package com.bettergi.pocket.trigger

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    /**
     * 动作执行线程：点击注入要 spawn `sh -c su -c input tap`，实测 100~300ms。
     * 放在引擎线程同步执行会把下一拍识别整体拖慢（快速跳过节流 500ms 会被吃掉一半）。
     * 单线程保证动作顺序，积压时不阻塞识别。
     */
    private val actionExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bg-action").apply { isDaemon = true }
    }

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
        // quitSafely 只保证"已投递的消息会跑完"，不阻塞返回；
        // 必须等引擎线程真正退出后再清模板 Mat，否则正在进行的匹配会踩到已释放内存
        runCatching { thread.join(1000L) }
        actionExecutor.shutdownNow()
        // 释放识别缓存里的 native Mat（OpenCV 内存不等 GC 才稳）
        features.forEach { runCatching { it.release() } }
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
                        CaptureContent.fromBgr(captured.bgr).use { content ->
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
                emitter.flushTo(actionExecutor, actionController)
            } catch (e: Throwable) {
                // 捕获全部异常/错误(含 OpenCV native 错误、OOM 边缘), 避免引擎线程崩溃后服务还在跑但无识别
                if (e is OutOfMemoryError) {
                    Log.e(TAG, "recognize frame OOM, skip this tick", e)
                } else {
                    Log.e(TAG, "recognize frame failed", e)
                }
            }

            val now = System.currentTimeMillis()
            val interval = if (now - lastActionAtMs < ACTIVE_WINDOW_MS) FAST_TICK_MS else SLOW_TICK_MS
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

        fun flushTo(executor: ExecutorService, controller: AutomationController) {
            if (pending.isEmpty()) return
            val batch = pending.toList()
            pending.clear()
            executor.execute {
                batch.forEach { action -> runCatching { controller.execute(action) } }
            }
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
