package com.bettergi.pocket.trigger

import com.bettergi.pocket.input.ActionEmitter
import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.settings.TriggerSettings

/**
 * 一拍触发上下文。[content] 仅在有功能声明 [TriggerFeature.needsFrame] 时才会构建。
 */
class FeatureTick(
    val screenWidth: Int,
    val screenHeight: Int,
    val content: CaptureContent?,
)

interface TriggerFeature {
    val key: String

    fun isEnabled(settings: TriggerSettings): Boolean

    /**
     * 本拍是否需要拷贝图像并做 OpenCV。
     * 不需要时引擎只丢弃积压帧，避免全屏 RGBA 拷贝和 Mat 转换。
     */
    fun needsFrame(settings: TriggerSettings): Boolean = isEnabled(settings)

    fun onTick(tick: FeatureTick, settings: TriggerSettings, actions: ActionEmitter)

    /** 引擎释放时调用：功能自行释放持有的 native 资源（如 OpenCV Mat 缓存）。 */
    fun release() {}
}
