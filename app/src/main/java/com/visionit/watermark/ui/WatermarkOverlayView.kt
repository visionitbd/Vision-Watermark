package com.visionit.watermark.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
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
    private val undoStack = ArrayDeque<Pair<List<WatermarkBox>, Int>>()
    private var nextId = 1
    private var totalDurationMs = 10_000L
    private var currentPlayheadMs = 0L

    var selectedBoxIndex: Int = 0
        private set

    var mode: RemovalMode = RemovalMode.SMART_ERASE
        set(value) {
            field = value
            if (value == RemovalMode.CROP && boxes.size > 1) {
                pushUndoSnapshot()
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

    // Live Preview Overlay (for Add Text / Logo mode)
    var isAddOverlayMode: Boolean = false
    var previewText: String = ""
        set(value) { field = value; invalidate() }
    var previewTypeface: Typeface = Typeface.DEFAULT_BOLD
        set(value) { field = value; invalidate() }
    var previewTextColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }
    var previewLogoBitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    private val liveTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var videoWidth = 16
    private var videoHeight = 9

    private enum class DragMode { NONE, MOVE, TL, TR, BL, BR, TOP, BOTTOM, LEFT, RIGHT }
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val cornerHitRadius = 46f
    private val edgeHitRadius = 30f
    // Allow ultra-slim horizontal/vertical boxes for tiny text watermarks
    private val minNormW = 0.03f
    private val minNormH = 0.015f

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val inactiveDashEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)

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
        textSize = 22f
        isFakeBoldText = true
    }

    init {
        addDefaultBox(0L, 10_000L, recordUndo = false)
    }

    private fun pushUndoSnapshot() {
        val copy = boxes.map { it.copy() }
        if (undoStack.size >= 25) undoStack.removeFirst()
        undoStack.addLast(copy to selectedBoxIndex)
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val (prevBoxes, prevSelected) = undoStack.removeLast()
        boxes.clear()
        boxes.addAll(prevBoxes.map { it.copy() })
        selectedBoxIndex = prevSelected.coerceIn(0, (boxes.size - 1).coerceAtLeast(0))
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onBoxesChanged?.invoke()
        invalidate()
        return true
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

    fun addDefaultBox(
        startMs: Long = currentPlayheadMs,
        endMs: Long = totalDurationMs,
        recordUndo: Boolean = true
    ) {
        if (recordUndo) pushUndoSnapshot()
        val color = BoxColors.colorForIndex(nextId - 1)
        val offset = (boxes.size % 3) * 0.08f
        val safeStart = startMs.coerceIn(0L, max(0L, totalDurationMs - 500L))
        val safeEnd = if (endMs > safeStart + 300L) endMs.coerceAtMost(totalDurationMs) else totalDurationMs
        val box = WatermarkBox(
            id = nextId++,
            left = (0.56f - offset).coerceIn(0.08f, 0.65f),
            top = (0.76f - offset).coerceIn(0.10f, 0.82f),
            right = (0.92f - offset).coerceIn(0.32f, 0.94f),
            bottom = (0.84f - offset).coerceIn(0.16f, 0.92f),
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
        if (index !in boxes.indices) return false
        pushUndoSnapshot()
        if (boxes.size <= 1) {
            // Reset single box cleanly to default slim position
            val b = boxes[0]
            b.left = 0.56f
            b.top = 0.76f
            b.right = 0.92f
            b.bottom = 0.84f
            b.startMs = 0L
            b.endMs = totalDurationMs
            onBoxesChanged?.invoke()
            invalidate()
            return true
        }
        boxes.removeAt(index)
        selectedBoxIndex = selectedBoxIndex.coerceIn(0, boxes.lastIndex)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onBoxesChanged?.invoke()
        invalidate()
        return true
    }


    /**
     * CapCut-style instant Split at current playhead timestamp!
     * Finds the clip containing playheadMs and splits it into two clean adjacent clips.
     */
    fun splitClipAt(playheadMs: Long, maxClips: Int = 5): Boolean {
        if (boxes.size >= maxClips) return false
        val targetIdx = boxes.indexOfFirst { b ->
            val end = if (b.endMs > b.startMs) b.endMs else totalDurationMs
            playheadMs > b.startMs + 400L && playheadMs < end - 400L
        }
        if (targetIdx == -1) return false

        pushUndoSnapshot()
        val orig = boxes[targetIdx]
        val origEnd = if (orig.endMs > orig.startMs) orig.endMs else totalDurationMs
        orig.endMs = playheadMs

        val rightClip = WatermarkBox(
            id = nextId++,
            left = orig.left,
            top = orig.top,
            right = orig.right,
            bottom = orig.bottom,
            color = BoxColors.colorForIndex(nextId - 1),
            startMs = playheadMs,
            endMs = origEnd
        )
        boxes.add(targetIdx + 1, rightClip)
        selectedBoxIndex = targetIdx + 1
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onBoxesChanged?.invoke()
        invalidate()
        return true
    }

    fun resetToSingleFullClip() {
        pushUndoSnapshot()
        boxes.clear()
        addDefaultBox(0L, totalDurationMs, recordUndo = false)
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

            val alphaFactor = when {
                isSelected && isActiveNow -> 255
                isSelected -> 175
                isActiveNow -> 200
                else -> 65
            }

            if (isAddOverlayMode && index == 0) {
                // Draw live Logo or live Styled Text directly inside the draggable box!
                val logo = previewLogoBitmap
                if (logo != null && !logo.isRecycled) {
                    val dst = Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                    canvas.drawBitmap(logo, null, dst, null)
                } else if (previewText.isNotBlank()) {
                    liveTextPaint.color = previewTextColor
                    liveTextPaint.typeface = previewTypeface
                    liveTextPaint.setShadowLayer(6f, 2f, 2f, Color.argb(200, 0, 0, 0))
                    var sz = (r.height() * 0.62f).coerceAtLeast(18f)
                    liveTextPaint.textSize = sz
                    val measured = liveTextPaint.measureText(previewText)
                    val availW = (r.width() - 12f).coerceAtLeast(24f)
                    if (measured > availW && measured > 0f) {
                        sz *= (availW / measured)
                        liveTextPaint.textSize = sz.coerceAtLeast(12f)
                    }
                    val fm = liveTextPaint.fontMetrics
                    val baseY = r.centerY() - (fm.ascent + fm.descent) / 2f
                    canvas.drawText(previewText, r.centerX(), baseY, liveTextPaint)
                }
            } else if (mode != RemovalMode.CROP) {
                boxFillPaint.color = Color.argb((alphaFactor * 0.14f).toInt(), Color.red(box.color), Color.green(box.color), Color.blue(box.color))
                canvas.drawRect(r, boxFillPaint)
            }

            borderPaint.color = Color.argb(alphaFactor, Color.red(box.color), Color.green(box.color), Color.blue(box.color))
            borderPaint.strokeWidth = if (isSelected) 4.2f else 3f
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
                val badgeW = 42f
                val badgeH = 26f
                val bx = r.left
                val by = (r.top - badgeH - 4f).coerceAtLeast(videoRect.top + 4f)
                badgeBgPaint.color = Color.argb(alphaFactor, Color.red(box.color), Color.green(box.color), Color.blue(box.color))
                canvas.drawRoundRect(bx, by, bx + badgeW, by + badgeH, 7f, 7f, badgeBgPaint)
                canvas.drawText(label, bx + 8f, by + 19f, badgeTextPaint)
            }
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        // Sleek compact handles that never obscure thin text watermarks
        canvas.drawCircle(cx, cy, 8.5f, handlePaint)
        canvas.drawCircle(cx, cy, 3.5f, handleInnerPaint)
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
                    dragMode = hitTestHandleOrEdge(x, y, r)
                    if (dragMode != DragMode.NONE) {
                        pushUndoSnapshot()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                for (i in boxes.indices.reversed()) {
                    val r = boxToScreenRect(boxes[i])
                    val expanded = RectF(r.left - 14f, r.top - 14f, r.right + 14f, r.bottom + 14f)
                    if (expanded.contains(x, y)) {
                        pushUndoSnapshot()
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
                        box.left = (box.left + dx).coerceIn(0f, box.right - minNormW)
                        box.top = (box.top + dy).coerceIn(0f, box.bottom - minNormH)
                    }
                    DragMode.TR -> {
                        box.right = (box.right + dx).coerceIn(box.left + minNormW, 1f)
                        box.top = (box.top + dy).coerceIn(0f, box.bottom - minNormH)
                    }
                    DragMode.BL -> {
                        box.left = (box.left + dx).coerceIn(0f, box.right - minNormW)
                        box.bottom = (box.bottom + dy).coerceIn(box.top + minNormH, 1f)
                    }
                    DragMode.BR -> {
                        box.right = (box.right + dx).coerceIn(box.left + minNormW, 1f)
                        box.bottom = (box.bottom + dy).coerceIn(box.top + minNormH, 1f)
                    }
                    DragMode.TOP -> {
                        box.top = (box.top + dy).coerceIn(0f, box.bottom - minNormH)
                    }
                    DragMode.BOTTOM -> {
                        box.bottom = (box.bottom + dy).coerceIn(box.top + minNormH, 1f)
                    }
                    DragMode.LEFT -> {
                        box.left = (box.left + dx).coerceIn(0f, box.right - minNormW)
                    }
                    DragMode.RIGHT -> {
                        box.right = (box.right + dx).coerceIn(box.left + minNormW, 1f)
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

    private fun hitTestHandleOrEdge(x: Float, y: Float, r: RectF): DragMode {
        // Corners first
        if (abs(x - r.left) <= cornerHitRadius && abs(y - r.top) <= cornerHitRadius) return DragMode.TL
        if (abs(x - r.right) <= cornerHitRadius && abs(y - r.top) <= cornerHitRadius) return DragMode.TR
        if (abs(x - r.left) <= cornerHitRadius && abs(y - r.bottom) <= cornerHitRadius) return DragMode.BL
        if (abs(x - r.right) <= cornerHitRadius && abs(y - r.bottom) <= cornerHitRadius) return DragMode.BR

        // Edges for effortless thin vertical/horizontal adjustment
        if (x in r.left..r.right && abs(y - r.top) <= edgeHitRadius) return DragMode.TOP
        if (x in r.left..r.right && abs(y - r.bottom) <= edgeHitRadius) return DragMode.BOTTOM
        if (y in r.top..r.bottom && abs(x - r.left) <= edgeHitRadius) return DragMode.LEFT
        if (y in r.top..r.bottom && abs(x - r.right) <= edgeHitRadius) return DragMode.RIGHT

        return DragMode.NONE
    }

    fun computePixelBoxes(actualVideoW: Int, actualVideoH: Int): List<PixelBox> {
        return boxes.map { b ->
            val rawX = (b.left * actualVideoW).toInt()
            val rawY = (b.top * actualVideoH).toInt()
            val rawW = ((b.right - b.left) * actualVideoW).toInt()
            val rawH = ((b.bottom - b.top) * actualVideoH).toInt()

            val safeX = rawX.coerceIn(2, max(2, actualVideoW - 10))
            val safeY = rawY.coerceIn(2, max(2, actualVideoH - 10))
            val safeW = rawW.coerceIn(6, max(6, actualVideoW - safeX - 2))
            val safeH = rawH.coerceIn(6, max(6, actualVideoH - safeY - 2))

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
