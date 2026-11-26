package com.visionit.watermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.visionit.watermark.databinding.ActivityPhotoEditorBinding
import com.visionit.watermark.model.RemovalMode
import com.visionit.watermark.ui.DetectedTextOverlayView
import com.visionit.watermark.util.GalleryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHOTO_URI = "extra_photo_uri"
        const val EXTRA_INITIAL_MODE = "extra_initial_mode"
        private const val MAX_BOXES = 3
    }

    private lateinit var binding: ActivityPhotoEditorBinding
    private var currentBitmap: Bitmap? = null
    private val bitmapUndoStack = ArrayDeque<Bitmap>()
    private var currentMode = RemovalMode.SMART_ERASE
    private var isEditTextMode = false
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_PHOTO_URI)
        if (uriStr.isNullOrBlank()) {
            finish()
            return
        }

        setupControls()
        if (intent.getStringExtra(EXTRA_INITIAL_MODE) == "EDIT_TEXT") {
            enterEditTextMode()
        }
        loadPhoto(Uri.parse(uriStr))
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.photoOverlay.onBoxesChanged = {
            binding.tvAreaCount.text = "${binding.photoOverlay.getBoxes().size}/$MAX_BOXES"
        }

        binding.textOverlay.onTextBlockTapped = { item ->
            showReplaceTextDialog(item.text, item.box)
        }

        binding.btnAddArea.setOnClickListener {
            if (isEditTextMode) {
                // Allow manual box replace for non-English / Bengali / stylized text!
                val bmp = currentBitmap ?: return@setOnClickListener
                val boxes = binding.photoOverlay.computePixelBoxes(bmp.width, bmp.height)
                if (boxes.isNotEmpty()) {
                    val b = boxes.first()
                    showReplaceTextDialog("", Rect(b.x, b.y, b.x + b.w, b.y + b.h))
                }
                return@setOnClickListener
            }
            if (currentMode == RemovalMode.CROP) {
                Toast.makeText(this, "Crop mode uses 1 frame", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (binding.photoOverlay.getBoxes().size >= MAX_BOXES) {
                Toast.makeText(this, "Maximum $MAX_BOXES areas allowed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.photoOverlay.addDefaultBox()
        }

        binding.btnDeleteBox.setOnClickListener {
            binding.photoOverlay.removeBoxAt(binding.photoOverlay.selectedBoxIndex)
        }

        binding.btnUndo.setOnClickListener {
            if (bitmapUndoStack.isNotEmpty()) {
                currentBitmap = bitmapUndoStack.removeLast()
                binding.ivPhotoPreview.setImageBitmap(currentBitmap)
                currentBitmap?.let { bmp ->
                    binding.photoOverlay.setVideoDimensions(bmp.width, bmp.height)
                    binding.textOverlay.setImageSize(bmp.width, bmp.height)
                    binding.tvPhotoMeta.text = "${bmp.width}×${bmp.height}"
                    if (isEditTextMode) scanTextInPhoto(bmp)
                }
            } else if (!binding.photoOverlay.undo()) {
                Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            }
        }

        binding.chipSmartErase.setOnClickListener { selectBoxMode(RemovalMode.SMART_ERASE) }
        binding.chipEditText.setOnClickListener { enterEditTextMode() }
        binding.chipBlur.setOnClickListener { selectBoxMode(RemovalMode.BLUR) }
        binding.chipCrop.setOnClickListener { selectBoxMode(RemovalMode.CROP) }

        binding.btnApplyErase.setOnClickListener {
            if (isEditTextMode) {
                // Re-scan text on photo
                currentBitmap?.let { scanTextInPhoto(it) }
            } else {
                applyPhotoEdit()
            }
        }

        binding.btnSavePhoto.setOnClickListener {
            val bmp = currentBitmap ?: return@setOnClickListener
            lifecycleScope.launch {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val uri = withContext(Dispatchers.IO) {
                    GalleryHelper.savePhotoToGallery(this@PhotoEditorActivity, bmp, "VWatermark_$stamp.jpg")
                }
                if (uri != null) {
                    Toast.makeText(this@PhotoEditorActivity, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@PhotoEditorActivity, "Could not save photo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun styleModeChip(tv: TextView, active: Boolean) {
        tv.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        tv.setTextColor(if (active) Color.parseColor("#04101D") else Color.parseColor("#F4F8FF"))
    }

    private fun selectBoxMode(mode: RemovalMode) {
        isEditTextMode = false
        currentMode = mode
        binding.photoOverlay.mode = mode
        binding.photoOverlay.visibility = View.VISIBLE
        binding.textOverlay.visibility = View.GONE
        binding.tvEditTextHint.visibility = View.GONE
        binding.btnDeleteBox.visibility = View.VISIBLE
        binding.tvAreaCount.visibility = View.VISIBLE
        binding.btnAddArea.text = getString(R.string.add_area)
        binding.btnApplyErase.text = if (mode == RemovalMode.CROP) "Crop Now" else "Erase Now"

        styleModeChip(binding.chipSmartErase, mode == RemovalMode.SMART_ERASE)
        styleModeChip(binding.chipEditText, false)
        styleModeChip(binding.chipBlur, mode == RemovalMode.BLUR)
        styleModeChip(binding.chipCrop, mode == RemovalMode.CROP)
    }

    private fun enterEditTextMode() {
        isEditTextMode = true
        binding.photoOverlay.visibility = View.GONE
        binding.textOverlay.visibility = View.VISIBLE
        binding.tvEditTextHint.visibility = View.VISIBLE
        binding.tvEditTextHint.text = "Scanning text on photo…"
        binding.btnDeleteBox.visibility = View.GONE
        binding.tvAreaCount.visibility = View.GONE
        binding.btnAddArea.text = "Manual Box"
        binding.btnApplyErase.text = "Re-Scan"

        styleModeChip(binding.chipSmartErase, false)
        styleModeChip(binding.chipEditText, true)
        styleModeChip(binding.chipBlur, false)
        styleModeChip(binding.chipCrop, false)

        currentBitmap?.let { scanTextInPhoto(it) }
    }

    private fun scanTextInPhoto(bmp: Bitmap) {
        binding.textOverlay.setImageSize(bmp.width, bmp.height)
        val image = InputImage.fromBitmap(bmp, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val detected = mutableListOf<DetectedTextOverlayView.TextBlockItem>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox
                        if (box != null && line.text.isNotBlank()) {
                            detected.add(DetectedTextOverlayView.TextBlockItem(line.text, box))
                        }
                    }
                }
                binding.textOverlay.setDetectedTexts(detected)
                binding.tvEditTextHint.text = if (detected.isEmpty()) {
                    "No Latin text auto-detected. Tap 'Manual Box' to edit any text."
                } else {
                    "Found ${detected.size} text line${if (detected.size > 1) "s" else ""} — tap any highlighted text to edit"
                }
            }
            .addOnFailureListener {
                binding.tvEditTextHint.text = "Tap 'Manual Box' to select & replace text"
            }
    }

    private fun showReplaceTextDialog(initialText: String, targetBox: Rect) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val input = EditText(this).apply {
            setText(initialText)
            setSelection(initialText.length)
            hint = "Enter new text (leave empty to erase)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A8C9"))
        }
        container.addView(input)

        val colorGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 20, 0, 8)
        }
        val colors = listOf(
            "Auto" to 0,
            "White" to Color.WHITE,
            "Black" to Color.BLACK,
            "Yellow" to Color.parseColor("#FFD600"),
            "Cyan" to Color.parseColor("#00E5FF")
        )
        colors.forEachIndexed { idx, (label, colorVal) ->
            val rb = RadioButton(this).apply {
                text = label
                tag = colorVal
                id = View.generateViewId()
                textSize = 12f
                setTextColor(Color.WHITE)
                isChecked = idx == 0
            }
            colorGroup.addView(rb)
        }
        container.addView(colorGroup)

        val boldCheck = CheckBox(this).apply {
            text = "Bold text"
            isChecked = true
            setTextColor(Color.WHITE)
        }
        container.addView(boldCheck)

        AlertDialog.Builder(this)
            .setTitle("Edit Photo Text")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val newText = input.text.toString()
                val checkedId = colorGroup.checkedRadioButtonId
                val selectedBtn = colorGroup.findViewById<RadioButton>(checkedId)
                val chosenColor = (selectedBtn?.tag as? Int) ?: 0
                replaceTextInPhoto(targetBox, newText, chosenColor, boldCheck.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun replaceTextInPhoto(box: Rect, newText: String, chosenColor: Int, isBold: Boolean) {
        val src = currentBitmap ?: return
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.Default) {
                val out = src.copy(Bitmap.Config.ARGB_8888, true)

                // Sample dominant foreground text color BEFORE erasing if Auto selected
                val textColor = if (chosenColor != 0) chosenColor else sampleTextColor(out, box)

                // 1. Smart-erase original text cleanly
                val pad = 4
                val rx = (box.left - pad).coerceAtLeast(1)
                val ry = (box.top - pad).coerceAtLeast(1)
                val rw = (box.width() + pad * 2).coerceAtMost(out.width - rx - 2)
                val rh = (box.height() + pad * 2).coerceAtMost(out.height - ry - 2)
                if (rw > 2 && rh > 2) {
                    delogoRegion(out, rx, ry, rw, rh)
                }

                // 2. Render new edited text cleanly fitted inside the original bounding box
                if (newText.isNotBlank()) {
                    val canvas = Canvas(out)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = textColor
                        typeface = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        textAlign = Paint.Align.CENTER
                    }
                    // Auto-fit font size to box height & width
                    var textSizePx = (box.height() * 0.88f).coerceAtLeast(16f)
                    paint.textSize = textSizePx
                    val measuredW = paint.measureText(newText)
                    val maxAllowedW = (box.width() * 1.15f).coerceAtLeast(40f)
                    if (measuredW > maxAllowedW) {
                        textSizePx *= (maxAllowedW / measuredW)
                        paint.textSize = textSizePx.coerceAtLeast(12f)
                    }
                    val fm = paint.fontMetrics
                    val baselineY = box.exactCenterY() - (fm.ascent + fm.descent) / 2f
                    canvas.drawText(newText, box.exactCenterX(), baselineY, paint)
                }
                out
            }
            if (bitmapUndoStack.size >= 10) bitmapUndoStack.removeFirst()
            bitmapUndoStack.addLast(src)
            currentBitmap = updated
            binding.ivPhotoPreview.setImageBitmap(updated)
            scanTextInPhoto(updated)
            Toast.makeText(this@PhotoEditorActivity, "Text updated! Tap Save when done.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sampleTextColor(bmp: Bitmap, box: Rect): Int {
        // Compare border brightness vs interior extrema to pick clean contrasting text color
        val cx = box.centerX().coerceIn(0, bmp.width - 1)
        val topY = (box.top - 3).coerceIn(0, bmp.height - 1)
        val bgPx = bmp.getPixel(cx, topY)
        val bgLum = 0.299 * Color.red(bgPx) + 0.587 * Color.green(bgPx) + 0.114 * Color.blue(bgPx)

        var bestPx = if (bgLum > 128) Color.BLACK else Color.WHITE
        var maxDiff = 0.0
        val stepX = (box.width() / 10).coerceAtLeast(1)
        val stepY = (box.height() / 6).coerceAtLeast(1)
        var y = box.top.coerceAtLeast(0)
        while (y < box.bottom.coerceAtMost(bmp.height)) {
            var x = box.left.coerceAtLeast(0)
            while (x < box.right.coerceAtMost(bmp.width)) {
                val px = bmp.getPixel(x, y)
                val lum = 0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px)
                val diff = kotlin.math.abs(lum - bgLum)
                if (diff > maxDiff) {
                    maxDiff = diff
                    bestPx = px
                }
                x += stepX
            }
            y += stepY
        }
        return bestPx
    }

    private fun loadPhoto(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.copy(Bitmap.Config.ARGB_8888, true)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (bmp == null) {
                Toast.makeText(this@PhotoEditorActivity, "Could not load photo", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            currentBitmap = bmp
            binding.ivPhotoPreview.setImageBitmap(bmp)
            binding.photoOverlay.setVideoDimensions(bmp.width, bmp.height)
            binding.textOverlay.setImageSize(bmp.width, bmp.height)
            binding.tvPhotoMeta.text = "${bmp.width}×${bmp.height}"
            if (isEditTextMode) {
                scanTextInPhoto(bmp)
            }
        }
    }

    private fun applyPhotoEdit() {
        val src = currentBitmap ?: return
        val boxes = binding.photoOverlay.computePixelBoxes(src.width, src.height)
        if (boxes.isEmpty()) return

        lifecycleScope.launch {
            val updated = withContext(Dispatchers.Default) {
                when (currentMode) {
                    RemovalMode.CROP -> {
                        val b = boxes.first()
                        val w = b.w.coerceAtMost(src.width - b.x)
                        val h = b.h.coerceAtMost(src.height - b.y)
                        Bitmap.createBitmap(src, b.x, b.y, w, h).copy(Bitmap.Config.ARGB_8888, true)
                    }
                    RemovalMode.SMART_ERASE -> {
                        val out = src.copy(Bitmap.Config.ARGB_8888, true)
                        boxes.forEach { b -> delogoRegion(out, b.x, b.y, b.w, b.h) }
                        out
                    }
                    RemovalMode.BLUR -> {
                        val out = src.copy(Bitmap.Config.ARGB_8888, true)
                        boxes.forEach { b -> blurRegion(out, b.x, b.y, b.w, b.h, radius = 10) }
                        out
                    }
                }
            }
            if (bitmapUndoStack.size >= 10) bitmapUndoStack.removeFirst()
            bitmapUndoStack.addLast(src)
            currentBitmap = updated
            binding.ivPhotoPreview.setImageBitmap(updated)
            binding.photoOverlay.setVideoDimensions(updated.width, updated.height)
            binding.textOverlay.setImageSize(updated.width, updated.height)
            binding.tvPhotoMeta.text = "${updated.width}×${updated.height}"
            Toast.makeText(this@PhotoEditorActivity, "Applied! Tap Save to export.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun delogoRegion(bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int) {
        val bw = bmp.width
        val bh = bmp.height
        val xEnd = (x0 + w).coerceAtMost(bw - 2)
        val yEnd = (y0 + h).coerceAtMost(bh - 2)
        val xStart = x0.coerceAtLeast(1)
        val yStart = y0.coerceAtLeast(1)
        val rw = (xEnd - xStart).coerceAtLeast(1)
        val rh = (yEnd - yStart).coerceAtLeast(1)

        val pixels = IntArray(bw * bh)
        bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh)

        for (y in yStart..yEnd) {
            val wy = (y - yStart).toFloat() / rh.toFloat()
            for (x in xStart..xEnd) {
                val wx = (x - xStart).toFloat() / rw.toFloat()
                val pLeft = pixels[y * bw + (xStart - 1)]
                val pRight = pixels[y * bw + (xEnd + 1)]
                val pTop = pixels[(yStart - 1) * bw + x]
                val pBot = pixels[(yEnd + 1) * bw + x]

                val rH = Color.red(pLeft) * (1f - wx) + Color.red(pRight) * wx
                val gH = Color.green(pLeft) * (1f - wx) + Color.green(pRight) * wx
                val bH = Color.blue(pLeft) * (1f - wx) + Color.blue(pRight) * wx

                val rV = Color.red(pTop) * (1f - wy) + Color.red(pBot) * wy
                val gV = Color.green(pTop) * (1f - wy) + Color.green(pBot) * wy
                val bV = Color.blue(pTop) * (1f - wy) + Color.blue(pBot) * wy

                val distX = minOf(wx, 1f - wx) + 0.01f
                val distY = minOf(wy, 1f - wy) + 0.01f
                val weightH = distY / (distX + distY)
                val weightV = distX / (distX + distY)

                val r = (rH * weightH + rV * weightV).toInt().coerceIn(0, 255)
                val g = (gH * weightH + gV * weightV).toInt().coerceIn(0, 255)
                val b = (bH * weightH + bV * weightV).toInt().coerceIn(0, 255)
                pixels[y * bw + x] = Color.rgb(r, g, b)
            }
        }
        bmp.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        blurRegion(bmp, xStart, yStart, rw, rh, radius = 3)
    }

    private fun blurRegion(bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int, radius: Int) {
        val bw = bmp.width
        val bh = bmp.height
        val xStart = x0.coerceAtLeast(0)
        val yStart = y0.coerceAtLeast(0)
        val xEnd = (x0 + w).coerceAtMost(bw - 1)
        val yEnd = (y0 + h).coerceAtMost(bh - 1)
        val rw = xEnd - xStart + 1
        val rh = yEnd - yStart + 1
        if (rw <= 1 || rh <= 1) return

        val src = IntArray(rw * rh)
        val tmp = IntArray(rw * rh)
        bmp.getPixels(src, 0, rw, xStart, yStart, rw, rh)

        for (y in 0 until rh) {
            for (x in 0 until rw) {
                var rSum = 0; var gSum = 0; var bSum = 0; var cnt = 0
                for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, rw - 1)
                    val p = src[y * rw + nx]
                    rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p); cnt++
                }
                tmp[y * rw + x] = Color.rgb(rSum / cnt, gSum / cnt, bSum / cnt)
            }
        }
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                var rSum = 0; var gSum = 0; var bSum = 0; var cnt = 0
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, rh - 1)
                    val p = tmp[ny * rw + x]
                    rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p); cnt++
                }
                src[y * rw + x] = Color.rgb(rSum / cnt, gSum / cnt, bSum / cnt)
            }
        }
        bmp.setPixels(src, 0, rw, xStart, yStart, rw, rh)
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognizer.close()
    }
}
