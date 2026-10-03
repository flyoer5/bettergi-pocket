package com.bettergi.pocket.overlay

import android.content.Context
import android.graphics.PixelFormat
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
    private var seekBarX: SeekBar? = null
    private var seekBarY: SeekBar? = null
    private var textX: TextView? = null
    private var textY: TextView? = null
    private var tempX: Float = 0.5f
    private var tempY: Float = 0.99f

    val isVisible: Boolean get() = pickerView != null

    fun show() {
        if (pickerView != null) return
        val screen = screenSize()
        val settings = settingsRepository.get()
        tempX = (if (settings.quickSkipCustomPosition) settings.quickSkipPositionX else 0.5f).coerceIn(0.05f, 0.95f)
        tempY = (if (settings.quickSkipCustomPosition) settings.quickSkipPositionY else 0.99f).coerceIn(0.05f, 0.95f)

        val container = FrameLayout(themedContext)
        val crosshair = View(themedContext).apply {
            background = ContextCompat.getDrawable(themedContext, R.drawable.crosshair)
        }
        val size = dp(40)
        container.addView(crosshair, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        crosshair.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    tempX = (e.rawX / screen.first).coerceIn(0.05f, 0.95f)
                    tempY = (e.rawY / screen.second).coerceIn(0.05f, 0.95f)
                    syncControls()
                    true
                }
                else -> true // 消费未处理事件（如注入触摸的 POINTER_DOWN），避免系统对手势发 CANCEL 致拖动断触
            }
        }
        crosshairView = crosshair
        val chLp = overlayParams(
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
        val lp = overlayParams(
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
                val ratio = 0.05f + progress / 90f * 0.9f
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
        seekBarX?.progress = ((tempX - 0.05f) / 0.9f * 90).toInt()
        seekBarY?.progress = ((tempY - 0.05f) / 0.9f * 90).toInt()
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
        seekBarX = null
        seekBarY = null
        textX = null
        textY = null
    }

    private fun syncControls() {
        seekBarX?.progress = ((tempX - 0.05f) / 0.9f * 90).toInt()
        seekBarY?.progress = ((tempY - 0.05f) / 0.9f * 90).toInt()
        textX?.text = "${(tempX * 100).toInt()}%"
        textY?.text = "${(tempY * 100).toInt()}%"
        updateCrosshairOffset()
    }

    private fun updateCrosshairOffset() {
        val screen = screenSize()
        crosshairView?.translationX = tempX * screen.first - screen.first / 2f
        crosshairView?.translationY = tempY * screen.second - screen.second / 2f
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
}