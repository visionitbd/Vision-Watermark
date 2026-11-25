package com.visionit.watermark.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.visionit.watermark.model.BoxColors
import com.visionit.watermark.model.RemovalMode
import com.visionit.watermark.model.WatermarkBox
import kotlin.math.abs
import kotlin.math.max

class WatermarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val videoRect = RectF()
    private val boxes = mutableListOf<WatermarkBox>()
    private var nextId = 1
    private var totalDurationMs = 10_000L
    private var currentPlayheadMs = 0L

    var selectedBoxIndex: Int = 0
        private set

    var mode: RemovalMode = RemovalMode.SMART_ERASE
        set(value) {
            field = value
            if (value == RemovalMode.CROP && boxes.size > 1) {
                val active = getSelectedBox()
                boxes.clear()
                if (active != null) boxes.add(active)
                selectedBoxIndex = 0
            }
            if (value == RemovalMode.CROP && boxes.size == 1) {
                val b = boxes[0]
                if ((b.right - b.left) < 0.45f && (b.bottom - b.top) < 0.45f) {
                    b.left = 0.05f
                    b.top = 0.05f
                    b.right = 0.95f
                    b.bottom = 0.85f
                }
            }
            onBoxesChanged?.invoke()
            invalidate()
        }

    var onBoxesChanged: (() -> Unit)? = null

    private var videoWidth = 16
    private var videoHeight = 9

    private enum class DragMode { NONE, MOVE, TL, TR, BL, BR }
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val handleHitRadius = 56f
    private val minNormSize = 0.06f

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val inactiveDashEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val cropDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val handleInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#071324")
        style = Paint.Style.FILL
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#04101D")
        textSize = 26f
        isFakeBoldText = true
    }

    init {
        addDefaultBox(0L, 10_000L)
    }

    fun setVideoDuration(durationMs: Long) {
        if (durationMs > 0) {
            val oldDur = totalDurationMs
            totalDurationMs = durationMs
            boxes.forEach { b ->
                if (b.endMs <= 0L || b.endMs == oldDur || b.endMs > durationMs) {
                    b.endMs = durationMs
                }
                b.startMs = b.startMs.coerceIn(0L, max(0L, b.endMs - 300L))
            }
            onBoxesChanged?.invoke()
            invalidate()
        }
    }

    fun setCurrentPlayhead(positionMs: Long) {
        currentPlayheadMs = positionMs
        invalidate()
    }

    fun setVideoDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
            updateVideoRect()
            invalidate()
        }
    }

    fun selectBox(index: Int) {
        if (index in boxes.indices) {
            selectedBoxIndex = index
            onBoxesChanged?.invoke()
            invalidate()
        }
    }

    fun addDefaultBox(startMs: Long = currentPlayheadMs, endMs: Long = totalDurationMs) {
        val color = BoxColors.colorForIndex(nextId - 1)
        val offset = (boxes.size % 4) * 0.06f
        val safeStart = startMs.coerceIn(0L, max(0L, totalDurationMs - 500L))
        val safeEnd = if (endMs > safeStart + 300L) endMs.coerceAtMost(totalDurationMs) else totalDurationMs
        val box = WatermarkBox(
            id = nextId++,
            left = (0.55f - offset).coerceIn(0.08f, 0.62f),
            top = (0.68f - offset).coerceIn(0.08f, 0.72f),
            right = (0.90f - offset).coerceIn(0.32f, 0.94f),
            bottom = (0.84f - offset).coerceIn(0.25f, 0.92f),
            color = color,
            startMs = safeStart,
            endMs = safeEnd
        )
        boxes.add(box)
        selectedBoxIndex = boxes.lastIndex
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onBoxesChanged?.invoke()
        invalidate()
    }

    fun removeBoxAt(index: Int): Boolean {
        if (boxes.size <= 1 || index !in boxes.indices) return false
        boxes.removeAt(index)
        selectedBoxIndex = selectedBoxIndex.coerceIn(0, boxes.lastIndex)
        onBoxesChanged?.invoke()
        invalidate()
        return true
    }

    fun getBoxes(): List<WatermarkBox> = boxes

    fun getSelectedBox(): WatermarkBox? = boxes.getOrNull(selectedBoxIndex)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateVideoRect()
    }

    private fun updateVideoRect() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val viewRatio = vw / vh
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        if (videoRatio > viewRatio) {
            val renderedH = vw / videoRatio
            val top = (vh - renderedH) / 2f
            videoRect.set(0f, top, vw, top + renderedH)
        } else {
            val renderedW = vh * videoRatio
            val left = (vw - renderedW) / 2f
            videoRect.set(left, 0f, left + renderedW, vh)
        }
    }

    private fun boxToScreenRect(box: WatermarkBox): RectF {
        return RectF(
            videoRect.left + box.left * videoRect.width(),
            videoRect.top + box.top * videoRect.height(),
            videoRect.left + box.right * videoRect.width(),
            videoRect.top + box.bottom * videoRect.height()
        )
    }

    private fun isBoxActiveAtPlayhead(box: WatermarkBox): Boolean {
        if (mode == RemovalMode.CROP) return true
        val end = if (box.endMs > 0L) box.endMs else totalDurationMs
        return currentPlayheadMs in box.startMs..end
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoRect.width() <= 0f || videoRect.height() <= 0f) return

        if (mode == RemovalMode.CROP && boxes.isNotEmpty()) {
            val r = boxToScreenRect(boxes[0])
            canvas.drawRect(videoRect.left, videoRect.top, videoRect.right, r.top, cropDimPaint)
            canvas.drawRect(videoRect.left, r.bottom, videoRect.right, videoRect.bottom, cropDimPaint)
            canvas.drawRect(videoRect.left, r.top, r.left, r.bottom, cropDimPaint)
            canvas.drawRect(r.right, r.top, videoRect.right, r.bottom, cropDimPaint)
        }

        boxes.forEachIndexed { index, box ->
            val r = boxToScreenRect(box)
            val isSelected = index == selectedBoxIndex
            val isActiveNow = isBoxActiveAtPlayhead(box)

            // Selected box is always editable; non-selected outside time window is faint
            val alphaFactor = when {
                isSelected && isActiveNow -> 255
                isSelected -> 170
                isActiveNow -> 200
                else -> 65
            }

            if (mode != RemovalMode.CROP) {
                boxFillPaint.color = Color.argb((alphaFactor * 0.16f).toInt(), Color.red(box.color), Color.green(box.color), Color.blue(box.color))
                canvas.drawRect(r, boxFillPaint)
            }

            borderPaint.color = Color.argb(alphaFactor, Color.red(box.color), Color.green(box.color), Color.blue(box.color))
            borderPaint.strokeWidth = if (isSelected) 6f else 4f
            borderPaint.pathEffect = if (isActiveNow) null else inactiveDashEffect
            canvas.drawRect(r, borderPaint)

            if (isSelected) {
                handlePaint.color = box.color
                drawHandle(canvas, r.left, r.top)
                drawHandle(canvas, r.right, r.top)
                drawHandle(canvas, r.left, r.bottom)
                drawHandle(canvas, r.right, r.bottom)
            }

            if (mode != RemovalMode.CROP) {
                val label = "#${index + 1}"
                val badgeW = 52f
                val badgeH = 32f
                val bx = r.left
                val by = (r.top - badgeH - 4f).coerceAtLeast(videoRect.top + 4f)
                badgeBgPaint.color = Color.argb(alphaFactor, Color.red(box.color), Color.green(box.color), Color.blue(box.color))
                canvas.drawRoundRect(bx, by, bx + badgeW, by + badgeH, 8f, 8f, badgeBgPaint)
                canvas.drawText(label, bx + 11f, by + 23f, badgeTextPaint)
            }
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawRoundRect(cx - 13f, cy - 13f, cx + 13f, cy + 13f, 6f, 6f, handlePaint)
        canvas.drawRoundRect(cx - 5f, cy - 5f, cx + 5f, cy + 5f, 3f, 3f, handleInnerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (videoRect.width() <= 0f || videoRect.height() <= 0f) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                lastTouchX = x
                lastTouchY = y
                dragMode = DragMode.NONE

                val activeBox = getSelectedBox()
                if (activeBox != null) {
                    val r = boxToScreenRect(activeBox)
                    dragMode = hitTestHandle(x, y, r)
                    if (dragMode != DragMode.NONE) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                for (i in boxes.indices.reversed()) {
                    val r = boxToScreenRect(boxes[i])
                    if (r.contains(x, y)) {
                        selectedBoxIndex = i
                        dragMode = DragMode.MOVE
                        onBoxesChanged?.invoke()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val box = getSelectedBox() ?: return false
                if (dragMode == DragMode.NONE) return false

                val dx = (event.x - lastTouchX) / videoRect.width()
                val dy = (event.y - lastTouchY) / videoRect.height()
                lastTouchX = event.x
                lastTouchY = event.y

                when (dragMode) {
                    DragMode.MOVE -> {
                        val w = box.right - box.left
                        val h = box.bottom - box.top
                        val newL = (box.left + dx).coerceIn(0f, 1f - w)
                        val newT = (box.top + dy).coerceIn(0f, 1f - h)
                        box.left = newL
                        box.top = newT
                        box.right = newL + w
                        box.bottom = newT + h
                    }
                    DragMode.TL -> {
                        box.left = (box.left + dx).coerceIn(0f, box.right - minNormSize)
                        box.top = (box.top + dy).coerceIn(0f, box.bottom - minNormSize)
                    }
                    DragMode.TR -> {
                        box.right = (box.right + dx).coerceIn(box.left + minNormSize, 1f)
                        box.top = (box.top + dy).coerceIn(0f, box.bottom - minNormSize)
                    }
                    DragMode.BL -> {
                        box.left = (box.left + dx).coerceIn(0f, box.right - minNormSize)
                        box.bottom = (box.bottom + dy).coerceIn(box.top + minNormSize, 1f)
                    }
                    DragMode.BR -> {
                        box.right = (box.right + dx).coerceIn(box.left + minNormSize, 1f)
                        box.bottom = (box.bottom + dy).coerceIn(box.top + minNormSize, 1f)
                    }
                    DragMode.NONE -> Unit
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTestHandle(x: Float, y: Float, r: RectF): DragMode {
        return when {
            abs(x - r.left) <= handleHitRadius && abs(y - r.top) <= handleHitRadius -> DragMode.TL
            abs(x - r.right) <= handleHitRadius && abs(y - r.top) <= handleHitRadius -> DragMode.TR
            abs(x - r.left) <= handleHitRadius && abs(y - r.bottom) <= handleHitRadius -> DragMode.BL
            abs(x - r.right) <= handleHitRadius && abs(y - r.bottom) <= handleHitRadius -> DragMode.BR
            else -> DragMode.NONE
        }
    }

    fun computePixelBoxes(actualVideoW: Int, actualVideoH: Int): List<PixelBox> {
        return boxes.map { b ->
            val rawX = (b.left * actualVideoW).toInt()
            val rawY = (b.top * actualVideoH).toInt()
            val rawW = ((b.right - b.left) * actualVideoW).toInt()
            val rawH = ((b.bottom - b.top) * actualVideoH).toInt()

            val safeX = rawX.coerceIn(2, max(2, actualVideoW - 12))
            val safeY = rawY.coerceIn(2, max(2, actualVideoH - 12))
            val safeW = rawW.coerceIn(8, max(8, actualVideoW - safeX - 2))
            val safeH = rawH.coerceIn(8, max(8, actualVideoH - safeY - 2))

            val fullEnd = if (b.endMs > 0L) b.endMs else totalDurationMs
            val isFullVideo = b.startMs <= 100L && fullEnd >= totalDurationMs - 150L

            PixelBox(
                x = safeX,
                y = safeY,
                w = safeW,
                h = safeH,
                useTimeRange = !isFullVideo,
                startSec = b.startMs / 1000f,
                endSec = fullEnd / 1000f
            )
        }
    }

    data class PixelBox(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val useTimeRange: Boolean,
        val startSec: Float,
        val endSec: Float
    )
}
