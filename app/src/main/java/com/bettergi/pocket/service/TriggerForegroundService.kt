package com.bettergi.pocket.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.bettergi.pocket.MainActivity
import com.bettergi.pocket.R
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.capture.ScreenCaptureController
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipFeature
import com.bettergi.pocket.feature.autoskip.OptionKeywords
import com.bettergi.pocket.genshin.GenshinLaunchMonitor
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.genshin.GenshinPackages
import com.bettergi.pocket.log.AppLog
import com.bettergi.pocket.root.RootAutomationController
import com.bettergi.pocket.root.RootBridge
import com.bettergi.pocket.root.RootStatusProbe
import com.bettergi.pocket.overlay.OverlayWindowController
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.settings.TriggerSettingsRepository
import com.bettergi.pocket.trigger.TriggerEngine

class TriggerForegroundService : Service() {
    private lateinit var settingsRepository: TriggerSettingsRepository
    private lateinit var captureController: ScreenCaptureController
    private lateinit var overlayController: OverlayWindowController
    private lateinit var genshinLauncher: GenshinLauncher
    private lateinit var genshinLaunchMonitor: GenshinLaunchMonitor
    private lateinit var engine: TriggerEngine
    private val rootProbe = RootStatusProbe()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var requestingCapturePermission = false

    @Volatile
    private var shutDown = false

    private var lastSettings: TriggerSettings? = null

    private val settingsListener: (TriggerSettings) -> Unit = { settings ->
        val previous = lastSettings
        lastSettings = settings
        if (settings.screenShareEnabled) {
            if (!captureController.isRunning()) {
                requestCapturePermission()
            }
            engine.start()
        } else {
            stopScreenShare()
        }
        // 自动启动原神：开关打开时立即生效，无需重启助手
        if (settings.autoLaunchGenshinEnabled && previous?.autoLaunchGenshinEnabled != true) {
            genshinLaunchMonitor.tryLaunchNow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "服务已创建")
        settingsRepository = TriggerSettingsRepository(applicationContext)
        captureController = ScreenCaptureController(applicationContext) {
            if (settingsRepository.get().screenShareEnabled) {
                settingsRepository.setScreenShareEnabled(false)
                Toast.makeText(
                    applicationContext,
                    "屏幕共享已停止，可能被其他录制应用占用",
                    Toast.LENGTH_SHORT,
                ).show()
                AppLog.w(TAG, "屏幕共享意外停止")
            }
        }
        genshinLauncher = GenshinLauncher(applicationContext)
        overlayController = OverlayWindowController(
            applicationContext,
            settingsRepository,
            genshinLauncher = genshinLauncher,
            onExit = {
                val stop = Intent(this, TriggerForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                startService(stop)
            },
        )
        genshinLaunchMonitor = GenshinLaunchMonitor(
            settingsRepository = settingsRepository,
            launcher = genshinLauncher,
            isGenshinInForeground = { rootProbe.foregroundPackage()?.let { GenshinPackages.isGenshinPackage(it) } },
            canAutoLaunch = { !requestingCapturePermission && !shutDown },
        )
        val recognitionAssets = RecognitionAssets(applicationContext.assets)
        engine = TriggerEngine(
            settingsRepository = settingsRepository,
            captureController = captureController,
            features = listOf(
                AutoPickFeature(),
                AutoSkipFeature(
                    recognitionAssets,
                    overlayController,
                    OptionKeywords.load(applicationContext.assets),
                ),
            ),
            actionController = RootAutomationController(applicationContext, overlayController),
        )
        settingsRepository.addListener(settingsListener)
        Thread {
            val ok = RootBridge.start()
            AppLog.i(TAG, "root 后端启动 -> ${if (ok) "成功" else "失败"}")
            mainHandler.post { overlayController.refreshStatus() }
            if (!ok) {
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "本版本需要 root 权限",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                // root 就绪后再做前台感知，避免与 start() 握手并发读写 socket
                genshinLaunchMonitor.start()
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "启动指令 action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                startInForeground(sharing = captureController.isRunning())
                overlayController.show()
            }
            ACTION_STOP -> {
                shutdown()
                stopSelf()
            }
            ACTION_CAPTURE_RESULT -> {
                requestingCapturePermission = false
                if (!settingsRepository.get().screenShareEnabled) {
                    captureController.stop()
                    startInForeground(sharing = false)
                    return START_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                if (resultData != null) {
                    startInForeground(sharing = true)
                    captureController.start(resultCode, resultData)
                    engine.start()
                } else {
                    settingsRepository.setScreenShareEnabled(false)
                }
            }
            ACTION_CAPTURE_DENIED -> {
                requestingCapturePermission = false
                settingsRepository.setScreenShareEnabled(false)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "服务已销毁")
        settingsRepository.removeListener(settingsListener)
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        if (shutDown) return
        shutDown = true
        AppLog.i(TAG, "服务关闭中")
        RootBridge.stop()
        genshinLaunchMonitor.stop()
        engine.release()
        captureController.stop()
        overlayController.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopScreenShare() {
        requestingCapturePermission = false
        engine.stop()
        captureController.stop()
        if (!shutDown) {
            startInForeground(sharing = false)
        }
    }

    private fun requestCapturePermission() {
        if (requestingCapturePermission) return
        requestingCapturePermission = true
        val intent = Intent(this, CapturePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun startInForeground(sharing: Boolean) {
        createNotificationChannelIfNeeded()
        val notification = buildNotification(sharing)
        if (Build.VERSION.SDK_INT >= 29) {
            val serviceType =
                if (sharing) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(sharing: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (sharing) "正在共享屏幕" else "点悬浮球可开启共享屏幕"
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("更好的原神")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("更好的原神")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "BetterGIPocket",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.bettergi.pocket.action.START"
        const val ACTION_STOP = "com.bettergi.pocket.action.STOP"
        const val ACTION_CAPTURE_RESULT = "com.bettergi.pocket.action.CAPTURE_RESULT"
        const val ACTION_CAPTURE_DENIED = "com.bettergi.pocket.action.CAPTURE_DENIED"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val TAG = "BetterGI.Service"
        private const val NOTIFICATION_CHANNEL_ID = "bettergi_pocket_trigger"
        private const val NOTIFICATION_ID = 1001
    }
}
