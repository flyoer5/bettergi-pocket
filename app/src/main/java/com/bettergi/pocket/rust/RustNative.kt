package com.bettergi.pocket.rust

import android.util.Log

/**
 * Rust 引擎的 JNI 入口（对应 `rust/crates/bg-jni`）。
 *
 * 设计约束：
 * - 只暴露「创建 / 释放 / 喂一帧 / 改设置」这几个动作，状态全在 Rust 侧；
 * - 每个方法都可能因为 `.so` 缺失而不可用（非 arm64 设备、旧版本安装包），
 *   所以调用方必须先看 [isAvailable]，不要直接调；
 * - 抛异常只有一个原因：传进去的 JSON 不合法。参数错误应当在调用前就避免。
 */
object RustNative {

    private const val TAG = "BetterGI.Rust"
    private const val LIBRARY = "bg_jni"

    private val loadError: Throwable? = try {
        System.loadLibrary(LIBRARY)
        null
    } catch (error: Throwable) {
        Log.w(TAG, "Rust 引擎不可用（$LIBRARY 未打包）: ${error.message}")
        error
    }

    /** 本机是否加载到了 Rust 引擎。 */
    val isAvailable: Boolean get() = loadError == null

    /** 加载失败的原因，排障时打印用。 */
    fun loadFailure(): Throwable? = loadError

    /** 创建引擎；`settingsJson` 为空则用默认设置。返回 0 表示失败。 */
    external fun createEngine(settingsJson: String): Long

    /** 释放引擎（幂等，传 0 直接返回）。 */
    external fun destroyEngine(handle: Long)

    /** 停止任务时调用，清空状态机。 */
    external fun resetEngine(handle: Long)

    /** 覆盖设置，字段名与 `TriggerSettings` 的 camelCase 一致。 */
    external fun updateSettings(handle: Long, settingsJson: String)

    /** 覆盖关键词表：`{"select":[...],"pause":[...],"defaultPause":[...]}`。 */
    external fun updateKeywords(handle: Long, keywordsJson: String)

    /**
     * 推进一拍。
     *
     * 入参示例：
     * ```json
     * {
     *   "nowMs": 12345,
     *   "screen": {"w": 1920, "h": 1080},
     *   "capture": {"w": 1920, "h": 1080},
     *   "foreground": true,
     *   "dialogue": true,
     *   "chatIcons": [{"x": 100, "y": 500, "w": 20, "h": 20}],
     *   "ocrLines": [{"x": 200, "y": 300, "w": 200, "h": 40, "text": "确定"}],
     *   "blackRate": 0.62
     * }
     * ```
     *
     * 出参示例：
     * ```json
     * {"actions":[{"type":"tap","x":960,"y":1060,"durationMs":50}],
     *  "events":["点击对话图标 (960, 1060)"],"state":"inDialog","nextTickMs":200}
     * ```
     */
    external fun tick(handle: Long, observationJson: String): String?

    /** `.so` 版本，用于在日志里确认加载的是哪一版。 */
    external fun version(): String
}
