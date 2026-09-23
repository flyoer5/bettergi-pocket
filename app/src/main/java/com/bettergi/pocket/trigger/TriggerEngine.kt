package com.bettergi.pocket.trigger

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.bettergi.pocket.capture.ScreenCaptureController
import com.bettergi.pocket.input.ActionEmitter
import com.bettergi.pocket.input.AutomationAction
import com.bettergi.pocket.input.AutomationController
import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.settings.TriggerSettingsRepository

class TriggerEngine(
    private val settingsRepository: TriggerSettingsRepository,
    private val captureController: ScreenCaptureController,
    private val features: List<TriggerFeature>,
    private val actionController: AutomationController,
) {
    private val thread = HandlerThread("TriggerEngine").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var running = false

    @Volatile
    private var lastActionAtMs: Long = 0L

    fun start() {
        if (running) return
        running = true
        handler.post(tickRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return

            val settings = settingsRepository.get()
            if (!settings.screenShareEnabled) {
                stop()
                return
            }

            if (!captureController.isRunning()) {
                handler.postDelayed(this, WAIT_CAPTURE_MS)
                return
            }

            val enabled = features.filter { it.isEnabled(settings) }
            val needFrame = enabled.any { it.needsFrame(settings) }

            if (enabled.isEmpty()) {
                captureController.discardLatestImages()
                handler.postDelayed(this, SLOW_TICK_MS)
                return
            }

            try {
                val emitter = BufferedActionEmitter {
                    lastActionAtMs = System.currentTimeMillis()
                }
                if (needFrame) {
                    val captured = captureController.acquireLatestBgr()
                    if (captured != null) {
                        CaptureContent.fromBgr(captured.bgr, captured.width, captured.height).use { content ->
                            val tick = FeatureTick(captured.width, captured.height, content)
                            enabled.forEach { it.onTick(tick, settings, emitter) }
                        }
                    } else {
                        val size = captureController.capturedSize()
                        if (size != null) {
                            val tick = FeatureTick(size.first, size.second, content = null)
                            enabled.filterNot { it.needsFrame(settings) }
                                .forEach { it.onTick(tick, settings, emitter) }
                        }
                    }
                } else {
                    captureController.discardLatestImages()
                    val size = captureController.capturedSize()
                    if (size != null) {
                        val tick = FeatureTick(size.first, size.second, content = null)
                        enabled.forEach { it.onTick(tick, settings, emitter) }
                    }
                }
                emitter.flushTo(actionController)
            } catch (e: Exception) {
                Log.e(TAG, "recognize frame failed", e)
            }

            val now = System.currentTimeMillis()
            val interval = if (now - lastActionAtMs < ACTIVE_WINDOW_MS) settings.fastTickMs else settings.slowTickMs
            handler.postDelayed(this, interval)
        }
    }

    private class BufferedActionEmitter(
        private val onAction: () -> Unit = {},
    ) : ActionEmitter {
        private val pending = ArrayList<AutomationAction>(4)

        override fun emit(action: AutomationAction) {
            pending.add(action)
            onAction()
        }

        fun flushTo(controller: AutomationController) {
            pending.forEach { controller.execute(it) }
            pending.clear()
        }
    }

    private companion object {
        const val TAG = "BetterGI.Engine"
        const val FAST_TICK_MS = 200L
        const val SLOW_TICK_MS = 600L
        const val ACTIVE_WINDOW_MS = 1500L
        const val WAIT_CAPTURE_MS = 300L
    }
}
