package com.bettergi.pocket.feature.autoskip

import android.util.Log
import com.bettergi.pocket.input.ActionEmitter
import com.bettergi.pocket.input.BackAction
import com.bettergi.pocket.input.ClickAction
import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.RecognitionObject
import com.bettergi.pocket.recognition.area.Region
import com.bettergi.pocket.recognition.opencv.MatOps
import com.bettergi.pocket.rust.RustAutoSkipEngine
import com.bettergi.pocket.rust.toRustJson
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.trigger.FeatureTick
import com.bettergi.pocket.trigger.TriggerFeature
import com.bettergi.pocket.trigger.screenBottomCenter
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import kotlin.math.abs

/**
 * 自动对话。
 *
 * 决策（状态机、点击点、节流、关键词判定）由 Rust 侧 `bg-trigger` 负责，Kotlin 只做识别：
 * 对话图标是否命中、哪些位置有图标、选项区 OCR 文本、黑屏比例。
 *
 * `.so` 不可用时会回退到本文件里保留的 Kotlin 状态机实现（[tickLegacy]），行为一致。
 */
class AutoSkipFeature(
    private val assets: RecognitionAssets,
    private val events: AutoSkipEvents? = null,
    private val optionKeywords: OptionKeywords = OptionKeywords(),
) : TriggerFeature {

    override val key: String = "AutoSkip"

    // ---- Rust 决策 ----
    private var rustEngine: RustAutoSkipEngine? = null
    private var rustUnavailable = false
    private var lastRustState = "idle"
    private var lastSettingsJson: String? = null
    /** 选项区采集（OCR + 感叹号）节流：OCR 很贵，只在该做决策的那一拍跑。 */
    private var lastOptionObservationAtMs = 0L
    /** 黑屏比例采集节流。 */
    private var lastBlackObservationAtMs = 0L

    // ---- Kotlin 回退状态机 ----
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
        rustEngine?.close()
        rustEngine = null
        // 服务停止/引擎释放时归还模板 Mat，避免 native 内存滞留到进程结束
        assets.clear()
    }

    override fun onTick(tick: FeatureTick, settings: TriggerSettings, actions: ActionEmitter) {
        val content = tick.content ?: return
        val engine = ensureRustEngine(settings)
        if (engine == null) {
            tickLegacy(tick, content, settings, actions)
            return
        }
        tickWithRust(engine, tick, content, settings, actions)
    }

    // ---------------- Rust 决策路径 ----------------

    private fun ensureRustEngine(settings: TriggerSettings): RustAutoSkipEngine? {
        if (rustUnavailable) return null
        val settingsJson = settings.toRustJson()
        val current = rustEngine
        if (current == null) {
            val created = RustAutoSkipEngine.create(settings)
            if (created == null) {
                rustUnavailable = true
                events?.onAutoSkipLog("Rust 引擎不可用，回退到 Kotlin 状态机")
                return null
            }
            created.updateKeywords(optionKeywords)
            rustEngine = created
            lastSettingsJson = settingsJson
            events?.onAutoSkipLog("自动对话已切换到 Rust 引擎")
            return created
        }
        if (lastSettingsJson != settingsJson) {
            current.updateSettings(settings)
            lastSettingsJson = settingsJson
        }
        return current
    }

    private fun tickWithRust(
        engine: RustAutoSkipEngine,
        tick: FeatureTick,
        content: CaptureContent,
        settings: TriggerSettings,
        actions: ActionEmitter,
    ) {
        val capture = content.captureRectArea
        val dialogue = inDialogue(content)
        val state = lastRustState
        val inDialog = state == "inDialog"
        val confirming = state == "confirming"
        val now = System.currentTimeMillis()

        // 选项区（OCR + 感叹号）只在决策窗口采集：OCR 单次几百毫秒，
        // 每拍都跑会把整拍拖到 2 秒，跳过点击也就跟着变慢。
        val optionWindow = inDialog && now - lastOptionObservationAtMs >= OPTION_OBSERVATION_INTERVAL_MS
        if (optionWindow) lastOptionObservationAtMs = now

        val exclamation = if (optionWindow && settings.smartOptionEnabled && settings.exclamationClickEnabled) {
            content.findMulti(assets.get(TASK_NAME, "ExclamationIcon", capture))
        } else {
            emptyList()
        }
        // Confirming 阶段每拍都要看图标是否还在原处（模板匹配便宜），其余拍只在决策窗口查。
        val chatHits = if (confirming || optionWindow) {
            content.findMulti(assets.get(TASK_NAME, "ChatIcon", capture))
        } else {
            emptyList()
        }
        val ocrLines = if (optionWindow && settings.smartOptionEnabled) {
            collectOptionLines(content, chatHits)
        } else {
            emptyList()
        }
        // 黑屏比例要扫中部 1/3 的像素，也节流；黑屏点击本身有 1200ms 间隔。
        val blackWindow = state == "idle" && now - lastBlackObservationAtMs >= BLACK_OBSERVATION_INTERVAL_MS
        val blackRate = if (blackWindow && settings.blackScreenClickEnabled) {
            lastBlackObservationAtMs = now
            computeBlackRate(content)
        } else {
            null
        }

        val observation = buildObservation(tick, capture, dialogue, exclamation, chatHits, ocrLines, blackRate)
        val result = engine.tick(observation)
        if (result == null) {
            rustUnavailable = true
            engine.close()
            rustEngine = null
            events?.onAutoSkipLog("Rust 引擎调用失败，回退到 Kotlin 状态机")
            return
        }

        val payload = JSONObject(result)
        lastRustState = payload.optString("state", lastRustState)
        dispatchActions(payload, actions)
        dispatchEvents(payload)
    }

    /** 选项文字区的 OCR：区域算法与 Rust 侧一致（以最下方对话图标为基准）。 */
    private fun collectOptionLines(content: CaptureContent, hits: List<Region>): List<Region> {
        if (hits.isEmpty()) return emptyList()
        val region = content.captureRectArea
        val lowest = hits.maxByOrNull { it.y } ?: return emptyList()
        val scale = region.width / 1920.0
        val ocrLeft = (lowest.x + lowest.width + 8 * scale).toInt().coerceAtMost(region.width - 1)
        val ocrWidth = (535 * scale).toInt().coerceAtLeast(80)
        val ocrTop = (region.height / 12).coerceAtLeast(0)
        val ocrBottom = (lowest.y + lowest.height + 30 * scale).toInt().coerceAtMost(region.height)
        val ocrHeight = ocrBottom - ocrTop
        if (ocrLeft < 0 || ocrWidth <= 0 || ocrHeight <= 0) return emptyList()
        val recognitionObject = RecognitionObject.ocrThis()
        recognitionObject.regionOfInterest = IntRect(ocrLeft, ocrTop, ocrWidth, ocrHeight)
        return region.findMulti(recognitionObject)
    }

    /** 画面中部 1/3 的黑像素占比；失败返回 null。 */
    private fun computeBlackRate(content: CaptureContent): Double? {
        val region = content.captureRectArea
        val grey = region.cacheGreyMatSafe ?: return null
        val width = grey.cols()
        val height = grey.rows()
        if (width <= 0 || height < 30) return null
        val top = height / 3
        val roi = MatOps.roiView(grey, IntRect(0, top, width, height - top * 2))
        val mask = Mat()
        return try {
            Core.inRange(roi, Scalar(0.0), Scalar(0.0), mask)
            val black = Core.countNonZero(mask).toDouble()
            val total = (roi.cols() * roi.rows()).toDouble()
            if (total <= 0.0) null else black / total
        } finally {
            roi.release()
            mask.release()
        }
    }

    private fun buildObservation(
        tick: FeatureTick,
        capture: Region,
        dialogue: Boolean,
        exclamation: List<Region>,
        chatHits: List<Region>,
        ocrLines: List<Region>,
        blackRate: Double?,
    ): String {
        val payload = JSONObject()
        payload.put("nowMs", System.currentTimeMillis())
        payload.put("screen", JSONObject().put("w", tick.screenWidth).put("h", tick.screenHeight))
        payload.put("capture", JSONObject().put("w", capture.width).put("h", capture.height))
        payload.put("foreground", true)
        payload.put("dialogue", dialogue)
        payload.put("exclamation", rectArray(exclamation))
        payload.put("chatIcons", rectArray(chatHits))
        payload.put("ocrLines", ocrArray(ocrLines))
        if (blackRate != null) payload.put("blackRate", blackRate)
        return payload.toString()
    }

    private fun rectArray(regions: List<Region>): JSONArray {
        val array = JSONArray()
        for (region in regions) {
            array.put(
                JSONObject()
                    .put("x", region.x)
                    .put("y", region.y)
                    .put("w", region.width)
                    .put("h", region.height),
            )
        }
        return array
    }

    private fun ocrArray(regions: List<Region>): JSONArray {
        val array = JSONArray()
        for (region in regions) {
            array.put(
                JSONObject()
                    .put("x", region.x)
                    .put("y", region.y)
                    .put("w", region.width)
                    .put("h", region.height)
                    .put("text", region.text),
            )
        }
        return array
    }

    private fun dispatchActions(payload: JSONObject, actions: ActionEmitter) {
        val array = payload.optJSONArray("actions") ?: return
        for (index in 0 until array.length()) {
            val action = array.optJSONObject(index) ?: continue
            when (action.optString("type")) {
                "tap" -> actions.emit(ClickAction(action.optInt("x"), action.optInt("y")))
                "back" -> actions.emit(BackAction)
                else -> Unit
            }
        }
    }

    private fun dispatchEvents(payload: JSONObject) {
        val array = payload.optJSONArray("events") ?: return
        for (index in 0 until array.length()) {
            val message = array.optString(index, "")
            if (message.isEmpty()) continue
            when (message) {
                "空闲扫描" -> events?.onIdleScan()
                "对话图标命中" -> events?.onTalkHistoryMatched()
                else -> events?.onAutoSkipLog(message)
            }
        }
    }

    // ---------------- Kotlin 回退状态机（与 Rust 语义一致） ----------------

    private fun tickLegacy(
        tick: FeatureTick,
        content: CaptureContent,
        settings: TriggerSettings,
        actions: ActionEmitter,
    ) {
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

    /** 黑屏转场检测：非对话时画面中部 1/3 区域接近全黑则点击推进。 */
    private fun clickBlackScreenIfNeeded(
        content: CaptureContent,
        tick: FeatureTick,
        settings: TriggerSettings,
        actions: ActionEmitter,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastBlackClickMs < settings.blackClickIntervalMs) return
        val rate = computeBlackRate(content) ?: return
        if (rate >= BLACK_RATE_MIN && rate < BLACK_RATE_MAX) {
            val (cx, cy) = content.captureRectArea.centerOnNativeCapture()
            val points = ArrayList<Pair<Int, Int>>(2)
            points.add(cx to cy)
            if (settings.blackScreenCustomPosition) {
                val ax = (tick.screenWidth * settings.blackScreenPositionX).toInt()
                    .coerceIn(0, tick.screenWidth)
                val ay = (tick.screenHeight * settings.blackScreenPositionY).toInt()
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
    }

    /** 对话判定：仅以对话历史图标命中为准，OCR 兜底（1 秒节流）。 */
    private fun inDialogue(content: CaptureContent): Boolean {
        if (isTalkHistoryIcon(content, assets)) return true

        val now = System.currentTimeMillis()
        if (now - lastLabelOcrAtMs < TALK_HISTORY_LABEL_OCR_INTERVAL_MS) return lastLabelOcrHit
        lastLabelOcrAtMs = now
        val hit = findTalkHistoryLabel(content, assets)
        lastLabelOcrHit = hit != null
        if (hit != null) events?.onAutoSkipLog("对话图标未命中，左侧文字命中：${hit.text}")
        return lastLabelOcrHit
    }

    /** 关键词决策（回退路径用；Rust 侧有同样的实现）。 */
    private fun decideOption(content: CaptureContent, hits: List<Region>): Decision? {
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

        val sorted = lines.sortedBy { it.y }
        val maxYGap = (OPTION_MAX_Y_GAP * scale).toInt()
        val filtered = sorted.filterIndexed { index, line ->
            val text = line.text
            if (text.isBlank()) return@filterIndexed false
            if (text.length < 5 && text.all { it in ASCII_OPTION_CHARS }) {
                return@filterIndexed false
            }
            if (index != sorted.size - 1) {
                val gap = sorted[index + 1].y - line.y
                if (gap > maxYGap) return@filterIndexed false
            }
            true
        }
        if (filtered.isEmpty()) return Decision(lowest)

        events?.onOptionTextsRecognized(filtered.map { it.text })

        for (item in filtered) {
            val text = item.text ?: continue
            if (optionKeywords.select.any { text.contains(it) }) {
                events?.onAutoSkipLog("主动选择命中：$text")
                return Decision(item)
            }
            if (optionKeywords.pause.any { text.contains(it) }) {
                events?.onAutoSkipLog("命中暂停词，等待手动选择：$text")
                return null
            }
        }
        for (item in filtered) {
            val text = item.text ?: continue
            if (optionKeywords.defaultPause.any { text.contains(it) }) {
                events?.onAutoSkipLog("命中默认暂停词，等待手动选择：$text")
                return null
            }
        }
        events?.onAutoSkipLog("无关键词命中，点最低选项")
        return Decision(filtered.last())
    }

    companion object {
        const val TASK_NAME = "AutoSkip"
        private const val TAG = "BetterGI.AutoSkip"
        private const val CONFIRM_WINDOW_MS = 600L
        private const val CONFIRM_TIMEOUT_MS = 1200L
        private const val OPTION_DECISION_INTERVAL_MS = 1000L
        private const val TALK_HISTORY_LABEL_OCR_INTERVAL_MS = 1000L
        private const val OPTION_MAX_Y_GAP = 150
        /** 选项区采集节流，与 Rust 侧决策节流（1000ms）对齐。 */
        private const val OPTION_OBSERVATION_INTERVAL_MS = 900L
        /** 黑屏比例采集节流。 */
        private const val BLACK_OBSERVATION_INTERVAL_MS = 400L
        private const val OPTION_ANCHOR_TOLERANCE_PX = 32
        private const val QUICK_SKIP_MIN_SEPARATION_PX = 32
        private const val BLACK_RATE_MIN = 0.5
        private const val BLACK_RATE_MAX = 0.98999
        private const val IDLE_LOG_INTERVAL_MS = 5000L
        private const val SKIP_LOG_INTERVAL_MS = 5000L
        private const val ASCII_OPTION_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ."

        fun selectTopChatIcon(hits: List<Region>): Region? = hits.minByOrNull { it.y }
    }
}
