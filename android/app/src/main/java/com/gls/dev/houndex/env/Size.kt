package com.gls.dev.houndex.env

import android.graphics.Bitmap
import android.text.TextUtils
import java.io.Serializable
import java.util.ArrayList

/** Size class independent of a Camera object. */
class Size(val width: Int, val height: Int): Comparable<Size>, Serializable {

    constructor(bitmap: Bitmap): this(bitmap.width, bitmap.height)

    companion object{
        // 1.4 went out with this UID so we'll need to maintain it to preserve pending queries when
        // upgrading.
        const val SERIAL_VERSION_UID = 7689808733290872361L

        fun parseFromString(sizeString: String): Size? {

            val _sizeString = sizeString.trim { it <= ' ' }

            if (TextUtils.isEmpty(_sizeString)) {
                return null
            }

            // The expected format is "<width>x<height>".
            val components = _sizeString.split("x")

            return if (components.size == 2) {
                try {
                    val width = Integer.parseInt(components[0])
                    val height = Integer.parseInt(components[1])
                    Size(width, height)
                } catch (e: NumberFormatException) {
                    null
                }

            } else {
                null
            }
        }

        fun dimensionsAsString(width: Int, height: Int) = "${width}x${height}"
    }

    /**
     * Rotate a size by the given number of degrees.
     *
     * @param size Size to rotate.
     * @param rotation Degrees {0, 90, 180, 270} to rotate the size.
     * @return Rotated size.
     */
    fun Size.getRotatedSize(rotation: Int): Size {
        return if (rotation % 180 != 0) {
            // The phone is portrait, therefore the camera is sideways and frame should be rotated.
            Size(this.height, this.width)
        } else this
    }

    fun sizeStringToList(sizes: String?): List<Size> {
        val sizeList = ArrayList<Size>()
        if (sizes != null) {
            val pairs = sizes.split(",")
            for (pair in pairs) {
                val size = parseFromString(pair)
                if (size != null) {
                    sizeList.add(size)
                }
            }
        }
        return sizeList
    }

    fun sizeListToString(sizes: List<Size>?): String {
        var sizesString = ""
        if (sizes != null && sizes.isNotEmpty()) {
            sizesString = sizes[0].toString()
            for (i in 1 until sizes.size) {
                sizesString += "," + sizes[i].toString()
            }
        }
        return sizesString
    }

    fun aspectRatio() = width.toFloat() / height.toFloat()

    override fun compareTo(other: Size) = width * height - other.width * other.height

    override fun equals(other: Any?): Boolean {
        if (other == null) {
            return false
        }

        if (other !is Size) {
            return false
        }

        val otherSize = other as Size?
        //TODO: Remove exclamation marks
        return width == otherSize!!.width && height == otherSize.height
    }

    override fun hashCode() = width * 32713 + height

    override fun toString() = dimensionsAsString(width, height)
}