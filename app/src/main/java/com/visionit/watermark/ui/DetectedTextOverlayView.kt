package com.visionit.watermark.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DetectedTextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class TextBlockItem(
        val text: String,
        val box: Rect
    )

    private var imageW = 1
    private var imageH = 1
    private val imageRect = RectF()
    private val items = mutableListOf<TextBlockItem>()

    var onTextBlockTapped: ((TextBlockItem) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    fun setImageSize(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            imageW = w
            imageH = h
            updateImageRect()
            invalidate()
        }
    }

    fun setDetectedTexts(list: List<TextBlockItem>) {
        items.clear()
        items.addAll(list)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageRect()
    }

    private fun updateImageRect() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val viewRatio = vw / vh
        val imgRatio = imageW.toFloat() / imageH.toFloat()
        if (imgRatio > viewRatio) {
            val rh = vw / imgRatio
            val top = (vh - rh) / 2f
            imageRect.set(0f, top, vw, top + rh)
        } else {
            val rw = vh * imgRatio
            val left = (vw - rw) / 2f
            imageRect.set(left, 0f, left + rw, vh)
        }
    }

    private fun mapToScreen(r: Rect): RectF {
        val sx = imageRect.width() / imageW.toFloat()
        val sy = imageRect.height() / imageH.toFloat()
        return RectF(
            imageRect.left + r.left * sx - 4f,
            imageRect.top + r.top * sy - 4f,
            imageRect.left + r.right * sx + 4f,
            imageRect.top + r.bottom * sy + 4f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageRect.width() <= 0f) return
        items.forEach { item ->
            val sr = mapToScreen(item.box)
            canvas.drawRoundRect(sr, 8f, 8f, fillPaint)
            canvas.drawRoundRect(sr, 8f, 8f, borderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            for (item in items) {
                val sr = mapToScreen(item.box)
                val hit = RectF(sr.left - 12f, sr.top - 12f, sr.right + 12f, sr.bottom + 12f)
                if (hit.contains(x, y)) {
                    onTextBlockTapped?.invoke(item)
                    return true
                }
            }
        }
        return true
    }
}
