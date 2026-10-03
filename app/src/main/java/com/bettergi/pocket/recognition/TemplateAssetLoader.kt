package com.bettergi.pocket.recognition

import android.content.res.AssetManager
import com.bettergi.pocket.recognition.opencv.MatOps
import com.bettergi.pocket.recognition.opencv.OpenCvRuntime
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.IOException

/**
 * 从 `assets/recognition/{task}/{WxH}/file.png` 加载模板。
 * 没有精确分辨率目录时回退到 `1920x1080`，再回退到任务根目录。
 * 未启用参考搜索时，捕获宽 < 1920 会按 [CaptureScale.assetScale] 缩小。
 */
class TemplateAssetLoader(
    private val assets: AssetManager,
) {
    fun load(
        taskName: String,
        fileName: String,
        captureWidth: Int,
        captureHeight: Int,
        applyLegacyAssetScale: Boolean = true,
    ): Mat {
        if (!OpenCvRuntime.ensureLoaded()) {
            throw IllegalStateException("OpenCV is not loaded")
        }
        val exact = path(taskName, "${captureWidth}x$captureHeight", fileName)
        val fallback = path(taskName, "${CaptureScale.BASELINE_WIDTH}x${CaptureScale.BASELINE_HEIGHT}", fileName)
        val taskRoot = "recognition/$taskName/$fileName"
        val assetPath = when {
            exists(exact) -> exact
            exists(fallback) -> fallback
            exists(taskRoot) -> taskRoot
            else -> throw IllegalArgumentException("未找到 $taskName 中的 $fileName")
        }

        val bytes = assets.open(assetPath).use { it.readBytes() }
        val encoded = Mat(1, bytes.size, CvType.CV_8UC1)
        encoded.put(0, 0, bytes)
        val decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        encoded.release()
        if (decoded.empty()) {
            throw IllegalArgumentException("无法解码模板: $assetPath")
        }

        if (applyLegacyAssetScale && captureWidth < CaptureScale.BASELINE_WIDTH) {
            val scaled = MatOps.resize(decoded, captureWidth / CaptureScale.BASELINE_WIDTH.toDouble())
            if (scaled !== decoded) {
                decoded.release()
            }
            return scaled
        }
        return decoded
    }

    private fun path(taskName: String, resolution: String, fileName: String): String {
        return "recognition/$taskName/$resolution/$fileName"
    }

    private fun exists(path: String): Boolean {
        return try {
            assets.open(path).close()
            true
        } catch (_: IOException) {
            false
        }
    }
}
