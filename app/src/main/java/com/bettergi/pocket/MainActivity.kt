package com.bettergi.pocket

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bettergi.pocket.root.RootBridge
import com.bettergi.pocket.service.TriggerForegroundService
import com.bettergi.pocket.settings.TriggerSettingsRepository

/**
 * App 主页：状态总览 + 权限引导 + 启动/停止助手。
 * 悬浮窗（悬浮球面板）不受影响，二者共用同一份设置与前台服务。
 */
class MainActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settingsRepository: TriggerSettingsRepository

    private var refreshTask: Runnable? = null

    private lateinit var versionText: TextView
    private lateinit var statusRoot: TextView
    private lateinit var statusAssistant: TextView
    private lateinit var statusShare: TextView
    private lateinit var statusAutoSkip: TextView
    private lateinit var statusQuickSkip: TextView
    private lateinit var permissionOverlayState: TextView
    private lateinit var permissionNotificationState: TextView

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settingsRepository = TriggerSettingsRepository(applicationContext)

        versionText = findViewById(R.id.home_version)
        statusRoot = findViewById(R.id.status_root)
        statusAssistant = findViewById(R.id.status_assistant)
        statusShare = findViewById(R.id.status_share)
        statusAutoSkip = findViewById(R.id.status_autoskip)
        statusQuickSkip = findViewById(R.id.status_quickskip)
        permissionOverlayState = findViewById(R.id.permission_overlay_state)
        permissionNotificationState = findViewById(R.id.permission_notification_state)

        findViewById<Button>(R.id.btn_overlay_permission).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
        findViewById<Button>(R.id.btn_notification_permission).setOnClickListener {
            requestNotificationPermission()
        }
        findViewById<Button>(R.id.btn_start_assistant).setOnClickListener { startAssistant() }
        findViewById<Button>(R.id.btn_stop_assistant).setOnClickListener { stopAssistant() }

        versionText.text = "版本 ${appVersionName()}"

        // 已授权时自动拉起助手（保持「打开即工作」体验）
        if (Settings.canDrawOverlays(this)) {
            startAssistant(showToast = false)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        startRefreshLoop()
    }

    override fun onPause() {
        super.onPause()
        stopRefreshLoop()
    }

    private fun startRefreshLoop() {
        stopRefreshLoop()
        val task = object : Runnable {
            override fun run() {
                refreshStatus()
                mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
        refreshTask = task
        mainHandler.postDelayed(task, REFRESH_INTERVAL_MS)
    }

    private fun stopRefreshLoop() {
        refreshTask?.let(mainHandler::removeCallbacks)
        refreshTask = null
    }

    private fun refreshStatus() {
        val on = ContextCompat.getColor(this, R.color.overlay_status_on)
        val off = ContextCompat.getColor(this, R.color.overlay_status_off)
        val warn = ContextCompat.getColor(this, R.color.overlay_status_warn)

        // ROOT：已连接 / 连接中… / 未连接
        when {
            RootBridge.isRunning() -> {
                statusRoot.text = "已连接"
                statusRoot.setTextColor(on)
            }
            RootBridge.isConnecting() -> {
                statusRoot.text = "连接中…"
                statusRoot.setTextColor(warn)
            }
            else -> {
                statusRoot.text = "未连接"
                statusRoot.setTextColor(warn)
            }
        }

        // 助手（前台服务）真实运行状态
        val assistantRunning = isAssistantRunning()
        statusAssistant.text = if (assistantRunning) "运行中" else "已停止"
        statusAssistant.setTextColor(if (assistantRunning) on else off)

        val settings = settingsRepository.get()
        statusShare.text = if (settings.screenShareEnabled) "开" else "关"
        statusShare.setTextColor(if (settings.screenShareEnabled) on else off)
        statusAutoSkip.text = if (settings.autoSkipEnabled) "开" else "关"
        statusAutoSkip.setTextColor(if (settings.autoSkipEnabled) on else off)
        statusQuickSkip.text = if (settings.quickSkipDialogueEnabled) "开" else "关"
        statusQuickSkip.setTextColor(if (settings.quickSkipDialogueEnabled) on else off)

        val overlayOk = Settings.canDrawOverlays(this)
        permissionOverlayState.text = if (overlayOk) "已授权" else "未授权"
        permissionOverlayState.setTextColor(if (overlayOk) on else warn)

        val notificationOk = notificationGranted()
        permissionNotificationState.text = if (notificationOk) "已授权" else "未授权"
        permissionNotificationState.setTextColor(if (notificationOk) on else warn)
    }

    /** 助手（TriggerForegroundService）是否在运行：查询本应用自己的服务列表。 */
    private fun isAssistantRunning(): Boolean {
        val am = getSystemService(ActivityManager::class.java) ?: return false
        return try {
            am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == TriggerForegroundService::class.java.name
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun startAssistant(showToast: Boolean = true) {
        if (!Settings.canDrawOverlays(this)) {
            if (showToast) Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (isAssistantRunning()) {
            if (showToast) Toast.makeText(this, "助手已在运行", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, TriggerForegroundService::class.java).apply {
            action = TriggerForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        if (showToast) Toast.makeText(this, "助手已启动", Toast.LENGTH_SHORT).show()
        mainHandler.postDelayed({ refreshStatus() }, 300)
    }

    private fun stopAssistant() {
        if (!isAssistantRunning()) {
            Toast.makeText(this, "助手未在运行", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, TriggerForegroundService::class.java).apply {
            action = TriggerForegroundService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "助手已停止", Toast.LENGTH_SHORT).show()
        mainHandler.postDelayed({ refreshStatus() }, 300)
        mainHandler.postDelayed({ refreshStatus() }, 1000)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "当前系统无需通知权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (notificationGranted()) {
            Toast.makeText(this, "通知权限已授权", Toast.LENGTH_SHORT).show()
            return
        }
        requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notificationGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun appVersionName(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "--"
        } catch (_: Exception) {
            "--"
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 1000L
    }
}
