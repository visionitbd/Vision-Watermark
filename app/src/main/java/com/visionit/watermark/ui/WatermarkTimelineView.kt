package com.visionit.watermark.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.visionit.watermark.model.WatermarkBox
import com.visionit.watermark.util.GalleryHelper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WatermarkTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var boxes: List<WatermarkBox> = emptyList()
    private var selectedIndex: Int = 0
    private var durationMs: Long = 10_000L
    private var playheadMs: Long = 0L
    private var thumbnails: List<Bitmap> = emptyList()

    var onBoxSelected: ((Int) -> Unit)? = null
    var onRangeChanged: ((Int, Long, Long) -> Unit)? = null
    var onScrubTo: ((Long) -> Unit)? = null

    private enum class TouchAction { NONE, TRIM_LEFT, TRIM_RIGHT, MOVE_BAR, SCRUB }
    private var touchAction = TouchAction.NONE
    private var activeTrackIndex = -1
    private var lastTouchX = 0f
    private var touchDownRangeLen = 0L

    private val trackPaddingH = 26f
    private val handleWidth = 34f
    private val minRangeMs = 400L

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val handleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D2C44")
    }
    private val handleIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val barLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#04101D")
        textSize = 24f
        isFakeBoldText = true
    }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA6C9")
        textSize = 21f
        textAlign = Paint.Align.CENTER
    }
    private val rulerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B6387")
        strokeWidth = 2f
    }
    private val filmstripBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B1424")
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B8BFF")
        strokeWidth = 5f
    }
    private val playheadGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400E5FF")
        strokeWidth = 14f
    }
    private val clipPath = Path()
    private val tempRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()

    fun setData(boxes: List<WatermarkBox>, selectedIndex: Int, durationMs: Long) {
        this.boxes = boxes
        this.selectedIndex = selectedIndex
        if (durationMs > 0) this.durationMs = durationMs
        invalidate()
    }

    fun setPlayhead(positionMs: Long) {
        playheadMs = positionMs.coerceIn(0L, max(1L, durationMs))
        invalidate()
    }

    fun setThumbnails(thumbs: List<Bitmap>) {
        thumbnails = thumbs
        invalidate()
    }

    private fun trackLeft(): Float = trackPaddingH
    private fun trackRight(): Float = (width - trackPaddingH).coerceAtLeast(trackLeft() + 10f)
    private fun trackWidth(): Float = (trackRight() - trackLeft()).coerceAtLeast(10f)

    private fun msToX(ms: Long): Float {
        val ratio = (ms.toFloat() / max(1L, durationMs).toFloat()).coerceIn(0f, 1f)
        return trackLeft() + ratio * trackWidth()
    }

    private fun xToMs(x: Float): Long {
        val ratio = ((x - trackLeft()) / trackWidth()).coerceIn(0f, 1f)
        return (ratio * durationMs).toLong()
    }

    private fun filmstripTop(): Float = height - 118f
    private fun filmstripBottom(): Float = height - 42f

    private fun trackRectForIndex(idx: Int): RectF {
        val availableH = (filmstripTop() - 18f).coerceAtLeast(44f)
        val count = boxes.size.coerceAtLeast(1)
        val gap = 8f
        val barH = ((availableH - gap * (count - 1)) / count).coerceIn(30f, 52f)
        val totalBarsH = barH * count + gap * (count - 1)
        val startY = (filmstripTop() - 12f - totalBarsH).coerceAtLeast(6f)
        val top = startY + idx * (barH + gap)
        val box = boxes[idx]
        val end = if (box.endMs > 0L) box.endMs else durationMs
        val x1 = msToX(box.startMs)
        val x2 = msToX(end).coerceAtLeast(x1 + 24f)
        return RectF(x1, top, x2, top + barH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val fTop = filmstripTop()
        val fBot = filmstripBottom()

        // 1. Filmstrip Thumbnails Bar
        tempRect.set(trackLeft(), fTop, trackRight(), fBot)
        canvas.drawRoundRect(tempRect, 12f, 12f, filmstripBgPaint)

        if (thumbnails.isNotEmpty()) {
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(tempRect, 12f, 12f, Path.Direction.CW)
            canvas.clipPath(clipPath)
            val segW = trackWidth() / thumbnails.size
            thumbnails.forEachIndexed { idx, bmp ->
                if (!bmp.isRecycled) {
                    val left = trackLeft() + idx * segW
                    dstRect.set(left, fTop, left + segW + 1f, fBot)
                    srcRect.set(0, 0, bmp.width, bmp.height)
                    canvas.drawBitmap(bmp, srcRect, dstRect, null)
                }
            }
            canvas.restore()
        }

        // 2. Time Ruler Ticks & Labels
        val rulerY = height - 10f
        val tickCount = 5
        for (i in 0 until tickCount) {
            val frac = i.toFloat() / (tickCount - 1)
            val x = trackLeft() + frac * trackWidth()
            val ms = (frac * durationMs).toLong()
            canvas.drawLine(x, fBot + 4f, x, fBot + 10f, rulerTickPaint)
            canvas.drawText(GalleryHelper.formatDuration(ms), x, rulerY, rulerTextPaint)
        }

        // 3. Multi-Track Draggable Watermark Bars
        boxes.forEachIndexed { idx, box ->
            val r = trackRectForIndex(idx)
            val isSelected = idx == selectedIndex
            val end = if (box.endMs > 0L) box.endMs else durationMs

            barPaint.color = if (isSelected) {
                box.color
            } else {
                Color.argb(145, Color.red(box.color), Color.green(box.color), Color.blue(box.color))
            }
            canvas.drawRoundRect(r, 12f, 12f, barPaint)

            if (isSelected) {
                canvas.drawRoundRect(r, 12f, 12f, barBorderPaint)

                // Left trim handle (<)
                val lh = RectF(r.left - 2f, r.top, (r.left + handleWidth).coerceAtMost(r.centerX()), r.bottom)
                canvas.drawRoundRect(lh, 10f, 10f, handleBgPaint)
                canvas.drawText("‹", lh.centerX(), lh.centerY() + 8f, handleIconPaint)

                // Right trim handle (>)
                val rh = RectF((r.right - handleWidth).coerceAtLeast(r.centerX()), r.top, r.right + 2f, r.bottom)
                canvas.drawRoundRect(rh, 10f, 10f, handleBgPaint)
                canvas.drawText("›", rh.centerX(), rh.centerY() + 8f, handleIconPaint)
            }

            // Track Label inside bar (#1 00:03–00:12)
            val label = "#${idx + 1}  ${GalleryHelper.formatDuration(box.startMs)}–${GalleryHelper.formatDuration(end)}"
            val textStart = if (isSelected) r.left + handleWidth + 10f else r.left + 12f
            val maxTextRight = if (isSelected) r.right - handleWidth - 8f else r.right - 8f
            if (maxTextRight - textStart > 40f) {
                canvas.save()
                canvas.clipRect(textStart, r.top, maxTextRight, r.bottom)
                canvas.drawText(label, textStart, r.centerY() + 8f, barLabelPaint)
                canvas.restore()
            }
        }

        // 4. Vertical Glowing Playhead Line
        val px = msToX(playheadMs)
        canvas.drawLine(px, 2f, px, fBot + 6f, playheadGlowPaint)
        canvas.drawLine(px, 2f, px, fBot + 6f, playheadPaint)
        canvas.drawCircle(px, 8f, 7f, playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0L) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                lastTouchX = x
                touchAction = TouchAction.NONE
                activeTrackIndex = -1

                // Check track bars first
                for (i in boxes.indices) {
                    val r = trackRectForIndex(i)
                    if (y >= r.top - 10f && y <= r.bottom + 10f) {
                        val hitPad = 36f
                        if (abs(x - r.left) <= hitPad) {
                            activeTrackIndex = i
                            touchAction = TouchAction.TRIM_LEFT
                        } else if (abs(x - r.right) <= hitPad) {
                            activeTrackIndex = i
                            touchAction = TouchAction.TRIM_RIGHT
                        } else if (x >= r.left && x <= r.right) {
                            activeTrackIndex = i
                            touchAction = TouchAction.MOVE_BAR
                            val b = boxes[i]
                            val end = if (b.endMs > 0L) b.endMs else durationMs
                            touchDownRangeLen = (end - b.startMs).coerceAtLeast(minRangeMs)
                        }
                        if (activeTrackIndex != -1) {
                            selectedIndex = activeTrackIndex
                            onBoxSelected?.invoke(activeTrackIndex)
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            parent?.requestDisallowInterceptTouchEvent(true)
                            invalidate()
                            return true
                        }
                    }
                }

                // Otherwise scrub playhead directly
                touchAction = TouchAction.SCRUB
                parent?.requestDisallowInterceptTouchEvent(true)
                val targetMs = xToMs(x)
                playheadMs = targetMs
                onScrubTo?.invoke(targetMs)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val x = event.x
                when (touchAction) {
                    TouchAction.SCRUB -> {
                        val targetMs = xToMs(x)
                        playheadMs = targetMs
                        onScrubTo?.invoke(targetMs)
                        invalidate()
                        return true
                    }
                    TouchAction.TRIM_LEFT -> {
                        val b = boxes.getOrNull(activeTrackIndex) ?: return false
                        val curEnd = if (b.endMs > 0L) b.endMs else durationMs
                        val newStart = xToMs(x).coerceIn(0L, max(0L, curEnd - minRangeMs))
                        b.startMs = newStart
                        playheadMs = newStart
                        onRangeChanged?.invoke(activeTrackIndex, b.startMs, curEnd)
                        onScrubTo?.invoke(newStart)
                        invalidate()
                        return true
                    }
                    TouchAction.TRIM_RIGHT -> {
                        val b = boxes.getOrNull(activeTrackIndex) ?: return false
                        val newEnd = xToMs(x).coerceIn(min(durationMs, b.startMs + minRangeMs), durationMs)
                        b.endMs = newEnd
                        playheadMs = newEnd
                        onRangeChanged?.invoke(activeTrackIndex, b.startMs, newEnd)
                        onScrubTo?.invoke(newEnd)
                        invalidate()
                        return true
                    }
                    TouchAction.MOVE_BAR -> {
                        val b = boxes.getOrNull(activeTrackIndex) ?: return false
                        val deltaMs = (((x - lastTouchX) / trackWidth()) * durationMs).toLong()
                        lastTouchX = x
                        val newStart = (b.startMs + deltaMs).coerceIn(0L, max(0L, durationMs - touchDownRangeLen))
                        val newEnd = (newStart + touchDownRangeLen).coerceAtMost(durationMs)
                        b.startMs = newStart
                        b.endMs = newEnd
                        onRangeChanged?.invoke(activeTrackIndex, newStart, newEnd)
                        invalidate()
                        return true
                    }
                    TouchAction.NONE -> return false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchAction = TouchAction.NONE
                activeTrackIndex = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
