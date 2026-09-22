package com.bettergi.pocket.feature.autoskip

import android.util.Log
import com.bettergi.pocket.input.ActionEmitter
import com.bettergi.pocket.input.ClickAction
import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.RecognitionObject
import com.bettergi.pocket.recognition.area.Region
import com.bettergi.pocket.recognition.opencv.MatOps
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.trigger.FeatureTick
import com.bettergi.pocket.trigger.TriggerFeature
import com.bettergi.pocket.trigger.screenBottomCenter
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * 自动对话（移植 PC 版 AutoSkip 核心，去掉语音/弹窗/邀约）：
 * 感叹号优先、选项 OCR + 关键词决策、状态机 + 点击确认、黑屏转场点击。
 */
class AutoSkipFeature(
    private val assets: RecognitionAssets,
    private val events: AutoSkipEvents? = null,
    private val optionKeywords: OptionKeywords = OptionKeywords(),
) : TriggerFeature {

    override val key: String = "AutoSkip"

    private enum class State { IDLE, IN_DIALOG, CONFIRMING }

    private data class Decision(val region: Region)

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var clickedOptionY: Int = -1

    @Volatile
    private var clickedAtMs: Long = 0L

    @Volatile
    private var lastOptionDecisionAtMs: Long = 0L

    @Volatile
    private var lastBlackClickMs: Long = 0L

    @Volatile
    private var lastIdleLogMs: Long = 0L

    @Volatile
    private var lastSkipLogMs: Long = 0L

    @Volatile
    private var lastSkipClickMs: Long = 0L

    @Volatile
    private var lastLabelOcrAtMs: Long = 0L

    @Volatile
    private var lastLabelOcrHit: Boolean = false

    override fun isEnabled(settings: TriggerSettings): Boolean =
        settings.screenShareEnabled && settings.autoSkipEnabled

    override fun onTick(tick: FeatureTick, settings: TriggerSettings, actions: ActionEmitter) {
        val content = tick.content ?: return

        when (state) {
            State.IDLE -> {
                if (inDialogue(content)) {
                    events?.onTalkHistoryMatched()
                    state = State.IN_DIALOG
                    return
                }
                if (settings.blackScreenClickEnabled) clickBlackScreenIfNeeded(content, actions)
                val idleNow = System.currentTimeMillis()
                if (idleNow - lastIdleLogMs >= IDLE_LOG_INTERVAL_MS) {
                    lastIdleLogMs = idleNow
                    events?.onIdleScan()
                }
            }

            State.IN_DIALOG -> {
                if (!inDialogue(content)) {
                    state = State.IDLE
                    return
                }
                events?.onTalkHistoryMatched()

                if (settings.quickSkipDialogueEnabled) {
                    val (skipX, skipY) = if (settings.quickSkipCustomPosition) {
                        (tick.screenWidth * settings.quickSkipPositionX).toInt().coerceIn(0, tick.screenWidth) to
                            (tick.screenHeight * settings.quickSkipPositionY).toInt().coerceIn(0, tick.screenHeight)
                    } else {
                        screenBottomCenter(tick.screenWidth, tick.screenHeight)
                    }
                    val skipNow = System.currentTimeMillis()
                    // 点击节流：避免每帧狂点干扰手动操作（500ms 一次足够逐行跳过对话）
                    if (skipNow - lastSkipClickMs >= SKIP_CLICK_INTERVAL_MS) {
                        lastSkipClickMs = skipNow
                        if (skipNow - lastSkipLogMs >= SKIP_LOG_INTERVAL_MS) {
                            lastSkipLogMs = skipNow
                            events?.onAutoSkipLog("点击跳过 ($skipX, $skipY)")
                        }
                        actions.emit(ClickAction(skipX, skipY))
                    }
                }

                val now = System.currentTimeMillis()
                if (now < clickedAtMs + CONFIRM_WINDOW_MS && clickedOptionY >= 0) return

                // 1) 感叹号选项：优先级最高，命中直接点
                val excls = content.findMulti(
                    assets.get(TASK_NAME, "ExclamationIcon", content.captureRectArea),
                )
                if (settings.smartOptionEnabled && settings.exclamationClickEnabled && excls.isNotEmpty()) {
                    val (x, y) = excls[0].centerOnNativeCapture()
                    Log.i(TAG, "click exclamation option at $x,$y")
                    events?.onAutoSkipLog("点击感叹号选项 ($x, $y)")
                    actions.emit(ClickAction(x, y))
                    events?.onChatIconClicked(x, y)
                    clickedOptionY = excls[0].y
                    clickedAtMs = now
                    state = State.CONFIRMING
                    return
                }

                // 2) 普通选项：OCR 读文字 + 关键词/橙色决策（节流 1s）
                if (now - lastOptionDecisionAtMs < OPTION_DECISION_INTERVAL_MS) return
                lastOptionDecisionAtMs = now

                val chatIcon = assets.get(TASK_NAME, "ChatIcon", content.captureRectArea)
                val hits = content.findMulti(chatIcon)
                val decision = if (settings.smartOptionEnabled) {
                    decideOption(content, hits)
                } else {
                    selectTopChatIcon(hits)?.let { Decision(it) }
                } ?: return
                val target = decision.region
                val (topX, topY) = target.centerOnNativeCapture()
                events?.onChatIconsRecognized(hits.size, topX, topY)

                Log.i(TAG, "click option at $topX,$topY")
                actions.emit(ClickAction(topX, topY))
                events?.onChatIconClicked(topX, topY)
                clickedOptionY = target.y
                clickedAtMs = now
                state = State.CONFIRMING
            }

            State.CONFIRMING -> {
                if (!inDialogue(content)) {
                    events?.onAutoSkipLog("对话已结束（点击生效）")
                    state = State.IDLE
                    return
                }
                val now = System.currentTimeMillis()
                val chatIcon = assets.get(TASK_NAME, "ChatIcon", content.captureRectArea)
                val hits = content.findMulti(chatIcon)
                val top = selectTopChatIcon(hits)

                val changed = top == null || top.y != clickedOptionY || hits.none { it.y == clickedOptionY }
                if (changed) {
                    events?.onAutoSkipLog("选项已变化，点击生效")
                    state = State.IN_DIALOG
                    clickedOptionY = -1
                    return
                }
                if (now - clickedAtMs >= CONFIRM_TIMEOUT_MS) {
                    Log.w(TAG, "option did not change within ${CONFIRM_TIMEOUT_MS}ms, allow retry")
                    events?.onAutoSkipLog("选项未变化，超时重试")
                    state = State.IN_DIALOG
                    clickedAtMs = 0L
                }
            }

        }
    }

    /** 黑屏转场检测：非对话时画面中部 1/3 区域接近全黑则点击推进（移植 PC 版）。 */
    private fun clickBlackScreenIfNeeded(content: CaptureContent, actions: ActionEmitter) {
        val now = System.currentTimeMillis()
        if (now - lastBlackClickMs < BLACK_CLICK_INTERVAL_MS) return
        val region = content.captureRectArea
        val grey = region.cacheGreyMatSafe ?: return
        val w = grey.cols()
        val h = grey.rows()
        if (w <= 0 || h < 30) return
        val top = h / 3
        val roi = MatOps.roiView(grey, IntRect(0, top, w, h - top * 2))
        val mask = Mat()
        try {
            Core.inRange(roi, Scalar(0.0), Scalar(0.0), mask)
            val black = Core.countNonZero(mask).toDouble()
            val rate = black / (roi.cols() * roi.rows())
            if (rate >= BLACK_RATE_MIN && rate < BLACK_RATE_MAX) {
                Log.i(TAG, "black transition detected, rate=$rate, click center")
                events?.onBlackScreenClicked(w / 2, h / 2)
                actions.emit(ClickAction(w / 2, h / 2))
                lastBlackClickMs = now
            }
        } finally {
            roi.release()
            mask.release()
        }
    }

    /** 对话判定沿用原版：仅以对话历史图标（TalkHistory 模板）命中为准。 */
    private fun inDialogue(content: CaptureContent): Boolean {
        if (isTalkHistoryIcon(content, assets)) return true

        // 兜底：图标淡化时 OCR 左侧状态文字，1 秒节流；节流窗口内返回上次结果防状态机抖动
        val now = System.currentTimeMillis()
        if (now - lastLabelOcrAtMs < TALK_HISTORY_LABEL_OCR_INTERVAL_MS) return lastLabelOcrHit
        lastLabelOcrAtMs = now
        val hit = findTalkHistoryLabel(content, assets)
        lastLabelOcrHit = hit != null
        if (hit != null) events?.onAutoSkipLog("对话图标未命中，左侧文字命中：${hit.text}")
        return lastLabelOcrHit
    }

    /**
     * 关键词决策（对齐 PC 版 ChatOptionChoose）：
     * 以最下方气泡为基准做固定宽度 OCR；按 Y 排序后过滤空文本、短英文数字、
     * 以及与下一行 Y 间距过大的行；再按 select > pause > default_pause > 兜底最低项决策。
     */
    private fun decideOption(
        content: CaptureContent,
        hits: List<Region>,
    ): Decision? {
        if (hits.isEmpty()) return null
        val region = content.captureRectArea
        val lowest = hits.maxByOrNull { it.y } ?: return null

        val scale = region.width / 1920.0
        val ocrLeft = (lowest.x + lowest.width + 8 * scale).toInt().coerceAtMost(region.width - 1)
        val ocrWidth = (535 * scale).toInt().coerceAtLeast(80)
        val ocrTop = (region.height / 12).coerceAtLeast(0)
        val ocrBottom = (lowest.y + lowest.height + 30 * scale).toInt().coerceAtMost(region.height)
        val ocrHeight = ocrBottom - ocrTop
        if (ocrLeft < 0 || ocrWidth <= 0 || ocrHeight <= 0) return Decision(lowest)

        val ro = RecognitionObject.ocrThis()
        ro.regionOfInterest = IntRect(ocrLeft, ocrTop, ocrWidth, ocrHeight)
        val lines = region.findMulti(ro)

        // 先按 Y 坐标排序，再按 PC 的顺序逐项过滤（含相邻行间距检查）
        val sorted = lines.sortedBy { it.y }
        val maxYGap = (OPTION_MAX_Y_GAP * scale).toInt()
        val rs = sorted.filterIndexed { index, line ->
            val t = line.text ?: return@filterIndexed false
            if (t.isBlank()) return@filterIndexed false
            if (t.length < 5 && t.all { it in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ." }) {
                return@filterIndexed false
            }
            // 对齐 PC：与下一行 Y 间距过大时，当前行不属于同一组选项，直接丢弃
            if (index != sorted.size - 1) {
                val gap = sorted[index + 1].y - line.y
                if (gap > maxYGap) {
                    events?.onAutoSkipLog("Y 轴偏差过大，忽略：$t")
                    return@filterIndexed false
                }
            }
            true
        }
        if (rs.isEmpty()) return Decision(lowest)

        events?.onOptionTextsRecognized(rs.mapNotNull { it.text })

        // 对齐 PC 版优先级：同一遍历内 select 先于 pause；命中 pause 本拍不点击（交用户手动选）
        for (item in rs) {
            val t = item.text ?: continue
            if (optionKeywords.select.any { t.contains(it) }) {
                events?.onAutoSkipLog("主动选择命中：$t")
                return Decision(item)
            }
            if (optionKeywords.pause.any { t.contains(it) }) {
                events?.onAutoSkipLog("命中暂停词，等待手动选择：$t")
                return null
            }
        }
        for (item in rs) {
            val t = item.text ?: continue
            if (optionKeywords.defaultPause.any { t.contains(it) }) {
                events?.onAutoSkipLog("命中默认暂停词，等待手动选择：$t")
                return null
            }
        }
        events?.onAutoSkipLog("无关键词命中，点最低选项")
        return Decision(rs.last())
    }

    companion object {
        const val TASK_NAME = "AutoSkip"
        private const val TAG = "BetterGI.AutoSkip"
        private const val CONFIRM_WINDOW_MS = 600L
        private const val CONFIRM_TIMEOUT_MS = 1200L
        private const val OPTION_DECISION_INTERVAL_MS = 1000L
        private const val TALK_HISTORY_LABEL_OCR_INTERVAL_MS = 1000L

        /** 相邻选项行 Y 间距上限（1080p 基准，对齐 PC 的 150）。 */
        private const val OPTION_MAX_Y_GAP = 150
        private const val BLACK_CLICK_INTERVAL_MS = 1200L
        private const val BLACK_RATE_MIN = 0.5
        private const val BLACK_RATE_MAX = 0.98999
        private const val IDLE_LOG_INTERVAL_MS = 5000L
        private const val SKIP_LOG_INTERVAL_MS = 5000L
        private const val SKIP_CLICK_INTERVAL_MS = 500L

        fun selectTopChatIcon(hits: List<Region>): Region? = hits.minByOrNull { it.y }
    }
}
