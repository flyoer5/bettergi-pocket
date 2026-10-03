package com.bettergi.pocket.recognition

import com.bettergi.pocket.recognition.area.GameCaptureRegion
import com.bettergi.pocket.recognition.area.ImageRegion
import com.bettergi.pocket.recognition.area.Region
import com.bettergi.pocket.recognition.ocr.IOcrService
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.recognition.opencv.OpenCvRuntime
import org.opencv.core.Mat

/**
 * 一帧捕获结果，对应 BetterGI 的 CaptureContent。
 * [captureRectArea] 是缩到不超过 1080P 宽后的识别区域。
 */
class CaptureContent(
    val nativeRegion: GameCaptureRegion,
    val captureRectArea: ImageRegion,
    val frameIndex: Int,
) : AutoCloseable {
    fun find(ro: RecognitionObject): Region = captureRectArea.find(ro)

    fun findMulti(ro: RecognitionObject): List<Region> = captureRectArea.findMulti(ro)

    override fun close() {
        if (captureRectArea !== nativeRegion) {
            captureRectArea.close()
        }
        nativeRegion.close()
    }

    companion object {
        fun fromBgr(
            bgr: Mat,
            frameIndex: Int = 0,
            ocrService: IOcrService = OcrFactory.default,
        ): CaptureContent {
            if (!OpenCvRuntime.ensureLoaded()) {
                throw IllegalStateException("OpenCV is not loaded")
            }
            val native = GameCaptureRegion(bgr, 0, 0, ocrService = ocrService)
            val recognition = native.deriveTo1080P()
            return CaptureContent(
                nativeRegion = native,
                captureRectArea = recognition,
                frameIndex = frameIndex,
            )
        }

    }
}
