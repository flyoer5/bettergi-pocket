package com.bettergi.pocket.rust

import com.bettergi.pocket.feature.autoskip.OptionKeywords
import com.bettergi.pocket.settings.TriggerSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动对话状态机的 Rust 封装（对应 `rust/crates/bg-jni` 的引擎接口）。
 *
 * Kotlin 只负责「识别」（模板匹配、OCR、黑屏比例），决策全部交给 Rust：
 * 每拍把观测打包成 JSON 送过去，拿回动作与日志。
 *
 * `.so` 不可用（非 arm64 设备 / 旧安装包）时 [create] 返回 null，调用方回退到纯 Kotlin 实现。
 */
class RustAutoSkipEngine private constructor(private var handle: Long) {

    companion object {
        fun create(settings: TriggerSettings): RustAutoSkipEngine? {
            if (!RustNative.isAvailable) return null
            val handle = try {
                RustNative.createEngine(settings.toRustJson())
            } catch (error: Throwable) {
                return null
            }
            if (handle == 0L) return null
            return RustAutoSkipEngine(handle)
        }
    }

    val isAlive: Boolean get() = handle != 0L

    fun updateSettings(settings: TriggerSettings) {
        if (handle == 0L) return
        RustNative.updateSettings(handle, settings.toRustJson())
    }

    fun updateKeywords(keywords: OptionKeywords) {
        if (handle == 0L) return
        RustNative.updateKeywords(handle, keywords.toRustJson())
    }

    fun reset() {
        if (handle != 0L) RustNative.resetEngine(handle)
    }

    /** 推进一拍；返回 `{"actions":[...],"events":[...],"state":"...","nextTickMs":200}`。 */
    fun tick(observationJson: String): String? {
        if (handle == 0L) return null
        return RustNative.tick(handle, observationJson)
    }

    fun close() {
        if (handle != 0L) {
            RustNative.destroyEngine(handle)
            handle = 0L
        }
    }
}

/** 与 Rust 侧 `SettingsPayload` 的 camelCase 字段一一对应。 */
fun TriggerSettings.toRustJson(): String = JSONObject().apply {
    put("screenShareEnabled", screenShareEnabled)
    put("autoSkipEnabled", autoSkipEnabled)
    put("quickSkipDialogueEnabled", quickSkipDialogueEnabled)
    put("autoLaunchGenshinEnabled", autoLaunchGenshinEnabled)
    put("smartOptionEnabled", smartOptionEnabled)
    put("blackScreenClickEnabled", blackScreenClickEnabled)
    put("showTapIndicator", showTapIndicator)
    put("exclamationClickEnabled", exclamationClickEnabled)
    put("quickSkipCustomPosition", quickSkipCustomPosition)
    put("quickSkipPositionX", quickSkipPositionX.toDouble())
    put("quickSkipPositionY", quickSkipPositionY.toDouble())
    put("blackScreenCustomPosition", blackScreenCustomPosition)
    put("blackScreenPositionX", blackScreenPositionX.toDouble())
    put("blackScreenPositionY", blackScreenPositionY.toDouble())
    put("skipClickIntervalMs", skipClickIntervalMs)
    put("blackClickIntervalMs", blackClickIntervalMs)
}.toString()

/** 与 Rust 侧 `KeywordsPayload` 对应：`defaultPause` 是驼峰。 */
fun OptionKeywords.toRustJson(): String = JSONObject().apply {
    put("select", JSONArray(select))
    put("pause", JSONArray(pause))
    put("defaultPause", JSONArray(defaultPause))
}.toString()
