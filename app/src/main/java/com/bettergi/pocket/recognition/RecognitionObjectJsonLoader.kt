package com.bettergi.pocket.recognition

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

class RecognitionObjectJsonLoadContext(
    val templateLoader: (fileName: String, applyLegacyAssetScale: Boolean) -> org.opencv.core.Mat,
)

object RecognitionObjectJsonLoader {
    fun load(json: String, objectName: String, context: RecognitionObjectJsonLoadContext): RecognitionObject {
        val root = JSONObject(json)
        val objects = root.optJSONObject("objects")
            ?: throw IllegalArgumentException("Recognition.json 缺少 objects")
        if (!objects.has(objectName)) {
            throw NoSuchElementException("未找到名称为 $objectName 的 RecognitionObject 配置")
        }
        val regions = jsonStringMap(root.optJSONObject("regions"))
        val templates = jsonStringMap(root.optJSONObject("templates"))
        return build(objects.getJSONObject(objectName), objectName, regions, templates, context)
    }

    private fun build(
        config: JSONObject,
        objectName: String,
        regions: Map<String, String>,
        templates: Map<String, String>,
        context: RecognitionObjectJsonLoadContext,
    ): RecognitionObject {
        val typeName = config.optString("type")
        if (typeName.isNullOrBlank()) {
            throw IllegalArgumentException("type 不能为空")
        }
        val recognitionType = parseEnum<RecognitionTypes>(typeName, "type")
        val templateName = if (recognitionType == RecognitionTypes.TemplateMatch) {
            resolveAlias(config.optString("template"), templates, "模板")
                ?: throw IllegalArgumentException("对象 $objectName 缺少 template 配置")
        } else {
            null
        }

        val ro = RecognitionObject().apply {
            this.recognitionType = recognitionType
            name = config.optString("name").takeIf { it.isNotBlank() }
                ?: templateName?.substringBeforeLast('.')
                ?: objectName
        }

        config.optString("roi").takeIf { it.isNotBlank() }?.let { expr ->
            ro.regionOfInterest = evaluateRect(resolveAlias(expr, regions, "区域") ?: expr)
        }
        if (config.has("threshold")) {
            ro.threshold = config.getDouble("threshold")
        }
        if (config.has("use3Channels")) {
            ro.use3Channels = config.getBoolean("use3Channels")
        }
        config.optString("templateMatchMode").takeIf { it.isNotBlank() }?.let {
            ro.templateMatchMode = parseEnum(it, "templateMatchMode")
        }
        if (config.has("useMask")) {
            ro.useMask = config.getBoolean("useMask")
        }
        if (config.has("maxMatchCount")) {
            ro.maxMatchCount = config.getInt("maxMatchCount")
        }
        if (config.has("useBinaryMatch")) {
            ro.useBinaryMatch = config.getBoolean("useBinaryMatch")
        }
        if (config.has("binaryThreshold")) {
            ro.binaryThreshold = config.getInt("binaryThreshold")
        }

        // ---- OCR 相关字段（Ocr / OcrMatch / ColorRangeAndOcr）----
        config.optJSONArray("allContainMatchText")?.let { arr ->
            ro.allContainMatchText = jsonStringList(arr)
        }
        config.optJSONArray("oneContainMatchText")?.let { arr ->
            ro.oneContainMatchText = jsonStringList(arr)
        }
        config.optJSONArray("regexMatchText")?.let { arr ->
            ro.regexMatchText = jsonStringList(arr)
        }
        config.optJSONObject("replaceDictionary")?.let { obj ->
            ro.replaceDictionary = buildReplaceDictionary(obj)
        }
        config.optString("colorConversion").takeIf { it.isNotBlank() }?.let {
            ro.colorConversion = parseEnum(it, "colorConversion")
        }
        config.optJSONArray("lowerColor")?.let { arr ->
            if (arr.length() >= 3) {
                ro.lowerColor = ColorBgr(arr.getDouble(0), arr.getDouble(1), arr.getDouble(2))
            }
        }
        config.optJSONArray("upperColor")?.let { arr ->
            if (arr.length() >= 3) {
                ro.upperColor = ColorBgr(arr.getDouble(0), arr.getDouble(1), arr.getDouble(2))
            }
        }

        val reference = config.optJSONObject("reference")
        if (reference != null) {
            val size = reference.optJSONArray("size")
            if (size != null && size.length() == 2) {
                ro.referenceImageSize = IntSize(size.getInt(0), size.getInt(1))
            }
            reference.optString("bbox").takeIf { it.isNotBlank() }?.let { expr ->
                ro.referenceBoundingBox = evaluateRect(resolveAlias(expr, regions, "区域") ?: expr)
            }
        }

        val search = config.optJSONObject("search")
        if (search != null) {
            var options = SearchOptions()
            search.optString("anchor").takeIf { it.isNotBlank() }?.let {
                options = options.copy(anchorMode = parseEnum(it, "search.anchor"))
            }
            search.optString("box").takeIf { it.isNotBlank() }?.let { expr ->
                options = options.copy(
                    referenceSearchBox = evaluateRect(resolveAlias(expr, regions, "区域") ?: expr),
                )
            }
            val expand = search.optJSONArray("expand")
            if (expand != null && expand.length() == 2) {
                options = options.copy(expandSize = IntSize(expand.getInt(0), expand.getInt(1)))
            }
            val expandPercent = search.optJSONArray("expandPercent")
            if (expandPercent != null) {
                options = options.copy(
                    expandPercent = SearchExpandRatio.fromThickness(
                        List(expandPercent.length()) { expandPercent.getDouble(it) },
                    ),
                )
            }
            ro.searchOptions = options
        }

        if (recognitionType == RecognitionTypes.TemplateMatch) {
            val hasReference = ro.referenceImageSize != null && ro.referenceBoundingBox != null
            ro.templateImageMat = context.templateLoader(templateName!!, !hasReference)
        }
        return ro.initTemplate()
    }

    private fun evaluateRect(expression: String): IntRect {
        val match = RECT_REGEX.matchEntire(expression.trim())
            ?: throw IllegalArgumentException("表达式 $expression 未返回 Rect")
        return IntRect(
            x = round(match.groupValues[1].toDouble()).toInt(),
            y = round(match.groupValues[2].toDouble()).toInt(),
            width = round(match.groupValues[3].toDouble()).toInt(),
            height = round(match.groupValues[4].toDouble()).toInt(),
        )
    }

    private fun resolveAlias(
        raw: String?,
        table: Map<String, String>,
        kind: String,
    ): String? {
        var current = raw?.trim().orEmpty()
        if (current.isEmpty()) return null
        val seen = HashSet<String>()
        while (current.startsWith('@')) {
            val alias = current.substring(1)
            if (!seen.add(alias)) {
                throw IllegalArgumentException("${kind}别名 $alias 存在循环引用")
            }
            current = table[alias] ?: throw NoSuchElementException("未找到${kind}别名 $alias")
        }
        return current
    }

    private fun jsonStringList(arr: JSONArray): List<String> {
        return List(arr.length()) { arr.optString(it).trim() }.filter { it.isNotEmpty() }
    }

    /** replaceDictionary: { "正确词": ["错1", "错2"] } */
    private fun buildReplaceDictionary(obj: JSONObject): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val list = obj.optJSONArray(key)
            if (list != null) {
                result[key] = jsonStringList(list)
            }
        }
        return result
    }

    private fun jsonStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = obj.getString(key)
        }
        return result
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, fieldName: String): T {
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("$fieldName 的值 $value 不是有效的 ${T::class.java.simpleName}")
    }

    private val RECT_REGEX = Regex(
        """^rect\s*\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)$""",
        RegexOption.IGNORE_CASE,
    )
}
