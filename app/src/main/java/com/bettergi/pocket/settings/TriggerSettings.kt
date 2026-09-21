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
)
