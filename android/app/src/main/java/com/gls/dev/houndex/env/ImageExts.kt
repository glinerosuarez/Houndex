package com.gls.dev.houndex.env

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** Utility class for manipulating images.  */
object ImageUtils {

    // This value is 2 ^ 18 - 1, and is used to clamp the RGB values before their ranges
    // are normalized to eight bits.
    internal const val K_MAX_CHANNEL_VALUE = 262143

    /**
     * Utility method to compute the allocated size in bytes of a YUV420SP image of the given
     * dimensions.
     */
    fun getYUVByteSize(width: Int, height: Int): Int {
        // The luminance plane requires 1 byte per pixel.
        val ySize = width * height

        // The UV plane works on 2x2 blocks, so dimensions with odd size must be rounded up.
        // Each 2x2 block takes 2 bytes to encode, one each for U and V.
        val uvSize = (width + 1) / 2 * ((height + 1) / 2) * 2

        return ySize + uvSize
    }

    fun convertYUV420SPToARGB8888(input: ByteArray, width: Int, height: Int, output: IntArray) {
        val frameSize = width * height
        var j = 0
        var yp = 0
        while (j < height) {
            var uvp = frameSize + (j shr 1) * width
            var u = 0
            var v = 0

            var i = 0
            while (i < width) {
                val y = 0xff and input[yp].toInt()
                if (i and 1 == 0) {
                    v = 0xff and input[uvp++].toInt()
                    u = 0xff and input[uvp++].toInt()
                }

                output[yp] = YUV2RGB(y, u, v)
                i++
                yp++
            }
            j++
        }
    }

    private fun YUV2RGB(y: Int, u: Int, v: Int): Int {
        // Adjust and check YUV values
        val _y: Int = if (y - 16 < 0) 0 else y - 16
        val _u = u - 128
        val _v = v - 128

        // This is the floating point equivalent. We do the conversion in integer
        // because some Android devices do not have floating point in hardware.
        // nR = (int)(1.164 * nY + 2.018 * nU);
        // nG = (int)(1.164 * nY - 0.813 * nV - 0.391 * nU);
        // nB = (int)(1.164 * nY + 1.596 * nV);
        val y1192 = 1192 * _y
        var r = y1192 + 1634 * _v
        var g = y1192 - 833 * _v - 400 * _u
        var b = y1192 + 2066 * _u

        // Clipping RGB values to be inside boundaries [ 0 , kMaxChannelValue ]
        r = if (r > K_MAX_CHANNEL_VALUE) K_MAX_CHANNEL_VALUE else if (r < 0) 0 else r
        g = if (g > K_MAX_CHANNEL_VALUE) K_MAX_CHANNEL_VALUE else if (g < 0) 0 else g
        b = if (b > K_MAX_CHANNEL_VALUE) K_MAX_CHANNEL_VALUE else if (b < 0) 0 else b

        return -0x1000000 or (r shl 6 and 0xff0000) or (g shr 2 and 0xff00) or (b shr 10 and 0xff)
    }

    fun convertYUV420ToARGB8888(
        yData: ByteArray,
        uData: ByteArray,
        vData: ByteArray,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        out: IntArray
    ) {
        var yp = 0
        for (j in 0 until height) {
            val pY = yRowStride * j
            val pUV = uvRowStride * (j shr 1)

            for (i in 0 until width) {
                val uv_offset = pUV + (i shr 1) * uvPixelStride

                out[yp++] = YUV2RGB(
                    0xff and yData[pY + i].toInt(),
                    0xff and uData[uv_offset].toInt(),
                    0xff and vData[uv_offset].toInt()
                )
            }
        }
    }

    /**
     * Returns a transformation matrix from one reference frame into another. Handles cropping (if
     * maintaining aspect ratio is desired) and rotation.
     *
     * @param srcWidth Width of source frame.
     * @param srcHeight Height of source frame.
     * @param dstWidth Width of destination frame.
     * @param dstHeight Height of destination frame.
     * @param applyRotation Amount of rotation to apply from one frame to another. Must be a multiple
     * of 90.
     * @param maintainAspectRatio If true, will ensure that scaling in x and y remains constant,
     * cropping the image if necessary.
     * @return The transformation fulfilling the desired requirements.
     */
    fun getTransformationMatrix(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        applyRotation: Int,
        maintainAspectRatio: Boolean
    ): Matrix {
        val matrix = Matrix()

        if (applyRotation != 0) {
            if (applyRotation % 90 != 0) {
                Log.w("getTransformationMatrix", "Rotation of $applyRotation % 90 != 0")
            }

            // Translate so center of image is at origin.
            matrix.postTranslate(-srcWidth / 2.0f, -srcHeight / 2.0f)

            // Rotate around origin.
            matrix.postRotate(applyRotation.toFloat())
        }

        // Account for the already applied rotation, if any, and then determine how
        // much scaling is needed for each axis.
        val transpose = (abs(applyRotation) + 90) % 180 == 0

        val inWidth = if (transpose) srcHeight else srcWidth
        val inHeight = if (transpose) srcWidth else srcHeight

        // Apply scaling if necessary.
        if (inWidth != dstWidth || inHeight != dstHeight) {
            val scaleFactorX = dstWidth / inWidth.toFloat()
            val scaleFactorY = dstHeight / inHeight.toFloat()

            if (maintainAspectRatio) {
                // Scale by minimum factor so that dst is filled completely while
                // maintaining the aspect ratio. Some image may fall off the edge.
                val scaleFactor = scaleFactorX.coerceAtLeast(scaleFactorY)
                matrix.postScale(scaleFactor, scaleFactor)
            } else {
                // Scale exactly to fill dst from src.
                matrix.postScale(scaleFactorX, scaleFactorY)
            }
        }

        if (applyRotation != 0) {
            // Translate back from origin centered reference to destination frame.
            matrix.postTranslate(dstWidth / 2.0f, dstHeight / 2.0f)
        }

        return matrix
    }
}

/**
 * Saves a Bitmap object to disk for analysis.
 *
 * @param bitmap The bitmap to save.
 */
fun Bitmap.save() {
    save("preview.png")
}

/**
 * Saves a Bitmap object to disk for analysis.
 *
 * @param bitmap The bitmap to save.
 * @param filename The location to save the bitmap to.
 */
fun Bitmap.save(filename: String) {
    val tag = "save bitmap"
    //TODO: Remove deprecation warning
    val root =
        Environment.getExternalStorageDirectory().absolutePath + File.separator + "tensorflow"
    Log.i(tag, "Saving ${width}x${height} bitmap to $root")

    val myDir = File(root)

    if (!myDir.mkdirs()) {
        Log.i(tag, "Make dir failed")
    }

    val file = File(myDir, filename)
    if (file.exists()) {
        file.delete()
    }
    try {
        val out = FileOutputStream(file)
        compress(Bitmap.CompressFormat.PNG, 99, out)
        out.flush()
        out.close()
    } catch (e: Exception) {
        Log.e(tag, "Exception", e)
    }

}