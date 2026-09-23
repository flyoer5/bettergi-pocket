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

    fun start() {
        if (started) return
        started = true
        if (!GenshinPackages.shouldAttemptAutoLaunch(
                enabled = settingsRepository.get().autoLaunchGenshinEnabled,
                genshinInForeground = isGenshinInForeground(),
                alreadyAttempted = attempted,
                allowed = canAutoLaunch(),
            )
        ) {
            return
        }
        attempted = true
        launcher.launch()
    }

    /**
     * 设置变更即时生效：开关打开且满足条件时立即尝试一次。
     * 仅在监控已启动（root 就绪）后响应；未就绪时由 [start] 统一处理。
     */
    fun tryLaunchNow() {
        if (!started) return
        if (!GenshinPackages.shouldAttemptAutoLaunch(
                enabled = settingsRepository.get().autoLaunchGenshinEnabled,
                genshinInForeground = isGenshinInForeground(),
                alreadyAttempted = attempted,
                allowed = canAutoLaunch(),
            )
        ) {
            return
        }
        attempted = true
        launcher.launch()
    }

    fun stop() {
        started = false
    }
}
