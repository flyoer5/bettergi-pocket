package com.bettergi.pocket.overlay

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import com.bettergi.pocket.R
import com.bettergi.pocket.settings.TriggerSettingsRepository

/**
 * 点击节奏面板：两段可调间隔（对话跳过 / 黑屏点击），拖动即生效。
 *
 * 取值映射与 SeekBar max 严格对应，避免"界面能拖到 95%、实际被常量卡住"这类错位：
 *   skip : 300ms + progress(0..24) * 50ms  -> 300..1500
 *   black: 500ms + progress(0..25) * 100ms -> 500..3000
 */
class ClickTempoPanel(
    private val themedContext: Context,
    private val windowManager: WindowManager,
    private val settingsRepository: TriggerSettingsRepository,
    private val defaultPosition: () -> Pair<Int, Int>,
    private val dp: (Int) -> Int,
) {
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var seekSkip: SeekBar? = null
    private var seekBlack: SeekBar? = null
    private var textSkip: TextView? = null
    private var textBlack: TextView? = null

    val isVisible: Boolean get() = panelView != null

    fun show() {
        if (panelView != null) return
        val panel = LayoutInflater.from(themedContext).inflate(R.layout.overlay_click_tempo, null)
        panelView = panel
        val (dx, dy) = defaultPosition()
        val lp = OverlayWindowParams.create(
            width = dp(248),
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
            x = dx,
            y = dy,
        )
        panelParams = lp

        seekSkip = panel.findViewById(R.id.ct_seek_skip)
        seekBlack = panel.findViewById(R.id.ct_seek_black)
        textSkip = panel.findViewById(R.id.ct_text_skip)
        textBlack = panel.findViewById(R.id.ct_text_black)

        DragGestureHelper(
            touchSlop = 0,
            initialPosition = { lp.x to lp.y },
            onMove = { x, y ->
                lp.x = x
                lp.y = y
                try {
                    windowManager.updateViewLayout(panel, lp)
                } catch (_: Throwable) {
                }
            },
        ).attach(panel.findViewById(R.id.ct_drag))

        panel.findViewById<View>(R.id.ct_close).setOnClickListener { hide() }
        panel.findViewById<View>(R.id.ct_ok).setOnClickListener { hide() }
        panel.findViewById<View>(R.id.ct_reset).setOnClickListener {
            settingsRepository.resetClickTempo()
            val s = settingsRepository.get()
            render(SKIP_MIN, SKIP_STEP, s.skipClickIntervalMs, seekSkip, textSkip)
            render(BLACK_MIN, BLACK_STEP, s.blackClickIntervalMs, seekBlack, textBlack)
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (seekBar?.id) {
                    R.id.ct_seek_skip -> {
                        val v = SKIP_MIN + progress * SKIP_STEP
                        textSkip?.text = "${v}ms"
                        settingsRepository.setSkipClickInterval(v)
                    }
                    R.id.ct_seek_black -> {
                        val v = BLACK_MIN + progress * BLACK_STEP
                        textBlack?.text = "${v}ms"
                        settingsRepository.setBlackClickInterval(v)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        seekSkip?.setOnSeekBarChangeListener(listener)
        seekBlack?.setOnSeekBarChangeListener(listener)

        val s = settingsRepository.get()
        render(SKIP_MIN, SKIP_STEP, s.skipClickIntervalMs, seekSkip, textSkip)
        render(BLACK_MIN, BLACK_STEP, s.blackClickIntervalMs, seekBlack, textBlack)

        try {
            windowManager.addView(panel, lp)
        } catch (_: Throwable) {
            hide()
        }
    }

    fun hide() {
        panelView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Throwable) {
            }
        }
        panelView = null
        panelParams = null
        seekSkip = null
        seekBlack = null
        textSkip = null
        textBlack = null
    }

    private fun render(min: Int, step: Int, valueMs: Int, seek: SeekBar?, label: TextView?) {
        seek?.progress = ((valueMs - min) / step).coerceIn(0, seek.max)
        label?.text = "${valueMs}ms"
    }

    private companion object {
        const val SKIP_MIN = 300
        const val SKIP_STEP = 50
        const val BLACK_MIN = 500
        const val BLACK_STEP = 100
    }
}
