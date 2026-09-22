package com.bettergi.pocket.feature.autoskip

import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.area.Region

/** 对话图标左侧状态文字关键词（图标淡化时 OCR 兜底用）。 */
val TALK_HISTORY_LABEL_KEYWORDS = listOf("自动", "播放中")

/** 仅模板匹配对话图标，不跑 OCR。 */
fun isTalkHistoryIcon(content: CaptureContent, assets: RecognitionAssets): Boolean {
    val talkHistory = assets.get(AutoSkipFeature.TASK_NAME, "TalkHistory", content.captureRectArea)
    return content.find(talkHistory).isExist()
}

/** OCR 识别对话图标左侧状态文字；命中关键词才返回 Region，否则 null。 */
fun findTalkHistoryLabel(content: CaptureContent, assets: RecognitionAssets): Region? {
    val label = assets.get(AutoSkipFeature.TASK_NAME, "TalkHistoryLabel", content.captureRectArea)
    val hit = content.find(label)
    if (!hit.isExist()) return null
    val text = hit.text ?: return null
    return if (TALK_HISTORY_LABEL_KEYWORDS.any { text.contains(it) }) hit else null
}
