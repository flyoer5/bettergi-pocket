package com.bettergi.pocket.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.log.AppLog
import com.bettergi.pocket.root.RootBridge
import com.bettergi.pocket.settings.TriggerSettingsRepository
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale

/**
 * 悬浮日志窗口（手柄 + 正文 + 过滤 chips + 拖拽/缩放）。
 *
 * 从 OverlayWindowController 拆出的独立组件：负责日志窗口的创建/销毁/渲染/拖拽/缩放/过滤，
 * 通过 [onStatusChanged] 通知外部（如设置同步、状态文字更新）。
 */
class LogWindowPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val prefs: android.content.SharedPreferences,
    private val settingsRepository: TriggerSettingsRepository,
    private val screenSize: () -> Pair<Int, Int>,
    private val dp: (Int) -> Int,
    private val onStatusTextChanged: (String) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var logHandleView: View? = null
    private var logBodyView: View? = null
    private var logTitle: TextView? = null
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private var logFilterTag: String? = null
    private var logFilterAll: TextView? = null
    private var logFilterAutoSkip: TextView? = null
    private var logFilterRoot: TextView? = null
    private var logFilterOther: TextView? = null
    private var logResizeView: View? = null
    private var logHandleParams: WindowManager.LayoutParams? = null
    private var logBodyParams: WindowManager.LayoutParams? = null
    private val logLines = ArrayDeque<String>(MAX_LOG_LINES)
    private var logWindowVisible = false
    private val logTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA)

    private val logSink = object : AppLog.Sink {
        override fun onLog(line: String) {
            appendLogLine(line)
        }
    }

    val isVisible: Boolean get() = logWindowVisible

    /** 点击坐标是否落在日志窗口内（穿透判定用）。 */
    fun contains(x: Int, y: Int): Boolean {
        val handle = logHandleView ?: return false
        val body = logBodyView ?: return false
        val handleLoc = IntArray(2)
        val bodyLoc = IntArray(2)
        handle.getLocationOnScreen(handleLoc)
        body.getLocationOnScreen(bodyLoc)
        val handleW = handle.width
        val handleH = handle.height
        val bodyW = body.width
        val bodyH = body.height
        if (x in handleLoc[0]..handleLoc[0] + handleW && y in handleLoc[1]..handleLoc[1] + handleH) return true
        if (x in bodyLoc[0]..bodyLoc[0] + bodyW && y in bodyLoc[1]..bodyLoc[1] + bodyH) return true
        return false
    }

    /** 点击穿透：临时把日志窗口置为不可触摸（无障碍注入点击时避免点到日志窗）。 */
    fun applyPassthrough(passthrough: Boolean) {
        listOf(logHandleParams, logBodyParams).forEach { lp ->
            if (lp == null) return@forEach
            val hasFlag = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            if (passthrough == hasFlag) return@forEach
            lp.flags = if (passthrough) {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            (if (lp === logHandleParams) logHandleView else logBodyView)?.let { view ->
                try {
                    windowManager.updateViewLayout(view, lp)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun attach() {
        AppLog.addSink(logSink)
    }

    fun detach() {
        AppLog.removeSink(logSink)
    }

    /** 追加一行带时间戳的日志（供外部直接写，如状态行）。 */
    fun appendLog(message: String) {
        appendLogLine("${logTimeFormat.format(LocalTime.now())} $message")
    }

    /** 追加一行已格式化的日志（供 AppLog sink 使用，不再补时间戳）。 */
    private fun appendLogLine(line: String) {
        mainHandler.post {
            if (logLines.size >= MAX_LOG_LINES) {
                logLines.removeFirst()
            }
            logLines.addLast(line)
            if (!logWindowVisible || logText == null) return@post
            renderLogs()
            val scroll = logScroll ?: return@post
            val child = scroll.getChildAt(0) ?: return@post
            val atBottom = scroll.scrollY >= child.height - scroll.height - 4
            if (atBottom) {
                scroll.post { scroll.smoothScrollTo(0, scroll.getChildAt(0)?.height ?: 0) }
            }
        }
    }

    /** 按当前过滤标签渲染日志面板内容。 */
    private fun renderLogs() {
        if (logLines.isEmpty()) return
        val filter = logFilterTag
        val text = if (filter == null) {
            logLines.joinToString("\n")
        } else {
            logLines.filter { logTagOf(it) == filter }.joinToString("\n")
        }
        logText?.text = text.ifEmpty { "（无此功能日志）" }
    }

    /** 解析日志行的功能标签：对话 / root / 其他。 */
    private fun logTagOf(line: String): String? {
        return when {
            line.contains("[BetterGI.AutoSkip]") -> "autoskip"
            line.contains("[BetterGI.Root]") || line.contains("[helper]") || line.contains("root backend") -> "root"
            else -> "other"
        }
    }

    private fun setLogFilter(tag: String?) {
        logFilterTag = tag
        refreshLogFilterChips()
        renderLogs()
    }

    private fun refreshLogFilterChips() {
        val active = ContextCompat.getColor(context, R.color.overlay_log_green)
        val muted = ContextCompat.getColor(context, R.color.overlay_text_muted)
        fun tint(view: TextView?, isActive: Boolean) {
            view?.setTextColor(if (isActive) active else muted)
        }
        tint(logFilterAll, logFilterTag == null)
        tint(logFilterAutoSkip, logFilterTag == "autoskip")
        tint(logFilterRoot, logFilterTag == "root")
        tint(logFilterOther, logFilterTag == "other")
    }

    fun setVisible(visible: Boolean, persist: Boolean = true) {
        if (persist) {
            prefs.edit().putBoolean(KEY_LOG_VISIBLE, visible).apply()
        }
        logWindowVisible = visible
        if (visible) {
            show()
        } else {
            hide()
        }
    }

    fun toggle(): Boolean {
        setVisible(!logWindowVisible)
        return logWindowVisible
    }

    /** 更新日志标题（对话中/识别日志），由外部状态驱动。 */
    fun updateTitle(talking: Boolean) {
        logTitle?.text = if (talking) "正在对话中" else "识别日志"
    }

    /** 当前状态文字（root 连接/共享/对话），由外部调用。 */
    fun statusText(): String {
        val settings = settingsRepository.get()
        return "root=${if (RootBridge.isRunning()) "已连接" else "未连接"} 注入=input 共享=${if (settings.screenShareEnabled) "开" else "关"} 对话=${if (settings.autoSkipEnabled) "开" else "关"}"
    }

    private fun show() {
        if (logHandleView != null || logBodyView != null) return
        val themedContext = context
        val handle = LayoutInflater.from(themedContext).inflate(R.layout.overlay_log_handle, null)
        val body = LayoutInflater.from(themedContext).inflate(R.layout.overlay_log_body, null)
        logTitle = handle.findViewById(R.id.overlay_log_title)
        logText = body.findViewById(R.id.overlay_log_text)
        logScroll = body.findViewById(R.id.overlay_log_scroll)
        logFilterAll = body.findViewById(R.id.overlay_log_filter_all)
        logFilterAutoSkip = body.findViewById(R.id.overlay_log_filter_autoskip)
        logFilterRoot = body.findViewById(R.id.overlay_log_filter_root)
        logFilterOther = body.findViewById(R.id.overlay_log_filter_other)
        logResizeView = body.findViewById(R.id.overlay_log_resize)
        logFilterAll?.setOnClickListener { setLogFilter(null) }
        logFilterAutoSkip?.setOnClickListener { setLogFilter("autoskip") }
        logFilterRoot?.setOnClickListener { setLogFilter("root") }
        logFilterOther?.setOnClickListener { setLogFilter("other") }
        logResizeView?.let { setupLogResize(it) }
        setLogFilter(null)

        val width = dp(prefs.getInt(KEY_LOG_W, LOG_WIDTH_DP))
        val height = dp(prefs.getInt(KEY_LOG_H, LOG_DEFAULT_HEIGHT_DP))
        val (defaultX, defaultY) = defaultLogPosition()
        val x = prefs.getInt(KEY_LOG_X, defaultX)
        val y = prefs.getInt(KEY_LOG_Y, defaultY)

        val handleParams = OverlayWindowParams.create(
            width = width,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
            x = x,
            y = y,
        )
        val bodyParams = OverlayWindowParams.create(
            width = width,
            height = height,
            touchable = true,
            x = x,
            y = y + dp(28),
        )

        logHandleView = handle
        logBodyView = body
        logHandleParams = handleParams
        logBodyParams = bodyParams
        setupLogDrag(handle.findViewById(R.id.overlay_log_drag), handleParams)
        handle.findViewById<View>(R.id.overlay_log_close).setOnClickListener {
            setVisible(false)
        }
        try {
            windowManager.addView(body, bodyParams)
            windowManager.addView(handle, handleParams)
            handle.post {
                clampLogWindows()
                renderLogs()
                logScroll?.post { logScroll?.smoothScrollTo(0, logScroll?.getChildAt(0)?.height ?: 0) }
                appendLog("状态：${statusText()}")
            }
        } catch (_: Throwable) {
            hide()
        }
    }

    private fun hide() {
        listOf(logHandleView, logBodyView).forEach { view ->
            if (view != null) {
                try {
                    windowManager.removeView(view)
                } catch (_: Throwable) {
                }
            }
        }
        logHandleView = null
        logBodyView = null
        logHandleParams = null
        logBodyParams = null
        logTitle = null
        logText = null
        logScroll = null
        logFilterAll = null
        logFilterAutoSkip = null
        logFilterRoot = null
        logFilterOther = null
        logResizeView = null
    }

    /** 清除全部日志行（hide 后历史保留；调用方决定是否清）。 */
    fun clearLines() {
        logLines.clear()
    }


    private fun setupLogDrag(
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
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    clampLogWindows()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistLogPosition(lp)
                    true
                }
                else -> true // 消费未处理事件（如注入触摸的 POINTER_DOWN），避免系统对手势发 CANCEL 致拖动断触
            }
        }
    }

    private fun clampLogWindows() {
        val handleLp = logHandleParams ?: return
        val handle = logHandleView ?: return
        val screen = screenSize()
        val width = if (handle.width > 0) handle.width else dp(LOG_WIDTH_DP)
        val handleHeight = if (handle.height > 0) handle.height else dp(28)
        val bodyHeight = logBodyView?.height?.takeIf { it > 0 } ?: dp(120)
        val minY = 0
        handleLp.x = handleLp.x.coerceIn(0, (screen.first - width).coerceAtLeast(0))
        handleLp.y = handleLp.y.coerceIn(
            minY,
            (screen.second - handleHeight - bodyHeight).coerceAtLeast(minY),
        )
        updateLogLayouts()
    }

    private fun updateLogLayouts() {
        val handleLp = logHandleParams ?: return
        val bodyLp = logBodyParams ?: return
        val handle = logHandleView ?: return
        val body = logBodyView ?: return
        val handleHeight = if (handle.height > 0) handle.height else dp(28)
        bodyLp.x = handleLp.x
        bodyLp.y = handleLp.y + handleHeight
        try {
            windowManager.updateViewLayout(handle, handleLp)
        } catch (_: Throwable) {
        }
        try {
            windowManager.updateViewLayout(body, bodyLp)
        } catch (_: Throwable) {
        }
    }

    private fun persistLogPosition(lp: WindowManager.LayoutParams) {
        prefs.edit().putInt(KEY_LOG_X, lp.x).putInt(KEY_LOG_Y, lp.y).apply()
    }

    /** 日志窗口右下角缩放手柄：拖动改变窗口宽高，限制在屏幕内。 */
    private fun setupLogResize(handle: View) {
        var startW = 0
        var startH = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val bw = logBodyParams?.width
                    val bh = logBodyParams?.height
                    if (bw == null || bh == null) return@setOnTouchListener true
                    startW = bw
                    startH = bh
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val params = logBodyParams ?: return@setOnTouchListener true
                    val screen = screenSize()
                    val newW = (startW + (event.rawX - touchX).toInt())
                        .coerceIn(dp(150), (screen.first * 0.92f).toInt())
                    val newH = (startH + (event.rawY - touchY).toInt())
                        .coerceIn(dp(100), (screen.second * 0.85f).toInt())
                    params.width = newW
                    params.height = newH
                    logHandleParams?.width = newW
                    val body = logBodyView
                    val handleView = logHandleView
                    val handleLp = logHandleParams
                    try {
                        body?.let { windowManager.updateViewLayout(it, params) }
                        if (handleView != null && handleLp != null) {
                            windowManager.updateViewLayout(handleView, handleLp)
                        }
                    } catch (_: Throwable) {
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun defaultLogPosition(): Pair<Int, Int> {
        val screen = screenSize()
        val width = dp(LOG_WIDTH_DP)
        val height = dp(LOG_DEFAULT_HEIGHT_DP)
        val x = screen.first - width - dp(12)
        val y = (screen.second - height - dp(48)).coerceAtLeast(dp(28))
        return x to y
    }

    companion object {
        const val KEY_LOG_VISIBLE = "log_visible"
        private const val KEY_LOG_X = "log_x"
        private const val KEY_LOG_Y = "log_y"
        private const val KEY_LOG_W = "log_w"
        private const val KEY_LOG_H = "log_h"
        private const val LOG_WIDTH_DP = 240
        private const val LOG_DEFAULT_HEIGHT_DP = 148
        private const val MAX_LOG_LINES = 400
    }
}
