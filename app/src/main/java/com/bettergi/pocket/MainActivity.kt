package com.bettergi.pocket

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bettergi.pocket.root.RootBridge
import com.bettergi.pocket.service.TriggerForegroundService
import com.bettergi.pocket.settings.AdvancedParam
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.settings.TriggerSettingsRepository

/**
 * App 主页：状态摘要 + 高级参数 + 权限引导。
 *
 * 功能开关（屏幕共享/自动对话等）统一在悬浮球面板操作，本页只承载
 * 悬浮球里没有的能力：写死参数的可视化配置与权限状态。
 */
class MainActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settingsRepository: TriggerSettingsRepository

    private var refreshTask: Runnable? = null
    private var updatingUi = false

    private lateinit var statusSummary: TextView
    private lateinit var advancedContainer: LinearLayout
    private lateinit var permissionOverlayState: TextView
    private lateinit var permissionNotificationState: TextView

    private val editViews = mutableMapOf<AdvancedParam, EditText>()

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settingsRepository = TriggerSettingsRepository(applicationContext)

        statusSummary = findViewById(R.id.status_summary)
        advancedContainer = findViewById(R.id.advanced_container)
        permissionOverlayState = findViewById(R.id.permission_overlay_state)
        permissionNotificationState = findViewById(R.id.permission_notification_state)

        findViewById<TextView>(R.id.home_version).text = "版本 ${appVersionName()}"
        findViewById<TextView>(R.id.btn_reset_advanced).setOnClickListener {
            settingsRepository.resetAdvanced()
            syncAdvancedEditors()
            Toast.makeText(this, "高级参数已恢复默认", Toast.LENGTH_SHORT).show()
        }
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

        buildAdvancedRows()

        settingsRepository.addListener { syncUi(it) }
        // 已授权时自动拉起助手（保持「打开即工作」体验）
        if (Settings.canDrawOverlays(this)) {
            startAssistantSilently()
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

    // ---- 高级参数 UI ----

    private fun buildAdvancedRows() {
        for (group in AdvancedParam.GROUPS) {
            val groupTitle = TextView(this).apply {
                text = group
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.overlay_gold))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(14) }
            }
            advancedContainer.addView(groupTitle)

            for (param in AdvancedParam.ofGroup(group)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) }
                }

                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val title = TextView(this).apply {
                    text = param.title
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.overlay_text))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val editor = EditText(this).apply {
                    setText(param.format(param.read(settingsRepository.get())))
                    textSize = 13f
                    gravity = Gravity.END
                    inputType = if (param.isInt) {
                        InputType.TYPE_CLASS_NUMBER
                    } else {
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    }
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.overlay_text))
                    background = null
                    minWidth = dp(90)
                    maxLines = 1
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setOnEditorActionListener { _, _, _ ->
                        saveParam(param, this)
                        true
                    }
                    setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) saveParam(param, this)
                    }
                }
                val unitLabel = if (param.unit.isNotEmpty()) {
                    TextView(this).apply {
                        text = param.unit
                        textSize = 11f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.overlay_text_muted))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = dp(4) }
                    }
                } else {
                    null
                }
                head.addView(title)
                head.addView(editor)
                unitLabel?.let { head.addView(it) }
                row.addView(head)

                val hint = TextView(this).apply {
                    text = "范围 ${param.format(param.min)} ~ ${param.format(param.max)}"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.overlay_text_muted))
                }
                row.addView(hint)
                advancedContainer.addView(row)
                editViews[param] = editor
            }
        }
    }

    private fun saveParam(param: AdvancedParam, editor: EditText) {
        val raw = editor.text?.toString()?.trim() ?: return
        if (raw.isEmpty()) {
            editor.setText(param.format(param.read(settingsRepository.get())))
            return
        }
        val value = raw.toDoubleOrNull()
        if (value == null) {
            editor.setText(param.format(param.read(settingsRepository.get())))
            Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show()
            return
        }
        val clamped = value.coerceIn(param.min, param.max)
        settingsRepository.setAdvanced(param, clamped)
        editor.setText(param.format(clamped))
    }

    private fun syncUi(settings: TriggerSettings) {
        updatingUi = true
        try {
            for ((param, editor) in editViews) {
                if (editor.hasFocus()) continue
                editor.setText(param.format(param.read(settings)))
            }
        } finally {
            updatingUi = false
        }
    }

    private fun syncAdvancedEditors() {
        val settings = settingsRepository.get()
        updatingUi = true
        try {
            for ((param, editor) in editViews) {
                editor.setText(param.format(param.read(settings)))
            }
        } finally {
            updatingUi = false
        }
    }

    // ---- 状态 ----

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
        val warn = ContextCompat.getColor(this, R.color.overlay_status_warn)

        val rootText = when {
            RootBridge.isRunning() -> "ROOT 已连接"
            RootBridge.isConnecting() -> "ROOT 连接中"
            else -> "ROOT 未连接"
        }
        val settings = settingsRepository.get()
        val share = if (settings.screenShareEnabled) "共享 开" else "共享 关"
        val dialog = if (settings.autoSkipEnabled) "对话 开" else "对话 关"
        statusSummary.text = "$rootText · $share · $dialog"
        statusSummary.setTextColor(
            when {
                RootBridge.isRunning() -> on
                RootBridge.isConnecting() -> warn
                else -> ContextCompat.getColor(this, R.color.overlay_status_off)
            },
        )

        val overlayOk = Settings.canDrawOverlays(this)
        permissionOverlayState.text = if (overlayOk) "已授权" else "未授权"
        permissionOverlayState.setTextColor(if (overlayOk) on else warn)

        val notificationOk = notificationGranted()
        permissionNotificationState.text = if (notificationOk) "已授权" else "未授权"
        permissionNotificationState.setTextColor(if (notificationOk) on else warn)
    }

    // ---- 助手 / 权限 ----

    private fun startAssistantSilently() {
        val intent = Intent(this, TriggerForegroundService::class.java).apply {
            action = TriggerForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_INTERVAL_MS = 1000L
    }
}
