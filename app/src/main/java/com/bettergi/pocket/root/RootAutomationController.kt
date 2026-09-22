package com.bettergi.pocket.root

import android.content.Context
import android.view.WindowManager
import com.bettergi.pocket.input.AutomationAction
import com.bettergi.pocket.input.AutomationController
import com.bettergi.pocket.input.BackAction
import com.bettergi.pocket.log.AppLog
import com.bettergi.pocket.overlay.OverlayWindowController
import com.bettergi.pocket.input.ClickAction

/**
 * root 输入后端：把自动化动作经 [RootBridge] 注入为真实触摸事件。
 *
 * 坐标换算（对齐无障碍版语义）：
 * - ClickAction 的坐标来自 centerOnNativeCapture()，等价于当前显示方向下的屏幕物理坐标
 *   （横屏游戏为 2712x1220）
 * - uinput 设备轴空间 = 设备自然方向（helper 由 wm size 探测，竖屏 1220x2712）
 * - Android InputReader 对 DIRECT 触摸设备总是按"设备自然坐标系 + 当前显示旋转"映射到屏幕，
 *   因此这里把屏幕坐标反旋转回设备自然坐标再注入；坐标超出轴范围会被系统 clamp 到边缘，
 *   导致点击落在屏幕外（横屏 x=1356 > 轴 max 1220 的典型症状）
 */
class RootAutomationController(
    private val context: Context,
    private val overlay: OverlayWindowController,
    private val bridge: RootBridge = RootBridge,
) : AutomationController {

    @Volatile
    private var naturalSize: Pair<Int, Int>? = null

    @Volatile
    private var lastYieldLogMs: Long = 0L

    override fun execute(action: AutomationAction) {
        // 用户正在操作时自动注入统一让路，实现互不影响；松手后自动恢复
        if (UserTouchMonitor.isUserActive()) {
            val now = System.currentTimeMillis()
            if (now - lastYieldLogMs >= YIELD_LOG_INTERVAL_MS) {
                lastYieldLogMs = now
                AppLog.i(TAG, "用户正在操作，自动点击让路: $action")
            }
            return
        }
        when (action) {
            is ClickAction -> {
                if (bridge.uinputReady() == false) {
                    // input 命令模式：直接用屏幕坐标（无需反旋转）
                    if (bridge.inputTap(action.x, action.y)) {
                        overlay.flashTap(action.x, action.y)
                    } else {
                        AppLog.w(TAG, "input tap failed at ${action.x},${action.y}")
                    }
                    return
                }
                // uinput 模式：先反旋转到设备自然坐标再注入
                val (dx, dy) = toDeviceCoordinates(action.x, action.y)
                if (bridge.tap(dx, dy, action.durationMs)) {
                    overlay.flashTap(action.x, action.y)
                } else {
                    AppLog.w(TAG, "uinput tap failed at ${action.x},${action.y} -> $dx,$dy, fallback input-cmd")
                    bridge.downgradeToInputMode()
                    if (bridge.inputTap(action.x, action.y)) {
                        overlay.flashTap(action.x, action.y)
                    }
                }
            }
            BackAction -> {
                if (bridge.uinputReady() == false) {
                    bridge.inputBack()
                } else {
                    bridge.back()
                }
            }
        }
    }

    /** 屏幕坐标（当前显示方向）→ uinput 设备自然坐标（竖屏 wm size 空间）。 */
    private fun toDeviceCoordinates(sx: Int, sy: Int): Pair<Int, Int> {
        val size = naturalSize ?: bridge.screenSize()?.also { naturalSize = it } ?: return sx to sy
        val nw = size.first
        val nh = size.second
        if (nw <= 0 || nh <= 0) return sx to sy
        return when (rotationDegrees()) {
            90 -> (nw - 1 - sy) to sx
            180 -> (nw - 1 - sx) to (nh - 1 - sy)
            270 -> sy to (nh - 1 - sx)
            else -> sx to sy
        }
    }

    private fun rotationDegrees(): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return 0
            wm.defaultDisplay.rotation * 90
        } catch (_: Throwable) {
            0
        }
    }

    private companion object {
        private const val YIELD_LOG_INTERVAL_MS = 2000L
        const val TAG = "BetterGI.Input"
    }
}
