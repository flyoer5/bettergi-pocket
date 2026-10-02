package com.bettergi.pocket.recognition

import android.content.res.AssetManager
import com.bettergi.pocket.recognition.area.ImageRegion
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * 从 `assets/recognition/{task}/Recognition.json` 加载识别对象，并按捕获宽高缓存。
 *
 * 缓存有上限（[MAX_CACHE_ENTRIES]）：分辨率变化会累积不同尺寸的模板 Mat（OpenCV native 内存），
 * 服务长期运行 / 反复启停时防止 native 内存无限增长。
 */
class RecognitionAssets(
    private val assets: AssetManager,
    private val templateLoader: TemplateAssetLoader = TemplateAssetLoader(assets),
) {
    private val cache = ConcurrentHashMap<CacheKey, RecognitionObject>()
    private val jsonCache = ConcurrentHashMap<String, String>()
    private val insertionOrder = ArrayDeque<CacheKey>()

    fun get(taskName: String, objectName: String, region: ImageRegion): RecognitionObject {
        return get(taskName, objectName, region.width, region.height)
    }

    fun get(taskName: String, objectName: String, captureWidth: Int, captureHeight: Int): RecognitionObject {
        val key = CacheKey(taskName, objectName, captureWidth, captureHeight)
        cache[key]?.let { return it }
        val loaded = load(key)
        cache[key] = loaded
        synchronized(insertionOrder) {
            insertionOrder.remove(key)
            insertionOrder.addLast(key)
            while (insertionOrder.size > MAX_CACHE_ENTRIES) {
                val oldest = insertionOrder.removeFirst()
                cache.remove(oldest)
            }
        }
        return loaded
    }

    /** 释放全部缓存（服务停止时调用，释放 OpenCV native Mat）。 */
    fun clear() {
        cache.clear()
        jsonCache.clear()
        synchronized(insertionOrder) {
            insertionOrder.clear()
        }
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

    private companion object {
        const val MAX_CACHE_ENTRIES = 32
    }
}