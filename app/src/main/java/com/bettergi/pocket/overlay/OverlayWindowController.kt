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
import android.widget.FrameLayout
import android.widget.SeekBar
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipEvents
import com.bettergi.pocket.genshin.GenshinLauncher
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
    private var spPickerView: View? = null
    private var spPickerParams: WindowManager.LayoutParams? = null
    private var spCrosshairView: View? = null
    private var spCrosshairParams: WindowManager.LayoutParams? = null
    private var spSeekBarX: SeekBar? = null
    private var spSeekBarY: SeekBar? = null
    private var spTextX: TextView? = null
    private var spTextY: TextView? = null
    @Volatile private var spTempX: Float = 0.5f
    @Volatile private var spTempY: Float = 0.99f

    private var talkingUntilMs: Long = 0L
    private var lastTalkLogMs: Long = 0L
    private val clearTalkingRunnable = Runnable { refreshStatus() }

    private val idleFadeRunnable = Runnable { fadeBubble(IDLE_ALPHA) }

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
        rowQuickSkipPosition?.setOnClickListener { showSkipPositionPicker() }
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
        autoSkipChevron?.animate()?.rotation(if (expanded) 90f else 0f)?.setDuration(160)?.start()
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
        launchChevron?.animate()?.rotation(if (expanded) 90f else 0f)?.setDuration(160)?.start()
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
                badge.animate().alpha(1f).setDuration(160).start()
            } else if (!showBadge && badge.visibility == View.VISIBLE) {
                badge.animate().alpha(0f).setDuration(160).withEndAction {
                    badge.visibility = View.GONE
                }.start()
            }
        }
    }



    // ===== 跳过位置取点 =====

    private fun showSkipPositionPicker() {
        if (spPickerView != null) return
        val screen = screenSize()
        val settings = settingsRepository.get()
        spTempX = (if (settings.quickSkipCustomPosition) settings.quickSkipPositionX else 0.5f).coerceIn(0.05f, 0.95f)
        spTempY = (if (settings.quickSkipCustomPosition) settings.quickSkipPositionY else 0.99f).coerceIn(0.05f, 0.95f)

        val container = FrameLayout(themedContext)
        val crosshair = View(themedContext).apply {
            background = ContextCompat.getDrawable(themedContext, R.drawable.crosshair)
        }
        val size = dp(40)
        container.addView(crosshair, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        crosshair.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    spTempX = (e.rawX / screen.first).coerceIn(0.05f, 0.95f)
                    spTempY = (e.rawY / screen.second).coerceIn(0.05f, 0.95f)
                    syncSpControls()
                    true
                }
                else -> true // 消费未处理事件（如注入触摸的 POINTER_DOWN），避免系统对手势发 CANCEL 致拖动断触
            }
        }
        spCrosshairView = crosshair
        val chLp = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            touchable = true,
            x = 0,
            y = 0,
        )
        spCrosshairParams = chLp

        val picker = LayoutInflater.from(themedContext).inflate(R.layout.overlay_skip_position_picker, null)
        spPickerView = picker
        val (dx, dy) = defaultLogPosition()
        val lp = overlayParams(
            width = dp(240),
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
            x = dx,
            y = dy,
        )
        spPickerParams = lp
        spSeekBarX = picker.findViewById(R.id.sp_picker_seek_x)
        spSeekBarY = picker.findViewById(R.id.sp_picker_seek_y)
        spTextX = picker.findViewById(R.id.sp_picker_x_text)
        spTextY = picker.findViewById(R.id.sp_picker_y_text)
        val dragHandle = picker.findViewById<View>(R.id.sp_picker_drag)
        var spStartX = 0
        var spStartY = 0
        var spTouchX = 0f
        var spTouchY = 0f
        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    spStartX = lp.x
                    spStartY = lp.y
                    spTouchX = event.rawX
                    spTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = spStartX + (event.rawX - spTouchX).toInt()
                    lp.y = spStartY + (event.rawY - spTouchY).toInt()
                    val screen = screenSize()
                    lp.x = lp.x.coerceIn(0, (screen.first - picker.width).coerceAtLeast(0))
                    lp.y = lp.y.coerceIn(0, (screen.second - picker.height).coerceAtLeast(0))
                    try {
                        windowManager.updateViewLayout(picker, lp)
                    } catch (_: Throwable) {
                    }
                    true
                }
                else -> true // 消费未处理事件（如注入触摸的 POINTER_DOWN），避免系统对手势发 CANCEL 致拖动断触
            }
        }
        picker.findViewById<View>(R.id.sp_picker_close).setOnClickListener { hideSkipPositionPicker() }
        picker.findViewById<View>(R.id.sp_picker_cancel).setOnClickListener { hideSkipPositionPicker() }
        picker.findViewById<View>(R.id.sp_picker_ok).setOnClickListener {
            settingsRepository.setQuickSkipPosition(spTempX, spTempY)
            hideSkipPositionPicker()
        }
        picker.findViewById<View>(R.id.sp_picker_test).setOnClickListener {
            flashTap((screen.first * spTempX).toInt(), (screen.second * spTempY).toInt())
        }
        val seek = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = 0.05f + progress / 90f * 0.9f
                when (seekBar?.id) {
                    R.id.sp_picker_seek_x -> {
                        spTempX = ratio
                        spTextX?.text = "${(ratio * 100).toInt()}%"
                    }
                    R.id.sp_picker_seek_y -> {
                        spTempY = ratio
                        spTextY?.text = "${(ratio * 100).toInt()}%"
                    }
                }
                updateCrosshairOffset()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        spSeekBarX?.setOnSeekBarChangeListener(seek)
        spSeekBarY?.setOnSeekBarChangeListener(seek)
        spSeekBarX?.progress = ((spTempX - 0.05f) / 0.9f * 90).toInt()
        spSeekBarY?.progress = ((spTempY - 0.05f) / 0.9f * 90).toInt()
        spTextX?.text = "${(spTempX * 100).toInt()}%"
        spTextY?.text = "${(spTempY * 100).toInt()}%"

        try {
            windowManager.addView(container, chLp)
            windowManager.addView(picker, lp)
            updateCrosshairOffset()
        } catch (_: Throwable) {
            hideSkipPositionPicker()
        }
    }

    private fun hideSkipPositionPicker() {
        spPickerView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Throwable) {
            }
        }
        (spCrosshairView?.parent as? View)?.let { c ->
            try {
                windowManager.removeView(c)
            } catch (_: Throwable) {
            }
        }
        spPickerView = null
        spPickerParams = null
        spCrosshairView = null
        spCrosshairParams = null
        spSeekBarX = null
        spSeekBarY = null
        spTextX = null
        spTextY = null
    }

    private fun syncSpControls() {
        spSeekBarX?.progress = ((spTempX - 0.05f) / 0.9f * 90).toInt()
        spSeekBarY?.progress = ((spTempY - 0.05f) / 0.9f * 90).toInt()
        spTextX?.text = "${(spTempX * 100).toInt()}%"
        spTextY?.text = "${(spTempY * 100).toInt()}%"
        updateCrosshairOffset()
    }

    private fun updateCrosshairOffset() {
        val screen = screenSize()
        spCrosshairView?.translationX = spTempX * screen.first - screen.first / 2f
        spCrosshairView?.translationY = spTempY * screen.second - screen.second / 2f
    }

    private fun overlayParams(
        width: Int,
        height: Int,
        touchable: Boolean,
        x: Int,
        y: Int,
    ): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }






    private fun setupDrag(
        dragHandle: View,
        lp: WindowManager.LayoutParams,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    clampToScreen(lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistPosition(lp)
                    true
                }
                else -> true // 消费未处理事件（如注入触摸的 POINTER_DOWN），避免系统对手势发 CANCEL 致拖动断触
            }
        }
    }

    private fun setupDragAndClick(
        dragHandle: View,
        lp: WindowManager.LayoutParams,
        onClick: () -> Unit,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        dragHandle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    moved = false
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    wakeBubble()
                    dragHandle.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        clampToScreen(lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragHandle.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (!moved) {
                        v.performClick()
                        onClick()
                    } else {
                        persistPosition(lp)
                        snapToEdgeIfEnabled(lp, animate = true)
                    }
                    if (!expanded) scheduleIdleFade()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragHandle.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (moved) {
                        persistPosition(lp)
                        snapToEdgeIfEnabled(lp, animate = true)
                    }
                    if (!expanded) scheduleIdleFade()
                    true
                }
                else -> true // 消费未处理事件（如注入触摸的 POINTER_DOWN），避免系统对手势发 CANCEL 致拖动断触
            }
        }
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


    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) {
            return context.resources.getDimensionPixelSize(id)
        }
        return dp(28)
    }

    /** 取点窗默认初始位置（左下角）。 */
    private fun defaultLogPosition(): Pair<Int, Int> {
        val screen = screenSize()
        val height = dp(LOG_DEFAULT_HEIGHT_DP)
        val margin = dp(12)
        val x = margin
        val y = (screen.second - height - dp(48)).coerceAtLeast(statusBarHeight())
        return x to y
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
