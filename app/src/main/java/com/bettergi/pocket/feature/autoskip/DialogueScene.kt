package com.bettergi.pocket.feature.autoskip

import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.area.Region

/** 对话图标左侧状态文字关键词（图标淡化时 OCR 兜底用）。 */
val TALK_HISTORY_LABEL_KEYWORDS = listOf("自动", "播放中")

/**
 * OCR 文本是否命中状态文字：按空白/标点拆词后，**必须恰好只有一个词**，
 * 且该词精确等于「自动」或「播放中」。
 * 组合（"自动 播放中"）或含其他词（"自动点击"）都不算对话，避免误判。
 */
fun isTalkHistoryLabelText(text: String): Boolean {
    val tokens = text
        .split(Regex("[\\s，。、：；！？,.:;!?（）()]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    return tokens.size == 1 && tokens[0] in TALK_HISTORY_LABEL_KEYWORDS
}

/** 仅模板匹配对话图标，不跑 OCR；threshold 可用高级参数覆盖。 */
fun isTalkHistoryIcon(
    content: CaptureContent,
    assets: RecognitionAssets,
    threshold: Double = 0.75,
): Boolean {
    val talkHistory = assets.get(AutoSkipFeature.TASK_NAME, "TalkHistory", content.captureRectArea)
    talkHistory.threshold = threshold
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