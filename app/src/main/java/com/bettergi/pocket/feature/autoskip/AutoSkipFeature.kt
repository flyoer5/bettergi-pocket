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
import kotlin.math.abs
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
    private var clickedOptionX: Int = -1

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

    override fun release() {
        // 服务停止/引擎释放时归还模板 Mat，避免 native 内存滞留到进程结束
        assets.clear()
    }

    override fun onTick(tick: FeatureTick, settings: TriggerSettings, actions: ActionEmitter) {
        val content = tick.content ?: return

        when (state) {
            State.IDLE -> {
                if (inDialogue(content)) {
                    events?.onTalkHistoryMatched()
                    state = State.IN_DIALOG
                    return
                }
                if (settings.blackScreenClickEnabled) clickBlackScreenIfNeeded(content, tick, settings, actions)
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
                    // 固定点（原版行为）始终保留；开启自定义后是"叠加"而非"替换",
                    // 同一拍依次点两处，覆盖不同界面下的可点区域。两点过近则合并为一次，
                    // 避免同一位置连点两下被系统当成双击、也避免重复注入。
                    val (fixedX, fixedY) = screenBottomCenter(tick.screenWidth, tick.screenHeight)
                    val points = ArrayList<Pair<Int, Int>>(2)
                    points.add(fixedX to fixedY)
                    if (settings.quickSkipCustomPosition) {
                        val cx = (tick.screenWidth * settings.quickSkipPositionX).toInt()
                            .coerceIn(0, tick.screenWidth)
                        val cy = (tick.screenHeight * settings.quickSkipPositionY).toInt()
                            .coerceIn(0, tick.screenHeight)
                        val dx = cx - fixedX
                        val dy = cy - fixedY
                        if (dx * dx + dy * dy >= QUICK_SKIP_MIN_SEPARATION_PX * QUICK_SKIP_MIN_SEPARATION_PX) {
                            points.add(cx to cy)
                        }
                    }
                    val skipNow = System.currentTimeMillis()
                    // 点击节流：避免每帧狂点干扰手动操作（500ms 一次足够逐行跳过对话）
                    if (skipNow - lastSkipClickMs >= settings.skipClickIntervalMs) {
                        lastSkipClickMs = skipNow
                        if (skipNow - lastSkipLogMs >= SKIP_LOG_INTERVAL_MS) {
                            lastSkipLogMs = skipNow
                            events?.onAutoSkipLog(
                                "点击跳过 " + points.joinToString(" / ") { "(${it.first}, ${it.second})" },
                            )
                        }
                        points.forEach { (x, y) -> actions.emit(ClickAction(x, y)) }
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
                    clickedOptionX = excls[0].x
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
                // 确认基准必须与 CONFIRMING 的比较对象同源：
                // 决策结果可能是 OCR 文本行（智能选项路径），而确认阶段比较的是 ChatIcon 命中，
                // 两者几何不同（文本在图标右侧）。取与目标同排最近的 ChatIcon 作为锚点。
                val anchor = hits.minByOrNull { abs(it.y - target.y) }
                events?.onChatIconsRecognized(hits.size, topX, topY)

                Log.i(TAG, "click option at $topX,$topY")
                actions.emit(ClickAction(topX, topY))
                events?.onChatIconClicked(topX, topY)
                clickedOptionX = anchor?.x ?: target.x
                clickedOptionY = anchor?.y ?: target.y
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

                // 判定"选项是否还在原处"：容差比较（同模板同空间，2~3px 抖动正常，给 32px 余量）
                val stillThere = top != null && hits.any {
                    abs(it.x - clickedOptionX) <= OPTION_ANCHOR_TOLERANCE_PX &&
                        abs(it.y - clickedOptionY) <= OPTION_ANCHOR_TOLERANCE_PX
                }
                val changed = !stillThere
                if (changed) {
                    events?.onAutoSkipLog("选项已变化，点击生效")
                    state = State.IN_DIALOG
                    clickedOptionX = -1
                    clickedOptionY = -1
                    return
                }
                if (now - clickedAtMs >= CONFIRM_TIMEOUT_MS) {
                    Log.w(TAG, "option did not change within ${CONFIRM_TIMEOUT_MS}ms, allow retry")
                    events?.onAutoSkipLog("选项未变化，超时重试")
                    state = State.IN_DIALOG
                    clickedAtMs = 0L
                    clickedOptionX = -1
                    clickedOptionY = -1
                }
            }

        }
    }

    /** 黑屏转场检测：非对话时画面中部 1/3 区域接近全黑则点击推进（移植 PC 版）。 */
    private fun clickBlackScreenIfNeeded(
        content: CaptureContent,
        tick: FeatureTick,
        settings: TriggerSettings,
        actions: ActionEmitter,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastBlackClickMs < settings.blackClickIntervalMs) return
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
                // 坐标系必须换算回原生捕获（w/h 是 ≤1920 的缩放图尺寸，直接当屏幕坐标会点偏）
                val (cx, cy) = content.captureRectArea.centerOnNativeCapture()
                // 与对话跳过同规则：中央固定点始终保留，开启附加点后叠加第二处；
                // 黑屏转场时画面无 UI，多点覆盖能减少"点空"导致的卡转场
                val points = ArrayList<Pair<Int, Int>>(2)
                points.add(cx to cy)
                if (settings.quickSkipCustomPosition) {
                    val ax = (tick.screenWidth * settings.quickSkipPositionX).toInt()
                        .coerceIn(0, tick.screenWidth)
                    val ay = (tick.screenHeight * settings.quickSkipPositionY).toInt()
                        .coerceIn(0, tick.screenHeight)
                    val dx = ax - cx
                    val dy = ay - cy
                    if (dx * dx + dy * dy >= QUICK_SKIP_MIN_SEPARATION_PX * QUICK_SKIP_MIN_SEPARATION_PX) {
                        points.add(ax to ay)
                    }
                }
                Log.i(TAG, "black transition detected, rate=$rate, click ${points.joinToString("/")}")
                events?.onBlackScreenClicked(cx, cy)
                points.forEach { (x, y) -> actions.emit(ClickAction(x, y)) }
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

        /** 确认阶段锚点匹配容差（1080p 基准像素） */
        private const val OPTION_ANCHOR_TOLERANCE_PX = 32

        /** 固定点与自定义点的最小间距；小于此值视为同一位置，只点一次 */
        private const val QUICK_SKIP_MIN_SEPARATION_PX = 32
        private const val BLACK_RATE_MIN = 0.5
        private const val BLACK_RATE_MAX = 0.98999
        private const val IDLE_LOG_INTERVAL_MS = 5000L
        private const val SKIP_LOG_INTERVAL_MS = 5000L

        fun selectTopChatIcon(hits: List<Region>): Region? = hits.minByOrNull { it.y }
    }
}
