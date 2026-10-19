package com.bettergi.pocket.settings

data class TriggerSettings(
    val screenShareEnabled: Boolean,
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
    /** 黑屏转场的附加点（固定点=画面中央，始终生效） */
    val blackScreenCustomPosition: Boolean = false,
    val blackScreenPositionX: Float = 0.5f,
    val blackScreenPositionY: Float = 0.5f,
    /** 对话推进点击间隔（ms）；越小越快，过小会与动画抢跑 */
    val skipClickIntervalMs: Int = 500,
    /** 黑屏转场点击间隔（ms） */
    val blackClickIntervalMs: Int = 1200,
)
