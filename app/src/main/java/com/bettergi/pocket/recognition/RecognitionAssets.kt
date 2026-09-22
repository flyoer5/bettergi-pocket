package com.bettergi.pocket.recognition

import android.content.res.AssetManager
import com.bettergi.pocket.recognition.area.ImageRegion
import java.util.concurrent.ConcurrentHashMap

/**
 * 从 `assets/recognition/{task}/Recognition.json` 加载识别对象，并按捕获宽高缓存。
 */
class RecognitionAssets(
    private val assets: AssetManager,
    private val templateLoader: TemplateAssetLoader = TemplateAssetLoader(assets),
) {
    private val cache = ConcurrentHashMap<CacheKey, RecognitionObject>()
    private val jsonCache = ConcurrentHashMap<String, String>()

    fun get(taskName: String, objectName: String, region: ImageRegion): RecognitionObject {
        return get(taskName, objectName, region.width, region.height)
    }

    fun get(taskName: String, objectName: String, captureWidth: Int, captureHeight: Int): RecognitionObject {
        val key = CacheKey(taskName, objectName, captureWidth, captureHeight)
        return cache.getOrPut(key) { load(key) }
    }

    private fun load(key: CacheKey): RecognitionObject {
        val json = jsonCache.getOrPut(key.taskName) {
            assets.open(jsonPath(key.taskName)).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        return RecognitionObjectJsonLoader.load(
            json,
            key.objectName,
            RecognitionObjectJsonLoadContext(
                templateLoader = { fileName, applyLegacyAssetScale ->
                    templateLoader.load(
                        taskName = key.taskName,
                        fileName = fileName,
                        captureWidth = key.captureWidth,
                        captureHeight = key.captureHeight,
                        applyLegacyAssetScale = applyLegacyAssetScale,
                    )
                },
            ),
        )
    }

    private fun jsonPath(taskName: String): String = "recognition/$taskName/Recognition.json"

    private data class CacheKey(
        val taskName: String,
        val objectName: String,
        val captureWidth: Int,
        val captureHeight: Int,
    )
}
