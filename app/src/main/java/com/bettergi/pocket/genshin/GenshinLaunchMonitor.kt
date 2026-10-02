package com.bettergi.pocket.genshin

import com.bettergi.pocket.settings.TriggerSettingsRepository

class GenshinLaunchMonitor(
    private val settingsRepository: TriggerSettingsRepository,
    private val launcher: GenshinLauncher,
    private val isGenshinInForeground: () -> Boolean?,
    private val canAutoLaunch: () -> Boolean,
) {
    private var started = false
    private var attempted = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        if (!canAttempt()) return
        attemptLaunch()
    }

    @Synchronized
    private fun canAttempt(): Boolean = GenshinPackages.shouldAttemptAutoLaunch(
        enabled = settingsRepository.get().autoLaunchGenshinEnabled,
        genshinInForeground = isGenshinInForeground(),
        alreadyAttempted = attempted,
        allowed = canAutoLaunch(),
    )

    @Synchronized
    private fun attemptLaunch() {
        attempted = true
        val result = launcher.launch()
        // 启动失败（未安装/异常）时重置 attempted，允许后续重试（如用户修复环境后）
        if (result !is GenshinLaunchResult.Started) {
            attempted = false
        }
    }

    fun stop() {
        started = false
        attempted = false
    }
}
