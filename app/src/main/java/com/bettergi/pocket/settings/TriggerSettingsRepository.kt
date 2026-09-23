package com.bettergi.pocket.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

class TriggerSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(TriggerSettings) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var current: TriggerSettings = readFromPrefs()

    /** 监听代次：removeListener 时递增，已排队的旧回调据此跳过，避免过期回调 */
    @Volatile
    private var generation = 0L

    /**
     * 跨实例同步：多个模块（App 页 / 前台服务 / 悬浮球）会各自创建仓库实例，
     * 任何实例写入 prefs 时，其他实例据此刷新内存副本并通知自己的监听器，
     * 保证改设置后运行中的引擎/UI 立即拿到最新值。
     */
    private val prefsChangeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val fresh: TriggerSettings
            synchronized(lock) {
                fresh = readFromPrefs()
                if (fresh == current) return@OnSharedPreferenceChangeListener
                current = fresh
            }
            notifyListeners(fresh)
        }

    init {
        // 注意：SharedPreferences 对监听器持弱引用，必须由本实例强引用持有
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun get(): TriggerSettings = current

    fun addListener(listener: (TriggerSettings) -> Unit) {
        listeners.add(listener)
        val gen = generation
        mainHandler.post {
            if (gen == generation && listeners.contains(listener)) {
                listener(current)
            }
        }
    }

    fun removeListener(listener: (TriggerSettings) -> Unit) {
        listeners.remove(listener)
        generation++
    }

    fun setScreenShareEnabled(enabled: Boolean) {
        update { it.copy(screenShareEnabled = enabled) }
    }

    fun setAutoPickEnabled(enabled: Boolean) {
        update { it.copy(autoPickEnabled = enabled) }
    }

    fun setAutoSkipEnabled(enabled: Boolean) {
        update { it.copy(autoSkipEnabled = enabled) }
    }

    fun setQuickSkipDialogueEnabled(enabled: Boolean) {
        update { it.copy(quickSkipDialogueEnabled = enabled) }
    }

    fun setAutoLaunchGenshinEnabled(enabled: Boolean) {
        update { it.copy(autoLaunchGenshinEnabled = enabled) }
    }

    fun setSmartOptionEnabled(enabled: Boolean) {
        update { it.copy(smartOptionEnabled = enabled) }
    }

    fun setBlackScreenClickEnabled(enabled: Boolean) {
        update { it.copy(blackScreenClickEnabled = enabled) }
    }

    fun setShowTapIndicator(enabled: Boolean) {
        update { it.copy(showTapIndicator = enabled) }
    }

    fun setExclamationClickEnabled(enabled: Boolean) {
        update { it.copy(exclamationClickEnabled = enabled) }
    }

    fun setQuickSkipPosition(x: Float, y: Float) {
        update {
            it.copy(
                quickSkipCustomPosition = true,
                quickSkipPositionX = x.coerceIn(0.05f, 0.95f),
                quickSkipPositionY = y.coerceIn(0.05f, 0.95f),
            )
        }
    }

    fun resetQuickSkipPosition() {
        update { it.copy(quickSkipCustomPosition = false) }
    }

    /** 设置单个高级参数（自动按范围 clamp）。 */
    fun setAdvanced(param: AdvancedParam, value: Double) {
        update { param.apply(it, value) }
    }

    /** 全部高级参数恢复默认值。 */
    fun resetAdvanced() {
        update { settings ->
            AdvancedParam.entries.fold(settings) { acc, p -> p.apply(acc, p.default) }
        }
    }

    private fun update(transform: (TriggerSettings) -> TriggerSettings) {
        val newValue: TriggerSettings
        synchronized(lock) {
            val old = current
            val updated = transform(old)
            if (updated == old) return
            current = updated
            newValue = updated
            prefs.edit().apply {
                putBoolean(KEY_SCREEN_SHARE, updated.screenShareEnabled)
                putBoolean(KEY_AUTO_PICK, updated.autoPickEnabled)
                putBoolean(KEY_AUTO_SKIP, updated.autoSkipEnabled)
                putBoolean(KEY_QUICK_SKIP, updated.quickSkipDialogueEnabled)
                putBoolean(KEY_AUTO_LAUNCH_GENSHIN, updated.autoLaunchGenshinEnabled)
                putBoolean(KEY_SMART_OPTION, updated.smartOptionEnabled)
                putBoolean(KEY_BLACK_SCREEN, updated.blackScreenClickEnabled)
                putBoolean(KEY_TAP_INDICATOR, updated.showTapIndicator)
                putBoolean(KEY_EXCLAMATION, updated.exclamationClickEnabled)
                putBoolean(KEY_QUICK_SKIP_CUSTOM, updated.quickSkipCustomPosition)
                putFloat(KEY_QUICK_SKIP_X, updated.quickSkipPositionX)
                putFloat(KEY_QUICK_SKIP_Y, updated.quickSkipPositionY)
                AdvancedParam.entries.forEach { p ->
                    putFloat(p.key, p.read(updated).toFloat())
                }
            }.apply()
        }
        notifyListeners(newValue)
    }

    private fun notifyListeners(value: TriggerSettings) {
        val gen = generation
        listeners.forEach { listener ->
            mainHandler.post {
                if (gen == generation && listeners.contains(listener)) {
                    listener(value)
                }
            }
        }
    }

    private fun readFromPrefs(): TriggerSettings {
        var settings = TriggerSettings(
            screenShareEnabled = false,
            autoPickEnabled = prefs.getBoolean(KEY_AUTO_PICK, false),
            autoSkipEnabled = prefs.getBoolean(KEY_AUTO_SKIP, false),
            quickSkipDialogueEnabled = prefs.getBoolean(KEY_QUICK_SKIP, true),
            autoLaunchGenshinEnabled = prefs.getBoolean(KEY_AUTO_LAUNCH_GENSHIN, false),
            smartOptionEnabled = prefs.getBoolean(KEY_SMART_OPTION, true),
            blackScreenClickEnabled = prefs.getBoolean(KEY_BLACK_SCREEN, true),
            showTapIndicator = prefs.getBoolean(KEY_TAP_INDICATOR, false),
            exclamationClickEnabled = prefs.getBoolean(KEY_EXCLAMATION, true),
            quickSkipCustomPosition = prefs.getBoolean(KEY_QUICK_SKIP_CUSTOM, false),
            quickSkipPositionX = prefs.getFloat(KEY_QUICK_SKIP_X, 0.5f),
            quickSkipPositionY = prefs.getFloat(KEY_QUICK_SKIP_Y, 0.99f),
        )
        // 高级参数：prefs 无记录时用默认值，有记录时覆盖
        AdvancedParam.entries.forEach { p ->
            if (prefs.contains(p.key)) {
                val v = prefs.getFloat(p.key, p.default.toFloat()).toDouble()
                if (abs(v - p.read(settings)) > 0.000001) {
                    settings = p.apply(settings, v)
                }
            }
        }
        return settings
    }

    private companion object {
        const val PREFS_NAME = "trigger_settings"
        const val KEY_SCREEN_SHARE = "screenShareEnabled"
        const val KEY_AUTO_PICK = "autoPickEnabled"
        const val KEY_AUTO_SKIP = "autoSkipEnabled"
        const val KEY_QUICK_SKIP = "quickSkipDialogueEnabled"
        const val KEY_AUTO_LAUNCH_GENSHIN = "autoLaunchGenshinEnabled"
        const val KEY_SMART_OPTION = "smartOptionEnabled"
        const val KEY_BLACK_SCREEN = "blackScreenClickEnabled"
        const val KEY_TAP_INDICATOR = "showTapIndicator"
        const val KEY_EXCLAMATION = "exclamationClickEnabled"
        const val KEY_QUICK_SKIP_CUSTOM = "quickSkipCustomPosition"
        const val KEY_QUICK_SKIP_X = "quickSkipPositionX"
        const val KEY_QUICK_SKIP_Y = "quickSkipPositionY"
    }
}