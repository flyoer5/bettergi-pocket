package com.bettergi.pocket.settings

data class TriggerSettings(
    val screenShareEnabled: Boolean,
    val autoPickEnabled: Boolean,
    val autoSkipEnabled: Boolean,
    val quickSkipDialogueEnabled: Boolean,
    val autoLaunchGenshinEnabled: Boolean = false,
    val smartOptionEnabled: Boolean = true,
    val blackScreenClickEnabled: Boolean = true,
    val showTapIndicator: Boolean = false,
    val exclamationClickEnabled: Boolean = true,
    val quickSkipCustomPosition: Boolean = false,
    val quickSkipPositionX: Float = 0.5f,
    val quickSkipPositionY: Float = 0.99f,

    // ---- 高级参数（默认值 = 原硬编码值，见 AdvancedParam）----

    // 识别
    val talkHistoryThreshold: Double = 0.75,
    val exclamationThreshold: Double = 0.8,
    val labelOcrIntervalMs: Long = 1000,
    val optionMaxYGap: Int = 150,
    val blackRateMin: Double = 0.5,
    val blackRateMax: Double = 0.99,

    // 节奏
    val skipClickIntervalMs: Long = 500,
    val optionDecisionIntervalMs: Long = 1000,
    val confirmWindowMs: Long = 600,
    val confirmTimeoutMs: Long = 1200,
    val blackClickIntervalMs: Long = 1200,
    val fastTickMs: Long = 200,
    val slowTickMs: Long = 600,

    // 点击
    val clickDurationMs: Long = 50,

    // 连接（root）
    val connectTimeoutMs: Long = 4000,
    val reconnectDelayMs: Long = 2000,
    val maxReconnectAttempts: Int = 5,

    // 日志
    val idleLogIntervalMs: Long = 5000,
    val skipLogIntervalMs: Long = 5000,
)
