package com.bettergi.pocket.feature.autoskip

import android.content.res.AssetManager
import org.json.JSONArray

/**
 * 对话选项关键词决策表（直接使用 PC 版 BetterGI 的词表文件）。
 * [select] 主动选择，优先级最高；[pause] 高优先级暂停；
 * [defaultPause] 默认不选择，优先级低于橙色关键选项。
 */
class OptionKeywords(
    val select: List<String> = emptyList(),
    val pause: List<String> = emptyList(),
    val defaultPause: List<String> = emptyList(),
) {
    companion object {
        private const val TAG = "BetterGI.Options"

        fun load(assets: AssetManager): OptionKeywords {
            return OptionKeywords(
                select = loadList(assets, "recognition/AutoSkip/select_options.json"),
                pause = loadList(assets, "recognition/AutoSkip/pause_options.json"),
                defaultPause = loadList(assets, "recognition/AutoSkip/default_pause_options.json"),
            )
        }

        /**
         * PC 版词表文件带 `//` 注释行与尾逗号，不是严格 JSON，解析前先清洗，
         * 保证与 PC 版词表逐字一致。
         */
        private fun loadList(assets: AssetManager, path: String): List<String> {
            return try {
                val raw = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
                val cleaned = raw.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("//") }
                    .joinToString("\n")
                    .replace(Regex(",(\\s*])")) { it.groupValues[1] }
                val arr = JSONArray(cleaned)
                List(arr.length()) { arr.optString(it).trim() }.filter { it.isNotEmpty() }
            } catch (e: Exception) {
                com.bettergi.pocket.log.AppLog.w(TAG, "加载词表失败: $path -> ${e.message}")
                emptyList()
            }
        }
    }
}
