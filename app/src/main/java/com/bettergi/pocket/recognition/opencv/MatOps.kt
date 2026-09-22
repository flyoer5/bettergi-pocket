package com.bettergi.pocket.recognition.opencv

import android.graphics.Bitmap
import android.media.Image
import com.bettergi.pocket.capture.Frame
import com.bettergi.pocket.recognition.ColorBgr
import com.bettergi.pocket.recognition.ColorConversion
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

object MatOps {
    fun copyRgbaImage(image: Image, dest: ByteArray) {
        val plane = image.planes.firstOrNull() ?: return
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowBytes = width * pixelStride
        require(dest.size >= height * rowBytes) { "RGBA buffer too small" }
        val buffer = plane.buffer.duplicate()
        if (rowStride == rowBytes) {
            buffer.get(dest, 0, height * rowBytes)
            return
        }
        var destOffset = 0
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(dest, destOffset, rowBytes)
            destOffset += rowBytes
        }
    }

    fun rgbaToBgr(width: Int, height: Int, rgba8888: ByteArray): Mat {
        val rgba = Mat(height, width, CvType.CV_8UC4)
        try {
            val written = rgba.put(0, 0, rgba8888)
            if (written == 0) {
                throw IllegalStateException("Failed to copy RGBA into Mat ${width}x$height")
            }
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            return bgr
        } finally {
            rgba.release()
        }
    }

    fun frameToBgr(frame: Frame): Mat = rgbaToBgr(frame.width, frame.height, frame.rgba8888)

    fun bgrToGray(src: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        return gray
    }

    fun createMask(src: Mat, maskColor: ColorBgr): Mat {
        val mask = Mat()
        val scalar = Scalar(maskColor.b, maskColor.g, maskColor.r)
        Core.inRange(src, scalar, scalar, mask)
        val inverted = Mat()
        Core.bitwise_not(mask, inverted)
        mask.release()
        return inverted
    }

    fun resize(src: Mat, target: IntSize, interpolation: Int = Imgproc.INTER_LINEAR): Mat {
        if (src.cols() == target.width && src.rows() == target.height) {
            return src
        }
        val dst = Mat()
        Imgproc.resize(src, dst, Size(target.width.toDouble(), target.height.toDouble()), 0.0, 0.0, interpolation)
        return dst
    }

    fun resize(src: Mat, scale: Double, interpolation: Int = Imgproc.INTER_LINEAR): Mat {
        if (abs(scale - 1.0) < 0.00001) {
            return src
        }
        return resize(
            src,
            IntSize(
                width = (src.cols() * scale).toInt().coerceAtLeast(1),
                height = (src.rows() * scale).toInt().coerceAtLeast(1),
            ),
            interpolation,
        )
    }

    fun convertColor(src: Mat, conversion: ColorConversion): Mat {
        if (conversion == ColorConversion.None) {
            return src
        }
        val dst = Mat()
        val code = when (conversion) {
            ColorConversion.None -> return src
            ColorConversion.BgrToRgb -> Imgproc.COLOR_BGR2RGB
            ColorConversion.BgrToHsv -> Imgproc.COLOR_BGR2HSV
            ColorConversion.BgrToGray -> Imgproc.COLOR_BGR2GRAY
        }
        Imgproc.cvtColor(src, dst, code)
        return dst
    }

    fun inRange(src: Mat, lower: ColorBgr, upper: ColorBgr): Mat {
        val dst = Mat()
        Core.inRange(
            src,
            Scalar(lower.b, lower.g, lower.r),
            Scalar(upper.b, upper.g, upper.r),
            dst,
        )
        return dst
    }

    fun binary(src: Mat, threshold: Int): Mat {
        val dst = Mat()
        Imgproc.threshold(src, dst, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        return dst
    }

    fun roiView(src: Mat, roi: IntRect): Mat {
        return Mat(src, roi.toCvRect())
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        when (mat.channels()) {
            1 -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_GRAY2RGBA)
            3 -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
            4 -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGRA2RGBA)
            else -> throw IllegalArgumentException("Unsupported channel count: ${mat.channels()}")
        }
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }

    private val pixelBuffer = ThreadLocal.withInitial { ByteArray(1) }

    fun u8(mat: Mat, y: Int, x: Int): Int {
        val buf = pixelBuffer.get()
        mat.get(y, x, buf)
        return buf[0].toInt() and 0xFF
    }

    fun setU8(mat: Mat, y: Int, x: Int, value: Int) {
        val buf = pixelBuffer.get()
        buf[0] = value.toByte()
        mat.put(y, x, buf)
    }
}

fun IntRect.toCvRect(): Rect = Rect(x, y, width, height)
