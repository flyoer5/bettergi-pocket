package com.bettergi.pocket.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipEvents
import com.bettergi.pocket.genshin.GenshinLaunchResult
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.genshin.GenshinPackages
import com.bettergi.pocket.log.AppLog
import com.bettergi.pocket.root.RootBridge
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.settings.TriggerSettingsRepository

class OverlayWindowController(
    private val context: Context,
    private val settingsRepository: TriggerSettingsRepository,
    private val genshinLauncher: GenshinLauncher = GenshinLauncher(context),
    private val onExit: () -> Unit = {},
) : AutoSkipEvents {
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_BetterGIPocket)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastScreenW = 0
    private var lastScreenH = 0
    private var watchingScreen = false
    private var lastManualReconnectMs = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            relocateOverlays(force = false)
        }
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            relocateOverlays(force = true)
        }

        override fun onLowMemory() = Unit
    }

    private var rootView: View? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var panelScroll: ScrollView? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var chatBadge: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null

    private var updatingUi = false
    private var expanded = false
    private var transforming = false
    private var switchEnabled: SwitchCompat? = null
    private var switchAutoSkip: SwitchCompat? = null
    private var switchQuickSkip: SwitchCompat? = null
    private var switchSmartOption: SwitchCompat? = null
    private var switchBlackScreen: SwitchCompat? = null
    private var switchTapIndicator: SwitchCompat? = null
    private var switchExclamation: SwitchCompat? = null
    private var rowExclamation: View? = null
    private var rowTapIndicator: View? = null
    private val hideTapIndicator = Runnable { tapIndicatorView?.visibility = View.GONE }
    private var tapIndicatorView: View? = null
    private var tapIndicatorParams: WindowManager.LayoutParams? = null
    private var switchAutoPick: SwitchCompat? = null
    private var switchAutoLaunch: SwitchCompat? = null
    private var switchSnapEdge: SwitchCompat? = null
    private var launchHint: TextView? = null
    private var launchSubtitle: TextView? = null
    private var logToggleButton: ImageButton? = null
    private val logWindowPanel: LogWindowPanel by lazy {
        LogWindowPanel(
            context = themedContext,
            windowManager = windowManager,
            prefs = prefs,
            settingsRepository = settingsRepository,
            screenSize = { screenSize() },
            dp = { dp(it) },
        )
    }
    private val skipPositionPicker: SkipPositionPicker by lazy {
        SkipPositionPicker(
            themedContext = themedContext,
            windowManager = windowManager,
            settingsRepository = settingsRepository,
            screenSize = { screenSize() },
            dp = { dp(it) },
            defaultPosition = { defaultLogPosition() },
            onFlashTap = { x, y -> flashTap(x, y) },
        )
    }
    private var rowAutoSkip: View? = null
    private var rowQuickSkip: View? = null
    private var rowSmartOption: View? = null
    private var rowBlackScreen: View? = null
    private var rowAutoPick: View? = null
    private var rowLaunch: View? = null
    private var autoSkipExtras: View? = null
    private var autoSkipChevron: ImageView? = null
    private var autoSkipMenuExpanded = false
    private var launchExtras: View? = null
    private var launchChevron: ImageView? = null
    private var launchMenuExpanded = false
    private var rowQuickSkipPosition: View? = null
    private var spChevron: ImageView? = null
    private var spStatus: TextView? = null
    private var spReset: TextView? = null
    private var talkingUntilMs: Long = 0L
    private var lastTalkLogMs: Long = 0L
    private val clearTalkingRunnable = Runnable { refreshStatus() }

    private val idleFadeRunnable = Runnable { fadeBubble(IDLE_ALPHA) }

    private val settingsListener: (TriggerSettings) -> Unit = { settings ->
        updatingUi = true
        try {
            switchEnabled?.isChecked = settings.screenShareEnabled
            switchAutoSkip?.isChecked = settings.autoSkipEnabled
            switchQuickSkip?.isChecked = settings.quickSkipDialogueEnabled
            spStatus?.text = if (settings.quickSkipCustomPosition) {
                "自定义 ${(settings.quickSkipPositionX * 100).toInt()}%,${(settings.quickSkipPositionY * 100).toInt()}%"
            } else {
                "默认（屏幕底部中央）"
            }
            switchSmartOption?.isChecked = settings.smartOptionEnabled
            switchBlackScreen?.isChecked = settings.blackScreenClickEnabled
            switchTapIndicator?.isChecked = settings.showTapIndicator
            switchExclamation?.isChecked = settings.exclamationClickEnabled
            switchAutoPick?.isChecked = settings.autoPickEnabled
            switchAutoLaunch?.isChecked = settings.autoLaunchGenshinEnabled
            applyFeatureEnabled(settings)
            refreshLaunchHint()
            refreshStatus()
        } finally {
            updatingUi = false
        }
    }

    fun show() {
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val root = LayoutInflater.from(themedContext).inflate(R.layout.overlay_window, null)
        val bubble = root.findViewById<View>(R.id.overlay_bubble)
        val panel = root.findViewById<View>(R.id.overlay_panel)
        val collapse = root.findViewById<ImageButton>(R.id.overlay_collapse)
        val header = root.findViewById<View>(R.id.overlay_header)
        val enabledSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_enabled)
        val autoSkipSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_auto_skip)
        val quickSkipSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_quick_skip)
        val smartOptionSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_smart_option)
        val blackScreenSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_black_screen)
        val tapIndicatorSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_tap_indicator)
        val exclamationSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_exclamation)
        val autoPickSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_auto_pick)
        val autoLaunchSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_auto_launch)
        val snapEdgeSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_snap_edge)
        val logToggle = root.findViewById<ImageButton>(R.id.overlay_log_toggle)

        bubbleView = bubble
        panelView = panel
        panelScroll = root.findViewById(R.id.overlay_panel_scroll)
        statusDot = root.findViewById(R.id.overlay_status_dot)
        statusText = root.findViewById<TextView>(R.id.overlay_status_text).also { text ->
            text.setOnClickListener {
                val now = System.currentTimeMillis()
                // 防抖：2 秒内不重复手动重连；已有自动重连兜底
                if (!RootBridge.isRunning() && now - lastManualReconnectMs > 2000L) {
                    lastManualReconnectMs = now
                    Thread { RootBridge.start() }.start()
                }
            }
        }
        chatBadge = root.findViewById(R.id.overlay_chat_badge)
        switchEnabled = enabledSwitch
        switchAutoSkip = autoSkipSwitch
        switchQuickSkip = quickSkipSwitch
        switchSmartOption = smartOptionSwitch
        switchBlackScreen = blackScreenSwitch
        switchTapIndicator = tapIndicatorSwitch
        switchExclamation = exclamationSwitch
        switchAutoPick = autoPickSwitch
        switchAutoLaunch = autoLaunchSwitch
        switchSnapEdge = snapEdgeSwitch
        launchHint = root.findViewById(R.id.overlay_auto_launch_hint)
        launchSubtitle = root.findViewById(R.id.overlay_launch_subtitle)
        logToggleButton = logToggle
        rowAutoSkip = root.findViewById(R.id.overlay_row_auto_skip)
        rowQuickSkip = root.findViewById(R.id.overlay_row_quick_skip)
        rowQuickSkipPosition = root.findViewById(R.id.overlay_row_quick_skip_position)
        spStatus = root.findViewById(R.id.overlay_quick_skip_position_status)
        spReset = root.findViewById(R.id.overlay_quick_skip_position_reset)
        rowQuickSkipPosition?.setOnClickListener { skipPositionPicker.show() }
        spReset?.setOnClickListener { settingsRepository.resetQuickSkipPosition() }
        rowSmartOption = root.findViewById(R.id.overlay_row_smart_option)
        rowBlackScreen = root.findViewById(R.id.overlay_row_black_screen)
        rowTapIndicator = root.findViewById(R.id.overlay_row_tap_indicator)
        rowExclamation = root.findViewById(R.id.overlay_row_exclamation)
        rowLaunch = root.findViewById(R.id.overlay_row_launch)
        rowAutoPick = root.findViewById<View>(R.id.overlay_row_auto_pick).also { row ->
            row.visibility = if (AutoPickFeature.AVAILABLE) View.VISIBLE else View.GONE
            if (!AutoPickFeature.AVAILABLE) {
                settingsRepository.setAutoPickEnabled(false)
            }
        }
        autoSkipExtras = root.findViewById(R.id.overlay_auto_skip_extras)
        autoSkipChevron = root.findViewById(R.id.overlay_auto_skip_chevron)
        launchExtras = root.findViewById(R.id.overlay_launch_extras)
        launchChevron = root.findViewById(R.id.overlay_launch_chevron)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, dp(120))
        }

        setupDragAndClick(bubble, layoutParams) {
            setExpanded(true)
        }
        setupDrag(header, layoutParams)
        collapse.setOnClickListener { setExpanded(false) }
        logToggle.setOnClickListener { logWindowPanel.toggle() }
        rowAutoSkip?.setOnClickListener { setAutoSkipMenuExpanded(!autoSkipMenuExpanded) }
        rowLaunch?.setOnClickListener { setLaunchMenuExpanded(!launchMenuExpanded) }
        root.findViewById<View>(R.id.overlay_launch).setOnClickListener { launchGenshinFromButton() }
        root.findViewById<View>(R.id.overlay_exit).setOnClickListener { exitAssistant() }

        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setScreenShareEnabled(isChecked)
        }
        autoSkipSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoSkipEnabled(isChecked)
        }
        quickSkipSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setQuickSkipDialogueEnabled(isChecked)
        }
        smartOptionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setSmartOptionEnabled(isChecked)
        }
        blackScreenSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setBlackScreenClickEnabled(isChecked)
        }
        tapIndicatorSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setShowTapIndicator(isChecked)
        }
        exclamationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setExclamationClickEnabled(isChecked)
        }
        autoPickSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoPickEnabled(isChecked)
        }
        autoLaunchSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoLaunchGenshinEnabled(isChecked)
        }
        switchSnapEdge?.isChecked = prefs.getBoolean(KEY_SNAP_EDGE, true)
        snapEdgeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(KEY_SNAP_EDGE, isChecked).apply()
            if (isChecked) {
                params?.let { snapToEdge(it, animate = true) }
            }
        }
        rootView = root
        params = layoutParams
        windowManager.addView(root, layoutParams)
        logWindowPanel.setVisible(prefs.getBoolean(LogWindowPanel.KEY_LOG_VISIBLE, false), persist = false)
        setAutoSkipMenuExpanded(prefs.getBoolean(KEY_AUTO_SKIP_EXPANDED, false), persist = false)
        setLaunchMenuExpanded(prefs.getBoolean(KEY_LAUNCH_EXPANDED, false), persist = false)
        settingsRepository.addListener(settingsListener)
        startScreenWatch()
        logWindowPanel.attach()
        mainHandler.post { refreshStatus() }
        root.post {
            rememberScreen()
            clampToScreen(layoutParams)
            if (!expanded) snapToEdgeIfEnabled(layoutParams, animate = false)
            clampPanelHeight()
            scheduleIdleFade()
        }
    }

    /**
     * 无障碍手势会先打到可触摸的悬浮窗。只有点击落在这些窗口上时才临时穿透，
     * 避免每次模拟点击都改 FLAG_NOT_TOUCHABLE 导致窗口闪烁。
     */
    fun prepareClickPassthrough(x: Int, y: Int): Boolean {
        var needed = false
        if (windowContains(params, rootView, x, y)) {
            applyTouchPassthrough(params, rootView, passthrough = true)
            needed = true
        }
        if (logWindowPanel.contains(x, y)) {
            logWindowPanel.applyPassthrough(true)
            needed = true
        }
        return needed
    }

    fun restoreClickPassthrough() {
        applyTouchPassthrough(params, rootView, passthrough = false)
        logWindowPanel.applyPassthrough(false)
    }

    private fun applyTouchPassthrough(
        lp: WindowManager.LayoutParams?,
        view: View?,
        passthrough: Boolean,
    ) {
        if (lp == null || view == null) return
        val hasFlag = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        if (passthrough == hasFlag) return
        lp.flags = if (passthrough) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Throwable) {
        }
    }

    private fun windowContains(
        lp: WindowManager.LayoutParams?,
        view: View?,
        x: Int,
        y: Int,
    ): Boolean {
        if (lp == null || view == null) return false
        val width = if (view.width > 0) view.width else return false
        val height = if (view.height > 0) view.height else return false
        val slop = dp(8)
        return x >= lp.x - slop &&
            x < lp.x + width + slop &&
            y >= lp.y - slop &&
            y < lp.y + height + slop
    }

    fun hide() {
        stopScreenWatch()
        logWindowPanel.detach()
        mainHandler.removeCallbacks(idleFadeRunnable)
        mainHandler.removeCallbacks(clearTalkingRunnable)
        snapAnimator?.cancel()
        snapAnimator = null
        transforming = false
        expanded = false
        talkingUntilMs = 0L
        // 持久化日志窗口关闭状态，避免重启助手后日志又自动弹出
        logWindowPanel.setVisible(false)
        val view = rootView ?: return
        settingsRepository.removeListener(settingsListener)
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
        rootView = null
        bubbleView = null
        panelView = null
        panelScroll = null
        statusDot = null
        statusText = null
        chatBadge = null
        params = null
        switchEnabled = null
        switchAutoSkip = null
        switchQuickSkip = null
        skipPositionPicker.hide()
        rowQuickSkipPosition = null
        spChevron = null
        spStatus = null
        spReset = null
        switchSmartOption = null
        switchBlackScreen = null
        switchTapIndicator = null
        rowTapIndicator = null
        switchExclamation = null
        rowExclamation = null
        releaseTapIndicator()
        switchAutoPick = null
        switchAutoLaunch = null
        switchSnapEdge = null
        launchHint = null
        launchSubtitle = null
        logToggleButton = null
        rowAutoSkip = null
        rowQuickSkip = null
        rowSmartOption = null
        rowBlackScreen = null
        rowAutoPick = null
        rowLaunch = null
        autoSkipExtras = null
        autoSkipChevron = null
        launchExtras = null
        launchChevron = null
    }

    private fun launchGenshinFromButton() {
        when (genshinLauncher.launch()) {
            is GenshinLaunchResult.Started -> setExpanded(false)
            GenshinLaunchResult.NotInstalled -> {
                Toast.makeText(themedContext, "未安装原神", Toast.LENGTH_SHORT).show()
                refreshLaunchHint()
            }
            is GenshinLaunchResult.Failed -> {
                Toast.makeText(themedContext, "无法启动原神", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshLaunchHint() {
        val pkg = genshinLauncher.resolveInstalledPackage()
        if (pkg != null) {
            val name = GenshinPackages.displayName(pkg)
            launchSubtitle?.text = "打开已安装的$name"
            launchHint?.text = "启动助手时若未检测到${name}则打开一次"
        } else {
            launchSubtitle?.text = "未安装原神"
            launchHint?.text = "未安装原神"
        }
    }

    private fun exitAssistant() {
        if (transforming) return
        settingsRepository.setScreenShareEnabled(false)
        val panel = panelView
        if (panel != null && expanded) {
            transforming = true
            panel.animate().cancel()
            panel.animate()
                .alpha(0f)
                .scaleX(OverlayAnim.SCALE_PANEL_OUT)
                .scaleY(OverlayAnim.SCALE_PANEL_OUT)
                .setDuration(OverlayAnim.DUR_EXIT)
                .setInterpolator(OverlayAnim.EASE)
                .withEndAction { onExit() }
                .start()
        } else {
            onExit()
        }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value || transforming) return
        val bubble = bubbleView ?: return
        val panel = panelView ?: return
        val root = rootView ?: return
        expanded = value
        transforming = true
        bubble.animate().cancel()
        panel.animate().cancel()
        val ease = OverlayAnim.EASE

        if (value) {
            refreshLaunchHint()
            mainHandler.removeCallbacks(idleFadeRunnable)
            panel.alpha = 0f
            panel.scaleX = 0.84f
            panel.scaleY = 0.84f
            panel.visibility = View.VISIBLE
            root.post {
                params?.let { ensurePanelOnScreen(it) }
                applyPanelPivot(panel)
                clampPanelHeight()
                bubble.animate()
                    .alpha(0f)
                    .scaleX(OverlayAnim.SCALE_BUBBLE)
                    .scaleY(OverlayAnim.SCALE_BUBBLE)
                    .setDuration(OverlayAnim.DUR_BUBBLE_OUT)
                    .setInterpolator(ease)
                    .withEndAction {
                        bubble.visibility = View.GONE
                        bubble.scaleX = 1f
                        bubble.scaleY = 1f
                    }
                    .start()
                panel.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(OverlayAnim.DUR_EXPAND_PANEL)
                    .setInterpolator(ease)
                    .withEndAction {
                        transforming = false
                    }
                    .start()
            }
        } else {
            applyPanelPivot(panel)
            bubble.alpha = 0f
            bubble.scaleX = 0.72f
            bubble.scaleY = 0.72f
            bubble.visibility = View.VISIBLE
            panel.animate()
                .alpha(0f)
                .scaleX(OverlayAnim.SCALE_PANEL_IN)
                .scaleY(OverlayAnim.SCALE_PANEL_IN)
                .setDuration(OverlayAnim.DUR_COLLAPSE_PANEL)
                .setInterpolator(ease)
                .withEndAction {
                    panel.visibility = View.GONE
                    panel.alpha = 1f
                    panel.scaleX = 1f
                    panel.scaleY = 1f
                }
                .start()
            bubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(OverlayAnim.DUR_BUBBLE_IN)
                .setInterpolator(ease)
                .withEndAction {
                    transforming = false
                    params?.let { snapToEdgeIfEnabled(it, animate = true) }
                    scheduleIdleFade()
                }
                .start()
        }
    }

    private fun applyPanelPivot(panel: View) {
        val lp = params ?: return
        val screen = screenSize()
        val onRight = lp.x + (rootView?.width ?: 0) / 2f > screen.first / 2f
        panel.pivotX = if (onRight) panel.width.toFloat() else 0f
        panel.pivotY = 0f
    }

    private fun applyFeatureEnabled(settings: TriggerSettings) {
        val shareOn = settings.screenShareEnabled
        val autoSkipOn = shareOn && settings.autoSkipEnabled
        switchAutoSkip?.isEnabled = shareOn
        switchAutoPick?.isEnabled = shareOn
        switchQuickSkip?.isEnabled = autoSkipOn
        switchSmartOption?.isEnabled = autoSkipOn
        switchBlackScreen?.isEnabled = autoSkipOn
        switchTapIndicator?.isEnabled = shareOn
        switchExclamation?.isEnabled = autoSkipOn
        rowAutoSkip?.alpha = if (shareOn) 1f else 0.45f
        rowAutoPick?.alpha = if (shareOn) 1f else 0.45f
        rowQuickSkip?.alpha = if (autoSkipOn) 1f else 0.45f
        rowSmartOption?.alpha = if (autoSkipOn) 1f else 0.45f
        rowBlackScreen?.alpha = if (autoSkipOn) 1f else 0.45f
        rowTapIndicator?.alpha = if (shareOn) 1f else 0.45f
        rowExclamation?.alpha = if (autoSkipOn) 1f else 0.45f
    }

    override fun onTalkHistoryMatched() {
        mainHandler.post {
            val now = System.currentTimeMillis()
            // 节流：IN_DIALOG 每 tick 都会命中，日志 2s 一条
            if (now - lastTalkLogMs >= TALK_LOG_INTERVAL_MS) {
                lastTalkLogMs = now
                AppLog.i("BetterGI.AutoSkip", "检测到对话（TalkHistory 命中）")
            }
            talkingUntilMs = System.currentTimeMillis() + TALKING_HOLD_MS
            mainHandler.removeCallbacks(clearTalkingRunnable)
            mainHandler.postDelayed(clearTalkingRunnable, TALKING_HOLD_MS)
            refreshStatus()
        }
    }

    override fun onChatIconsRecognized(count: Int, topX: Int, topY: Int) {
        AppLog.i("BetterGI.AutoSkip", "识别到对话选项 $count 个，最高位置 ($topX, $topY)")
    }

    override fun onChatIconClicked(x: Int, y: Int) {
        AppLog.i("BetterGI.AutoSkip", "点击对话选项 ($x, $y)")
    }

    override fun onAutoSkipLog(message: String) {
        AppLog.i("BetterGI.AutoSkip", message)
    }

    override fun onBlackScreenClicked(x: Int, y: Int) {
        AppLog.i("BetterGI.AutoSkip", "点击黑屏转场 ($x, $y)")
    }

    override fun onOptionTextsRecognized(texts: List<String>) {
        if (texts.isNotEmpty()) {
            AppLog.d("BetterGI.AutoSkip", "选项文字：${texts.joinToString("、")}")
        }
    }

    override fun onIdleScan() {
        AppLog.d("BetterGI.AutoSkip", "扫描中，未检测到对话…")
    }


    fun flashTap(x: Int, y: Int) {
        if (!settingsRepository.get().showTapIndicator) return
        if (!Settings.canDrawOverlays(context)) return
        mainHandler.post {
            val size = tapIndicatorSizePx
            if (tapIndicatorView == null) {
                val dot = View(themedContext)
                dot.background = ContextCompat.getDrawable(themedContext, R.drawable.tap_indicator)
                val params = WindowManager.LayoutParams(
                    size,
                    size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                try {
                    windowManager.addView(dot, params)
                } catch (_: Throwable) {
                    return@post
                }
                tapIndicatorView = dot
                tapIndicatorParams = params
            }
            val params = tapIndicatorParams ?: return@post
            params.x = x - size / 2
            params.y = y - size / 2
            tapIndicatorView?.visibility = View.VISIBLE
            try {
                windowManager.updateViewLayout(tapIndicatorView, params)
            } catch (_: Throwable) {
            }
            mainHandler.removeCallbacks(hideTapIndicator)
            mainHandler.postDelayed(hideTapIndicator, TAP_INDICATOR_MS)
        }
    }

    private fun releaseTapIndicator() {
        mainHandler.removeCallbacks(hideTapIndicator)
        val view = tapIndicatorView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
        tapIndicatorView = null
        tapIndicatorParams = null
    }

    /** 面板内容超过屏幕 72% 时限制滚动高度，保证小屏机器也能看全菜单 */
    private fun clampPanelHeight() {
        val scroll = panelScroll ?: return
        val maxHeight = (screenSize().second * 0.72f).toInt()
        rootView?.post {
            val content = scroll.getChildAt(0) ?: return@post
            val target =
                if (content.height > maxHeight) maxHeight else WindowManager.LayoutParams.WRAP_CONTENT
            if (scroll.layoutParams.height != target) {
                scroll.layoutParams = scroll.layoutParams.apply { height = target }
            }
        }
    }

    private fun setAutoSkipMenuExpanded(expanded: Boolean, persist: Boolean = true) {
        autoSkipMenuExpanded = expanded
        if (persist) {
            prefs.edit().putBoolean(KEY_AUTO_SKIP_EXPANDED, expanded).apply()
        }
        autoSkipExtras?.visibility = if (expanded) View.VISIBLE else View.GONE
        if (!expanded) {
            panelScroll?.post { panelScroll?.scrollTo(0, 0) }
        }
        autoSkipChevron?.animate()?.rotation(if (expanded) 90f else 0f)?.setDuration(OverlayAnim.DUR_INDICATOR)?.start()
        clampPanelHeight()
    }

    private fun setLaunchMenuExpanded(expanded: Boolean, persist: Boolean = true) {
        launchMenuExpanded = expanded
        if (persist) {
            prefs.edit().putBoolean(KEY_LAUNCH_EXPANDED, expanded).apply()
        }
        launchExtras?.visibility = if (expanded) View.VISIBLE else View.GONE
        if (!expanded) {
            panelScroll?.post { panelScroll?.scrollTo(0, 0) }
        }
        launchChevron?.animate()?.rotation(if (expanded) 90f else 0f)?.setDuration(OverlayAnim.DUR_INDICATOR)?.start()
        clampPanelHeight()
    }

    private fun isTalking(): Boolean = System.currentTimeMillis() < talkingUntilMs

    fun refreshStatus() {
        val enabled = settingsRepository.get().screenShareEnabled
        val talking = isTalking()
        val rootOk = RootBridge.isRunning()
        val colorRes = when {
            !rootOk -> R.color.overlay_status_warn
            talking -> R.color.overlay_status_on
            enabled -> R.color.overlay_status_on
            else -> R.color.overlay_status_off
        }
        val color = ContextCompat.getColor(themedContext, colorRes)
        (statusDot?.background?.mutate() as? GradientDrawable)?.setColor(color)
            ?: statusDot?.background?.let { drawable ->
                DrawableCompat.setTint(DrawableCompat.wrap(drawable).mutate(), color)
            }
        statusText?.text = when {
            !rootOk -> "root 未连接"
            talking -> "正在对话中"
            enabled -> "已启动"
            else -> "未启动"
        }
        statusText?.setTextColor(
            when {
                !rootOk -> ContextCompat.getColor(themedContext, R.color.overlay_status_warn)
                talking || enabled -> ContextCompat.getColor(themedContext, R.color.overlay_status_on)
                else -> ContextCompat.getColor(themedContext, R.color.overlay_text_muted)
            },
        )
        logWindowPanel.updateTitle(talking)
        val badge = chatBadge
        if (badge != null) {
            val showBadge = talking
            if (showBadge && badge.visibility != View.VISIBLE) {
                badge.alpha = 0f
                badge.visibility = View.VISIBLE
                badge.animate().alpha(1f).setDuration(OverlayAnim.DUR_INDICATOR).start()
            } else if (!showBadge && badge.visibility == View.VISIBLE) {
                badge.animate().alpha(0f).setDuration(OverlayAnim.DUR_INDICATOR).withEndAction {
                    badge.visibility = View.GONE
                }.start()
            }
        }
    }







    private fun setupDrag(
        dragHandle: View,
        lp: WindowManager.LayoutParams,
    ) {
        DragGestureHelper(
            touchSlop = touchSlop,
            initialPosition = { lp.x to lp.y },
            onDown = { snapAnimator?.cancel() },
            onMove = { x, y ->
                lp.x = x
                lp.y = y
                clampToScreen(lp)
            },
            onDragEnd = { persistPosition(lp) },
        ).attach(dragHandle)
    }

    private fun setupDragAndClick(
        dragHandle: View,
        lp: WindowManager.LayoutParams,
        onClick: () -> Unit,
    ) {
        DragGestureHelper(
            touchSlop = touchSlop,
            initialPosition = { lp.x to lp.y },
            onDown = {
                snapAnimator?.cancel()
                wakeBubble()
                dragHandle.animate().scaleX(OverlayAnim.SCALE_PRESS).scaleY(OverlayAnim.SCALE_PRESS).setDuration(OverlayAnim.DUR_PRESS).start()
            },
            onMove = { x, y ->
                lp.x = x
                lp.y = y
                clampToScreen(lp)
            },
            onClick = {
                dragHandle.animate().scaleX(1f).scaleY(1f).setDuration(OverlayAnim.DUR_RELEASE).start()
                onClick()
                if (!expanded) scheduleIdleFade()
            },
            onDragEnd = {
                dragHandle.animate().scaleX(1f).scaleY(1f).setDuration(OverlayAnim.DUR_RELEASE).start()
                persistPosition(lp)
                snapToEdgeIfEnabled(lp, animate = true)
                if (!expanded) scheduleIdleFade()
            },
        ).attach(dragHandle)
    }

    private fun snapToEdgeIfEnabled(lp: WindowManager.LayoutParams, animate: Boolean) {
        if (!prefs.getBoolean(KEY_SNAP_EDGE, true)) {
            clampToScreen(lp)
            persistPosition(lp)
            return
        }
        snapToEdge(lp, animate)
    }

    private fun snapToEdge(lp: WindowManager.LayoutParams, animate: Boolean) {
        val view = rootView ?: return
        // 先取消进行中的动画，避免旧动画 updateListener 与下方 persist 竞争写 lp.x
        snapAnimator?.cancel()
        val screen = screenSize()
        val width = if (view.width > 0) view.width else dp(48)
        val targetX = if (lp.x + width / 2 < screen.first / 2) 0 else screen.first - width
        if (!animate || lp.x == targetX) {
            lp.x = targetX
            clampToScreen(lp)
            persistPosition(lp)
            return
        }
        val fromX = lp.x
        snapAnimator = ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                lp.x = animator.animatedValue as Int
                updateLayout(lp)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistPosition(lp)
                }
            })
            start()
        }
    }

    private fun ensurePanelOnScreen(lp: WindowManager.LayoutParams) {
        clampToScreen(lp)
    }

    private fun clampToScreen(lp: WindowManager.LayoutParams) {
        val view = rootView ?: return
        val screen = screenSize()
        val width = if (view.width > 0) view.width else dp(48)
        val height = if (view.height > 0) view.height else dp(48)
        lp.x = lp.x.coerceIn(0, (screen.first - width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screen.second - height).coerceAtLeast(0))
        updateLayout(lp)
    }

    private fun updateLayout(lp: WindowManager.LayoutParams) {
        val view = rootView ?: return
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Throwable) {
        }
    }

    private fun persistPosition(lp: WindowManager.LayoutParams) {
        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
    }

    private fun wakeBubble() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        fadeBubble(1f)
    }

    private fun scheduleIdleFade() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        if (!expanded) {
            mainHandler.postDelayed(idleFadeRunnable, IDLE_DELAY_MS)
        }
    }

    private fun fadeBubble(alpha: Float) {
        val bubble = bubbleView ?: return
        if (expanded || bubble.visibility != View.VISIBLE) return
        bubble.animate().alpha(alpha).setDuration(220).start()
    }

    private fun startScreenWatch() {
        if (watchingScreen) return
        watchingScreen = true
        displayManager.registerDisplayListener(displayListener, mainHandler)
        context.registerComponentCallbacks(configCallbacks)
    }

    private fun stopScreenWatch() {
        if (!watchingScreen) return
        watchingScreen = false
        displayManager.unregisterDisplayListener(displayListener)
        context.unregisterComponentCallbacks(configCallbacks)
    }

    private fun rememberScreen() {
        val screen = screenSize()
        lastScreenW = screen.first
        lastScreenH = screen.second
    }

    private fun relocateOverlays(force: Boolean) {
        val root = rootView ?: return
        val screen = screenSize()
        if (!force && screen.first == lastScreenW && screen.second == lastScreenH) return
        snapAnimator?.cancel()
        root.post {
            rememberScreen()
            val lp = params ?: return@post
            clampToScreen(lp)
            if (!expanded) {
                snapToEdgeIfEnabled(lp, animate = false)
            } else {
                persistPosition(lp)
            }
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun defaultLogPosition(): Pair<Int, Int> {
        val screen = screenSize()
        val height = dp(LOG_DEFAULT_HEIGHT_DP)
        val margin = dp(12)
        val x = margin
        val y = (screen.second - height - dp(48)).coerceAtLeast(statusBarHeight())
        return x to y
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) {
            return context.resources.getDimensionPixelSize(id)
        }
        return dp(28)
    }

    private val tapIndicatorSizePx: Int by lazy { dp(TAP_INDICATOR_SIZE_DP) }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private companion object {
        private const val PREFS_NAME = "overlay_window"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_AUTO_SKIP_EXPANDED = "auto_skip_expanded"
        private const val KEY_LAUNCH_EXPANDED = "launch_expanded"
        private const val KEY_SNAP_EDGE = "snap_edge"
        private const val LOG_DEFAULT_HEIGHT_DP = 148
        private const val IDLE_ALPHA = 0.62f
        private const val IDLE_DELAY_MS = 2400L
        private const val TALK_LOG_INTERVAL_MS = 2000L
        private const val TALKING_HOLD_MS = 2000L
        private const val TAP_INDICATOR_SIZE_DP = 28
        private const val TAP_INDICATOR_MS = 450L
    }
}
