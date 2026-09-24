package com.bettergi.pocket.root

import com.bettergi.pocket.input.AutomationAction
import com.bettergi.pocket.input.AutomationController
import com.bettergi.pocket.input.BackAction
import com.bettergi.pocket.log.AppLog
import com.bettergi.pocket.overlay.OverlayWindowController
import com.bettergi.pocket.input.ClickAction

/**
 * root 输入后端：把自动化动作注入为真实触摸事件。
 *
 * 注入方式：
 * - 优先走 [RootBridge] 常驻 helper 的 socket：helper 在 root 侧执行
 *   `input tap/keyevent`（InputManager 正规注入管线，等同真实手指），
 *   无需每次点击重新 fork `su` 进程。
 * - 若 socket 不在线（root 后端未连接/重连中）则退化为经 `su -c input` 直连注入，
 *   保证输入功能在 root 刚失效、尚未重连的空窗期仍可用。
 *
 * 说明：注入走系统 input 命令（InputManager 正规管线），不创建虚拟触摸屏设备，
 * 因此不会触发系统对用户进行中手势的 ACTION_CANCEL（断触），
 * 自动点击与用户手动操作并行；点击位置命中悬浮窗/日志窗时临时穿透，
 * 避免注入落在悬浮窗上导致游戏无响应。
 */
class RootAutomationController(
    private val overlay: OverlayWindowController,
    private val bridge: RootBridge = RootBridge,
) : AutomationController {

    override fun execute(action: AutomationAction) {
        when (action) {
            is ClickAction -> {
                val passthrough = overlay.prepareClickPassthrough(action.x, action.y)
                val ok = injectTap(action.x, action.y)
                if (passthrough) overlay.restoreClickPassthrough()
                if (ok) {
                    overlay.flashTap(action.x, action.y)
                } else {
                    AppLog.w(TAG, "input tap failed at ${action.x},${action.y}")
                }
            }
            BackAction -> {
                if (!injectBack()) {
                    AppLog.w(TAG, "back action failed")
                }
            }
        }
    }

    /** 优先走 helper socket；socket 离线时退回 su -c input 直连。 */
    private fun injectTap(x: Int, y: Int): Boolean {
        if (bridge.request("TAP $x $y ${FALLBACK_DURATION_MS}", TAP_TIMEOUT_MS)?.startsWith("OK") == true) {
            return true
        }
        return bridge.inputTap(x, y)
    }

    private fun injectBack(): Boolean {
        if (bridge.request("BACK", TAP_TIMEOUT_MS)?.startsWith("OK") == true) {
            return true
        }
        return bridge.inputBack()
    }

    private companion object {
        const val TAG = "BetterGI.Input"
        const val FALLBACK_DURATION_MS = 50L
        const val TAP_TIMEOUT_MS = 1500L
    }
}