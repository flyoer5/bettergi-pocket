package com.bettergi.pocket.feature.autopick

import com.bettergi.pocket.input.ActionEmitter
import com.bettergi.pocket.input.ClickAction
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.trigger.FeatureTick
import com.bettergi.pocket.trigger.TriggerFeature
import com.bettergi.pocket.trigger.screenBottomCenter

class AutoPickFeature : TriggerFeature {
    override val key: String = "AutoPick"

    @Volatile
    private var nextClickAtMs: Long = 0L

    override fun isEnabled(settings: TriggerSettings): Boolean =
        AVAILABLE && settings.screenShareEnabled && settings.autoPickEnabled

    override fun needsFrame(settings: TriggerSettings): Boolean = false

    override fun onTick(tick: FeatureTick, settings: TriggerSettings, actions: ActionEmitter) {
        val now = System.currentTimeMillis()
        if (now < nextClickAtMs) return
        val (x, y) = screenBottomCenter(tick.screenWidth, tick.screenHeight)
        actions.emit(ClickAction(x, y, settings.clickDurationMs))
        nextClickAtMs = now + CLICK_INTERVAL_MS
    }

    companion object {
        const val CLICK_INTERVAL_MS = 800L

        /** Temporarily disabled — set true to restore auto-pick UI and behavior. */
        const val AVAILABLE = false
    }
}
