package com.bettergi.pocket.feature.autoskip

import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.area.Region

/** 对话图标左侧状态文字关键词（图标淡化时 OCR 兜底用）。 */
val TALK_HISTORY_LABEL_KEYWORDS = listOf("自动", "播放中")

/**
 * OCR 文本是否命中状态文字：按空白/标点拆词后，每个词都必须精确等于
 * 「自动」或「播放中」。出现任何其他词（如"自动点击"）都不算对话，
 * 避免包含式匹配误判。
 */
fun isTalkHistoryLabelText(text: String): Boolean {
    val tokens = text
        .split(Regex("[\\s，。、：；！？,.:;!?"'（）()]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    return tokens.isNotEmpty() && tokens.all { it in TALK_HISTORY_LABEL_KEYWORDS }
}

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
    return if (isTalkHistoryLabelText(text)) hit else null
}
