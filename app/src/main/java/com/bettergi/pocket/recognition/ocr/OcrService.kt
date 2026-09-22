package com.bettergi.pocket.recognition.ocr

import android.content.Context
import android.util.Log
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.OcrText
import com.bettergi.pocket.recognition.opencv.MatOps
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.opencv.core.Mat
import java.util.concurrent.TimeUnit

data class OcrResultRegion(
    val rect: IntRect,
    val text: String,
    val score: Float,
)

data class OcrResult(
    val regions: List<OcrResultRegion>,
) {
    val text: String
        get() = regions
            .sortedWith(compareBy({ it.rect.centerY }, { it.rect.centerX }))
            .joinToString("\n") { it.text }

    companion object {
        val EMPTY = OcrResult(emptyList())
    }
}

interface IOcrService {
    fun recognize(mat: Mat): OcrResult

    fun recognizeWithoutDetector(mat: Mat): OcrResult = recognize(mat)

    fun recognizeText(mat: Mat): String = OcrText.removeAllSpace(recognize(mat).text)
}

object UnavailableOcrService : IOcrService {
    override fun recognize(mat: Mat): OcrResult = OcrResult.EMPTY
}

object OcrFactory {
    @Volatile
    var default: IOcrService = UnavailableOcrService

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        default = MlKitOcrService()
    }
}

class MlKitOcrService(
    private val timeoutMillis: Long = 1500,
) : IOcrService {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun recognize(mat: Mat): OcrResult {
        if (mat.empty()) return OcrResult.EMPTY
        val bitmap = MatOps.matToBitmap(mat)
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = Tasks.await(recognizer.process(image), timeoutMillis, TimeUnit.MILLISECONDS)
            toOcrResult(visionText)
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit OCR failed", e)
            OcrResult.EMPTY
        } finally {
            bitmap.recycle()
        }
    }

    private fun toOcrResult(visionText: Text): OcrResult {
        val regions = ArrayList<OcrResultRegion>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val rect = IntRect(box.left, box.top, box.width(), box.height())
                if (rect.isEmpty()) continue
                regions.add(OcrResultRegion(rect, line.text, 1.0f))
            }
        }
        return OcrResult(regions)
    }

    private companion object {
        private const val TAG = "BetterGI.Ocr"
    }
}
