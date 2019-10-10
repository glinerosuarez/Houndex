package com.gls.dev.houndex.env

import android.graphics.*
import java.util.*

/** A class that encapsulates the tedious bits of rendering legible, bordered text onto a canvas.  */
class BorderedText
/**
 * Create a bordered text object with the specified interior and exterior colors, text size and
 * alignment.
 *
 * @param interiorColor the interior text color
 * @param exteriorColor the exterior text color
 * @param textSize text size in pixels
 */
    (interiorColor: Int, exteriorColor: Int, val textSize: Float) {
    private val interiorPaint = Paint()
    private val exteriorPaint: Paint

    /**
     * Creates a left-aligned bordered text object with a white interior, and a black exterior with
     * the specified text size.
     *
     * @param textSize text size in pixels
     */
    constructor(textSize: Float) : this(Color.WHITE, Color.BLACK, textSize)

    init {
        interiorPaint.textSize = textSize
        interiorPaint.color = interiorColor
        interiorPaint.style = Paint.Style.FILL
        interiorPaint.isAntiAlias = false
        interiorPaint.alpha = 255

        exteriorPaint = Paint()
        exteriorPaint.textSize = textSize
        exteriorPaint.color = exteriorColor
        exteriorPaint.style = Paint.Style.FILL_AND_STROKE
        exteriorPaint.strokeWidth = textSize / 8
        exteriorPaint.isAntiAlias = false
        exteriorPaint.alpha = 255
    }

    fun setTypeface(typeface: Typeface) {
        interiorPaint.typeface = typeface
        exteriorPaint.typeface = typeface
    }

    fun drawText(canvas: Canvas, posX: Float, posY: Float, text: String) {
        canvas.drawText(text, posX, posY, exteriorPaint)
        canvas.drawText(text, posX, posY, interiorPaint)
    }

    fun drawText(canvas: Canvas, posX: Float, posY: Float, text: String, bgPaint: Paint) {

        val width = exteriorPaint.measureText(text)
        val textSize = exteriorPaint.textSize
        val paint = Paint(bgPaint)
        paint.style = Paint.Style.FILL
        paint.alpha = 160
        canvas.drawRect(posX, posY + textSize.toInt(), posX + width.toInt(), posY, paint)

        canvas.drawText(text, posX, posY + textSize, interiorPaint)
    }

    fun drawLines(canvas: Canvas, posX: Float, posY: Float, lines: Vector<String>) {
        for ((lineNum, line) in lines.withIndex()) {
            drawText(canvas, posX, posY - textSize * (lines.size - lineNum - 1), line)
        }
    }

    fun setInteriorColor(color: Int) {
        interiorPaint.color = color
    }

    fun setExteriorColor(color: Int) {
        exteriorPaint.color = color
    }

    fun setAlpha(alpha: Int) {
        interiorPaint.alpha = alpha
        exteriorPaint.alpha = alpha
    }

    fun getTextBounds(
        line: String, index: Int, count: Int, lineBounds: Rect
    ) {
        interiorPaint.getTextBounds(line, index, count, lineBounds)
    }

    fun setTextAlign(align: Paint.Align) {
        interiorPaint.textAlign = align
        exteriorPaint.textAlign = align
    }
}
