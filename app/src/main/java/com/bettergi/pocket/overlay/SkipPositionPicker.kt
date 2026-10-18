package com.bettergi.pocket.overlay

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.settings.TriggerSettingsRepository
import com.bettergi.pocket.trigger.screenBottomCenter

/**
 * 快速跳过点击位置选择器（十字线 + SeekBar + 拖拽面板 + 测试按钮）。
 *
 * 从 OverlayWindowController 拆出的独立组件：负责取点面板的显示/隐藏/十字线/百分比预览/落盘。
 */
class SkipPositionPicker(
    private val themedContext: Context,
    private val windowManager: WindowManager,
    private val settingsRepository: TriggerSettingsRepository,
    private val screenSize: () -> Pair<Int, Int>,
    private val dp: (Int) -> Int,
    private val defaultPosition: () -> Pair<Int, Int>,
    private val onFlashTap: (Int, Int) -> Unit,
) {
    private var pickerView: View? = null
    private var pickerParams: WindowManager.LayoutParams? = null
    private var crosshairView: View? = null
    private var crosshairParams: WindowManager.LayoutParams? = null
    private var fixedMarkerView: View? = null
    private var seekBarX: SeekBar? = null
    private var seekBarY: SeekBar? = null
    private var textX: TextView? = null
    private var textY: TextView? = null
    private companion object {
        const val SEEK_MIN = 0.05f
        const val SEEK_MAX = 0.99f
        const val SEEK_SPAN = SEEK_MAX - SEEK_MIN      // 0.94
        const val SEEK_PROGRESS_MAX = 90
    }

    private var tempX: Float = 0.5f
    private var tempY: Float = 0.99f

    val isVisible: Boolean get() = pickerView != null

    fun show() {
        if (pickerView != null) return
        val screen = screenSize()
        val settings = settingsRepository.get()
        tempX = (if (settings.quickSkipCustomPosition) settings.quickSkipPositionX else 0.5f).coerceIn(SEEK_MIN, SEEK_MAX)
        tempY = (if (settings.quickSkipCustomPosition) settings.quickSkipPositionY else 0.7f).coerceIn(SEEK_MIN, SEEK_MAX)

        val container = FrameLayout(themedContext)
        val crosshair = View(themedContext).apply {
            background = ContextCompat.getDrawable(themedContext, R.drawable.crosshair)
        }
        val size = dp(40)
        container.addView(crosshair, FrameLayout.LayoutParams(size, size, Gravity.CENTER))

        // 固定点（原版默认点，始终会被点击）用半透明圆环标出，让"两点并行"可见
        val marker = View(themedContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FFFFFF)
                setStroke(dp(1), 0xAAFFCC66.toInt())
            }
            isClickable = false
            isFocusable = false
        }
        val markerSize = dp(26)
        container.addView(marker, FrameLayout.LayoutParams(markerSize, markerSize, Gravity.CENTER))
        fixedMarkerView = marker
        crosshair.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    tempX = (e.rawX / screen.first).coerceIn(SEEK_MIN, SEEK_MAX)
                    tempY = (e.rawY / screen.second).coerceIn(SEEK_MIN, SEEK_MAX)
                    syncControls()
                    true
                }
                else -> true // 消费未处理事件（如注入触摸的 POINTER_DOWN），避免系统对手势发 CANCEL 致拖动断触
            }
        }
        crosshairView = crosshair
        val chLp = OverlayWindowParams.create(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            touchable = true,
            x = 0,
            y = 0,
        )
        crosshairParams = chLp

        val picker = LayoutInflater.from(themedContext).inflate(R.layout.overlay_skip_position_picker, null)
        pickerView = picker
        val (dx, dy) = defaultPosition()
        val lp = OverlayWindowParams.create(
            width = dp(240),
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
            x = dx,
            y = dy,
        )
        pickerParams = lp
        seekBarX = picker.findViewById(R.id.sp_picker_seek_x)
        seekBarY = picker.findViewById(R.id.sp_picker_seek_y)
        textX = picker.findViewById(R.id.sp_picker_x_text)
        textY = picker.findViewById(R.id.sp_picker_y_text)
        val dragHandle = picker.findViewById<View>(R.id.sp_picker_drag)
        DragGestureHelper(
            touchSlop = 0,
            initialPosition = { lp.x to lp.y },
            onMove = { x, y ->
                lp.x = x
                lp.y = y
                val screen = screenSize()
                lp.x = lp.x.coerceIn(0, (screen.first - picker.width).coerceAtLeast(0))
                lp.y = lp.y.coerceIn(0, (screen.second - picker.height).coerceAtLeast(0))
                try {
                    windowManager.updateViewLayout(picker, lp)
                } catch (_: Throwable) {
                }
            },
        ).attach(dragHandle)
        picker.findViewById<View>(R.id.sp_picker_close).setOnClickListener { hide() }
        picker.findViewById<View>(R.id.sp_picker_cancel).setOnClickListener { hide() }
        picker.findViewById<View>(R.id.sp_picker_ok).setOnClickListener {
            settingsRepository.setQuickSkipPosition(tempX, tempY)
            hide()
        }
        picker.findViewById<View>(R.id.sp_picker_test).setOnClickListener {
            onFlashTap((screen.first * tempX).toInt(), (screen.second * tempY).toInt())
        }
        val seek = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = SEEK_MIN + progress / SEEK_PROGRESS_MAX.toFloat() * SEEK_SPAN
                when (seekBar?.id) {
                    R.id.sp_picker_seek_x -> {
                        tempX = ratio
                        textX?.text = "${(ratio * 100).toInt()}%"
                    }
                    R.id.sp_picker_seek_y -> {
                        tempY = ratio
                        textY?.text = "${(ratio * 100).toInt()}%"
                    }
                }
                updateCrosshairOffset()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        seekBarX?.setOnSeekBarChangeListener(seek)
        seekBarY?.setOnSeekBarChangeListener(seek)
        seekBarX?.progress = toProgress(tempX)
        seekBarY?.progress = toProgress(tempY)
        textX?.text = "${(tempX * 100).toInt()}%"
        textY?.text = "${(tempY * 100).toInt()}%"

        try {
            windowManager.addView(container, chLp)
            windowManager.addView(picker, lp)
            updateCrosshairOffset()
        } catch (_: Throwable) {
            hide()
        }
    }

    fun hide() {
        pickerView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Throwable) {
            }
        }
        (crosshairView?.parent as? View)?.let { c ->
            try {
                windowManager.removeView(c)
            } catch (_: Throwable) {
            }
        }
        pickerView = null
        pickerParams = null
        crosshairView = null
        crosshairParams = null
        fixedMarkerView = null
        seekBarX = null
        seekBarY = null
        textX = null
        textY = null
    }

    private fun syncControls() {
        seekBarX?.progress = toProgress(tempX)
        seekBarY?.progress = toProgress(tempY)
        textX?.text = "${(tempX * 100).toInt()}%"
        textY?.text = "${(tempY * 100).toInt()}%"
        updateCrosshairOffset()
    }

    private fun updateCrosshairOffset() {
        val screen = screenSize()
        crosshairView?.translationX = tempX * screen.first - screen.first / 2f
        crosshairView?.translationY = tempY * screen.second - screen.second / 2f
        val (fx, fy) = screenBottomCenter(screen.first, screen.second)
        fixedMarkerView?.translationX = fx - screen.first / 2f
        fixedMarkerView?.translationY = fy - screen.second / 2f
    }

    private fun toProgress(value: Float): Int =
        ((value - SEEK_MIN) / SEEK_SPAN * SEEK_PROGRESS_MAX).toInt().coerceIn(0, SEEK_PROGRESS_MAX)

}