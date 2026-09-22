package com.bettergi.pocket.root

import android.content.Context
import com.bettergi.pocket.input.AutomationAction
import com.bettergi.pocket.input.AutomationController
import com.bettergi.pocket.input.BackAction
import com.bettergi.pocket.log.AppLog
import com.bettergi.pocket.overlay.OverlayWindowController
import com.bettergi.pocket.input.ClickAction

/**
 * root 输入后端：把自动化动作经 [RootBridge] 用系统 input 命令注入为真实触摸事件。
 *
 * 统一走 InputManager 正规注入管线（su -c "input tap/keyevent"，等同真实手指）：
 * 不创建虚拟触摸屏设备，因此不会触发系统对用户进行中手势的 ACTION_CANCEL（断触），
 * 自动点击与用户手动操作完全并行，无需让路/避让/兜底。
 * ClickAction 坐标即当前显示方向下的屏幕物理坐标（横屏 2712x1220）。
 */
class RootAutomationController(
    private val context: Context,
    private val overlay: OverlayWindowController,
    private val bridge: RootBridge = RootBridge,
) : AutomationController {

    override fun execute(action: AutomationAction) {
        // 统一走系统 input 命令注入（InputManager 正规管线，等同真实手指）：
        // 不产生虚拟触摸屏设备，不会触发系统对用户手势的 ACTION_CANCEL（断触），
        // 自动点击与用户手动操作完全并行，无需任何让路/避让/兜底。
        when (action) {
            is ClickAction -> {
                // 点击位置命中悬浮窗/日志窗时临时穿透，避免注入打在悬浮窗上导致游戏无响应
                val passthrough = overlay.prepareClickPassthrough(action.x, action.y)
                val ok = bridge.inputTap(action.x, action.y)
                if (passthrough) overlay.restoreClickPassthrough()
                if (ok) {
                    overlay.flashTap(action.x, action.y)
                } else {
                    AppLog.w(TAG, "input tap failed at ${action.x},${action.y}")
                }
            }
            BackAction -> {
                bridge.inputBack()
            }
        }
    }

    private companion object {
        const val TAG = "BetterGI.Input"
    }
}
