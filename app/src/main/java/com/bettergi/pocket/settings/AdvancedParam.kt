package com.bettergi.pocket.settings

/**
 * 高级参数定义：把原先写死在代码里的阈值/间隔抽成可配置项。
 * 每个参数自带分组、单位、范围与默认值（默认值 = 原硬编码值），
 * [read]/[apply] 负责与 [TriggerSettings] 双向映射。
 */
enum class AdvancedParam(
    val key: String,
    val group: String,
    val title: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val isInt: Boolean,
    val default: Double,
) {
    // ---- 识别 ----
    TALK_HISTORY_THRESHOLD("adv_talk_threshold", "识别", "对话图标匹配阈值", "", 0.5, 0.99, false, 0.75),
    EXCLAMATION_THRESHOLD("adv_exclamation_threshold", "识别", "感叹号图标阈值", "", 0.5, 0.99, false, 0.8),
    LABEL_OCR_INTERVAL("adv_label_ocr_interval", "识别", "状态文字 OCR 节流", "ms", 200.0, 5000.0, true, 1000.0),
    OPTION_MAX_Y_GAP("adv_option_max_y_gap", "识别", "选项行间距上限", "px", 50.0, 600.0, true, 150.0),
    BLACK_RATE_MIN("adv_black_rate_min", "识别", "黑屏判定下限", "", 0.1, 0.99, false, 0.5),
    BLACK_RATE_MAX("adv_black_rate_max", "识别", "黑屏判定上限", "", 0.1, 0.999, false, 0.99),

    // ---- 节奏 ----
    SKIP_CLICK_INTERVAL("adv_skip_click_interval", "节奏", "跳过点击间隔", "ms", 100.0, 5000.0, true, 500.0),
    OPTION_DECISION_INTERVAL("adv_option_decision_interval", "节奏", "选项决策间隔", "ms", 200.0, 5000.0, true, 1000.0),
    CONFIRM_WINDOW("adv_confirm_window", "节奏", "选项确认窗口", "ms", 100.0, 5000.0, true, 600.0),
    CONFIRM_TIMEOUT("adv_confirm_timeout", "节奏", "选项重试超时", "ms", 300.0, 8000.0, true, 1200.0),
    BLACK_CLICK_INTERVAL("adv_black_click_interval", "节奏", "黑屏点击间隔", "ms", 300.0, 8000.0, true, 1200.0),
    FAST_TICK("adv_fast_tick", "节奏", "识别快帧率", "ms", 50.0, 1000.0, true, 200.0),
    SLOW_TICK("adv_slow_tick", "节奏", "识别慢帧率", "ms", 100.0, 3000.0, true, 600.0),

    // ---- 连接（root）----
    CONNECT_TIMEOUT("adv_connect_timeout", "连接", "连接超时", "ms", 1000.0, 15000.0, true, 4000.0),
    RECONNECT_DELAY("adv_reconnect_delay", "连接", "重连等待", "ms", 500.0, 15000.0, true, 2000.0),
    MAX_RECONNECT("adv_max_reconnect", "连接", "最大重连次数", "次", 0.0, 20.0, true, 5.0),

    // ---- 日志 ----
    IDLE_LOG_INTERVAL("adv_idle_log_interval", "日志", "空闲日志间隔", "ms", 1000.0, 30000.0, true, 5000.0),
    SKIP_LOG_INTERVAL("adv_skip_log_interval", "日志", "跳过日志间隔", "ms", 1000.0, 30000.0, true, 5000.0),
    ;

    fun read(settings: TriggerSettings): Double = when (this) {
        TALK_HISTORY_THRESHOLD -> settings.talkHistoryThreshold
        EXCLAMATION_THRESHOLD -> settings.exclamationThreshold
        LABEL_OCR_INTERVAL -> settings.labelOcrIntervalMs.toDouble()
        OPTION_MAX_Y_GAP -> settings.optionMaxYGap.toDouble()
        BLACK_RATE_MIN -> settings.blackRateMin
        BLACK_RATE_MAX -> settings.blackRateMax
        SKIP_CLICK_INTERVAL -> settings.skipClickIntervalMs.toDouble()
        OPTION_DECISION_INTERVAL -> settings.optionDecisionIntervalMs.toDouble()
        CONFIRM_WINDOW -> settings.confirmWindowMs.toDouble()
        CONFIRM_TIMEOUT -> settings.confirmTimeoutMs.toDouble()
        BLACK_CLICK_INTERVAL -> settings.blackClickIntervalMs.toDouble()
        FAST_TICK -> settings.fastTickMs.toDouble()
        SLOW_TICK -> settings.slowTickMs.toDouble()
        CONNECT_TIMEOUT -> settings.connectTimeoutMs.toDouble()
        RECONNECT_DELAY -> settings.reconnectDelayMs.toDouble()
        MAX_RECONNECT -> settings.maxReconnectAttempts.toDouble()
        IDLE_LOG_INTERVAL -> settings.idleLogIntervalMs.toDouble()
        SKIP_LOG_INTERVAL -> settings.skipLogIntervalMs.toDouble()
    }

    fun apply(settings: TriggerSettings, value: Double): TriggerSettings {
        val v = value.coerceIn(min, max)
        return when (this) {
            TALK_HISTORY_THRESHOLD -> settings.copy(talkHistoryThreshold = v)
            EXCLAMATION_THRESHOLD -> settings.copy(exclamationThreshold = v)
            LABEL_OCR_INTERVAL -> settings.copy(labelOcrIntervalMs = v.toLong())
            OPTION_MAX_Y_GAP -> settings.copy(optionMaxYGap = v.toInt())
            BLACK_RATE_MIN -> settings.copy(blackRateMin = v)
            BLACK_RATE_MAX -> settings.copy(blackRateMax = v)
            SKIP_CLICK_INTERVAL -> settings.copy(skipClickIntervalMs = v.toLong())
            OPTION_DECISION_INTERVAL -> settings.copy(optionDecisionIntervalMs = v.toLong())
            CONFIRM_WINDOW -> settings.copy(confirmWindowMs = v.toLong())
            CONFIRM_TIMEOUT -> settings.copy(confirmTimeoutMs = v.toLong())
            BLACK_CLICK_INTERVAL -> settings.copy(blackClickIntervalMs = v.toLong())
            FAST_TICK -> settings.copy(fastTickMs = v.toLong())
            SLOW_TICK -> settings.copy(slowTickMs = v.toLong())
            CONNECT_TIMEOUT -> settings.copy(connectTimeoutMs = v.toLong())
            RECONNECT_DELAY -> settings.copy(reconnectDelayMs = v.toLong())
            MAX_RECONNECT -> settings.copy(maxReconnectAttempts = v.toInt())
            IDLE_LOG_INTERVAL -> settings.copy(idleLogIntervalMs = v.toLong())
            SKIP_LOG_INTERVAL -> settings.copy(skipLogIntervalMs = v.toLong())
        }
    }

    /** 显示值：整数参数去掉小数尾巴 */
    fun format(value: Double): String =
        if (isInt) value.toLong().toString() else String.format("%.2f", value)

    companion object {
        val GROUPS: List<String> = entries.map { it.group }.distinct()

        fun ofGroup(group: String): List<AdvancedParam> = entries.filter { it.group == group }
    }
}
