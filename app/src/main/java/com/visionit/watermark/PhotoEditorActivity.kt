package com.visionit.watermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
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
    private var specialPhotoTool: String? = null
    private var photoFlipH = true
    private var photoFlipV = false
    private var photoFlipOriginal = false
    private var photoCompressQuality = 65
    private var photoWmTypeface: Typeface = Typeface.DEFAULT_BOLD
    private var photoWmColor: Int = Color.WHITE
    private var photoWmLogo: Bitmap? = null

    private val pickPhotoLogoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                }
                if (bmp != null) {
                    photoWmLogo = bmp
                    binding.photoOverlay.previewLogoBitmap = bmp
                    binding.btnPhotoPickLogo.text = "Clear Logo"
                }
            }
        }
    }
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
        when (intent.getStringExtra(EXTRA_INITIAL_MODE)) {
            "EDIT_TEXT" -> enterEditTextMode()
            "FLIP" -> enterSpecialPhotoTool("FLIP")
            "COMPRESS" -> enterSpecialPhotoTool("COMPRESS")
            "ADD_TEXT" -> enterSpecialPhotoTool("ADD_TEXT")
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
                // Step 1: Show draggable box over any text; Step 2: Tap again to replace text inside box
                if (binding.photoOverlay.visibility != View.VISIBLE) {
                    binding.photoOverlay.visibility = View.VISIBLE
                    binding.textOverlay.visibility = View.GONE
                    binding.btnAddArea.text = "Replace Box"
                    binding.tvEditTextHint.text = "Place box over any text, then tap 'Replace Box'"
                } else {
                    val bmp = currentBitmap ?: return@setOnClickListener
                    val boxes = binding.photoOverlay.computePixelBoxes(bmp.width, bmp.height)
                    if (boxes.isNotEmpty()) {
                        val b = boxes.first()
                        showReplaceTextDialog("", Rect(b.x, b.y, b.x + b.w, b.y + b.h))
                    }
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

        // Photo Flip buttons
        binding.btnPhotoFlipH.setOnClickListener {
            photoFlipOriginal = false
            photoFlipH = !photoFlipH
            if (!photoFlipH && !photoFlipV) photoFlipH = true
            updatePhotoFlipPreview()
        }
        binding.btnPhotoFlipV.setOnClickListener {
            photoFlipOriginal = false
            photoFlipV = !photoFlipV
            if (!photoFlipH && !photoFlipV) photoFlipV = true
            updatePhotoFlipPreview()
        }
        binding.btnPhotoFlipOrig.setOnClickListener {
            photoFlipOriginal = !photoFlipOriginal
            updatePhotoFlipPreview()
        }

        // Photo Compress buttons
        binding.btnPhotoCompHigh.setOnClickListener { photoCompressQuality = 85; updatePhotoCompressUi() }
        binding.btnPhotoCompMed.setOnClickListener { photoCompressQuality = 65; updatePhotoCompressUi() }
        binding.btnPhotoCompLow.setOnClickListener { photoCompressQuality = 40; updatePhotoCompressUi() }

        // Photo Add Text / Logo controls
        binding.etPhotoWmText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.photoOverlay.previewText = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.btnPhotoPickLogo.setOnClickListener {
            if (photoWmLogo != null) {
                photoWmLogo = null
                binding.photoOverlay.previewLogoBitmap = null
                binding.btnPhotoPickLogo.text = "+ Logo"
            } else {
                pickPhotoLogoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        binding.pFontHindBold.setOnClickListener { selectPhotoFont("HIND_BOLD") }
        binding.pFontHindSemi.setOnClickListener { selectPhotoFont("HIND_SEMI") }
        binding.pFontSans.setOnClickListener { selectPhotoFont("ENG_SANS") }
        binding.pFontSerif.setOnClickListener { selectPhotoFont("ENG_SERIF") }
        binding.pFontItalic.setOnClickListener { selectPhotoFont("ENG_ITALIC") }
        binding.pFontImpact.setOnClickListener { selectPhotoFont("ENG_CONDENSED") }
        binding.pFontMono.setOnClickListener { selectPhotoFont("ENG_MONO") }

        binding.pColorWhite.setOnClickListener { selectPhotoColor(Color.WHITE) }
        binding.pColorYellow.setOnClickListener { selectPhotoColor(Color.parseColor("#FFD600")) }
        binding.pColorCyan.setOnClickListener { selectPhotoColor(Color.parseColor("#00E5FF")) }
        binding.pColorRed.setOnClickListener { selectPhotoColor(Color.parseColor("#FF5C72")) }
        binding.pColorBlack.setOnClickListener { selectPhotoColor(Color.BLACK) }

        binding.btnSavePhoto.setOnClickListener {
            val srcBmp = currentBitmap ?: return@setOnClickListener
            lifecycleScope.launch {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val finalBmp = withContext(Dispatchers.Default) {
                    when (specialPhotoTool) {
                        "FLIP" -> {
                            val m = Matrix().apply {
                                postScale(if (photoFlipH) -1f else 1f, if (photoFlipV) -1f else 1f)
                            }
                            Bitmap.createBitmap(srcBmp, 0, 0, srcBmp.width, srcBmp.height, m, true)
                        }
                        "ADD_TEXT" -> bakeWatermarkOnPhoto(srcBmp)
                        else -> srcBmp
                    }
                }
                val quality = if (specialPhotoTool == "COMPRESS") photoCompressQuality else 96
                val uri = withContext(Dispatchers.IO) {
                    GalleryHelper.savePhotoToGallery(this@PhotoEditorActivity, finalBmp, "VWatermark_$stamp.jpg", quality)
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

    private fun enterSpecialPhotoTool(tool: String) {
        specialPhotoTool = tool
        binding.photoModeChipsRow.visibility = View.GONE
        binding.photoBottomControls.visibility = View.GONE
        binding.textOverlay.visibility = View.GONE
        binding.tvEditTextHint.visibility = View.GONE

        when (tool) {
            "FLIP" -> {
                binding.tvPhotoTitle.text = "Flip Photo"
                binding.photoOverlay.visibility = View.GONE
                binding.photoFlipPanel.visibility = View.VISIBLE
                updatePhotoFlipPreview()
            }
            "COMPRESS" -> {
                binding.tvPhotoTitle.text = "Compress Photo"
                binding.photoOverlay.visibility = View.GONE
                binding.photoCompressPanel.visibility = View.VISIBLE
                updatePhotoCompressUi()
            }
            "ADD_TEXT" -> {
                binding.tvPhotoTitle.text = "Add Text / Logo"
                binding.photoOverlay.visibility = View.VISIBLE
                binding.photoOverlay.isAddOverlayMode = true
                binding.photoAddTextPanel.visibility = View.VISIBLE
                selectPhotoFont("HIND_BOLD")
                selectPhotoColor(Color.WHITE)
                binding.photoOverlay.previewText = binding.etPhotoWmText.text?.toString().orEmpty()
            }
        }
    }

    private fun updatePhotoFlipPreview() {
        binding.ivPhotoPreview.scaleX = if (!photoFlipOriginal && photoFlipH) -1f else 1f
        binding.ivPhotoPreview.scaleY = if (!photoFlipOriginal && photoFlipV) -1f else 1f
        styleModeChip(binding.btnPhotoFlipH, !photoFlipOriginal && photoFlipH)
        styleModeChip(binding.btnPhotoFlipV, !photoFlipOriginal && photoFlipV)
        styleModeChip(binding.btnPhotoFlipOrig, photoFlipOriginal)
    }

    private fun updatePhotoCompressUi() {
        styleModeChip(binding.btnPhotoCompHigh, photoCompressQuality == 85)
        styleModeChip(binding.btnPhotoCompMed, photoCompressQuality == 65)
        styleModeChip(binding.btnPhotoCompLow, photoCompressQuality == 40)
        val bmp = currentBitmap ?: return
        lifecycleScope.launch {
            val kb = withContext(Dispatchers.Default) {
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, photoCompressQuality, baos)
                baos.size() / 1024
            }
            binding.tvPhotoCompInfo.text = "Estimated size: ${kb} KB (Quality $photoCompressQuality%)"
        }
    }

    private fun selectPhotoFont(key: String) {
        photoWmTypeface = try {
            when (key) {
                "HIND_BOLD" -> Typeface.createFromAsset(assets, "fonts/HindSiliguri-Bold.ttf")
                "HIND_SEMI" -> Typeface.createFromAsset(assets, "fonts/HindSiliguri-SemiBold.ttf")
                "ENG_SANS" -> Typeface.createFromAsset(assets, "fonts/eng_sans_bold.ttf")
                "ENG_SERIF" -> Typeface.createFromAsset(assets, "fonts/eng_serif_bold.ttf")
                "ENG_ITALIC" -> Typeface.createFromAsset(assets, "fonts/eng_italic_bold.ttf")
                "ENG_CONDENSED" -> Typeface.createFromAsset(assets, "fonts/eng_condensed_bold.ttf")
                "ENG_MONO" -> Typeface.createFromAsset(assets, "fonts/eng_mono_bold.ttf")
                else -> Typeface.DEFAULT_BOLD
            }
        } catch (_: Exception) { Typeface.DEFAULT_BOLD }
        binding.photoOverlay.previewTypeface = photoWmTypeface
        styleModeChip(binding.pFontHindBold, key == "HIND_BOLD")
        styleModeChip(binding.pFontHindSemi, key == "HIND_SEMI")
        styleModeChip(binding.pFontSans, key == "ENG_SANS")
        styleModeChip(binding.pFontSerif, key == "ENG_SERIF")
        styleModeChip(binding.pFontItalic, key == "ENG_ITALIC")
        styleModeChip(binding.pFontImpact, key == "ENG_CONDENSED")
        styleModeChip(binding.pFontMono, key == "ENG_MONO")
    }

    private fun selectPhotoColor(color: Int) {
        photoWmColor = color
        binding.photoOverlay.previewTextColor = color
        styleModeChip(binding.pColorWhite, color == Color.WHITE)
        styleModeChip(binding.pColorYellow, color == Color.parseColor("#FFD600"))
        styleModeChip(binding.pColorCyan, color == Color.parseColor("#00E5FF"))
        styleModeChip(binding.pColorRed, color == Color.parseColor("#FF5C72"))
        styleModeChip(binding.pColorBlack, color == Color.BLACK)
        if (color != Color.parseColor("#FFD600")) binding.pColorYellow.setTextColor(Color.parseColor("#FFD600"))
        if (color != Color.parseColor("#00E5FF")) binding.pColorCyan.setTextColor(Color.parseColor("#00E5FF"))
        if (color != Color.parseColor("#FF5C72")) binding.pColorRed.setTextColor(Color.parseColor("#FF5C72"))
    }

    private fun bakeWatermarkOnPhoto(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val boxes = binding.photoOverlay.computePixelBoxes(out.width, out.height)
        if (boxes.isEmpty()) return out
        val b = boxes.first()
        val canvas = Canvas(out)
        val logo = photoWmLogo
        if (logo != null && !logo.isRecycled) {
            val dst = Rect(b.x, b.y, b.x + b.w, b.y + b.h)
            canvas.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val text = binding.etPhotoWmText.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = photoWmColor
                    typeface = photoWmTypeface
                    textAlign = Paint.Align.CENTER
                    setShadowLayer(6f, 2f, 2f, Color.argb(200, 0, 0, 0))
                }
                var sz = (b.h * 0.62f).coerceAtLeast(20f)
                paint.textSize = sz
                val measured = paint.measureText(text)
                if (measured > b.w - 16f && measured > 0f) {
                    sz *= ((b.w - 16f) / measured)
                    paint.textSize = sz.coerceAtLeast(14f)
                }
                val fm = paint.fontMetrics
                val baseY = b.y + b.h / 2f - (fm.ascent + fm.descent) / 2f
                canvas.drawText(text, b.x + b.w / 2f, baseY, paint)
            }
        }
        return out
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

                // Sample exact background & foreground ink color BEFORE erasing
                val (bgColor, bgIsUniform) = sampleBorderBackground(out, box)
                val textColor = if (chosenColor != 0) chosenColor else sampleAccurateTextColor(out, box, bgColor)
                val autoBold = if (chosenColor == 0) detectBoldness(out, box, bgColor) else isBold

                // 1. Exact gradient-preserving background reconstruction (zero flat patch, zero blur!)
                val pad = 3
                val rx = (box.left - pad).coerceAtLeast(2)
                val ry = (box.top - pad).coerceAtLeast(2)
                val rw = (box.width() + pad * 2).coerceAtMost(out.width - rx - 3)
                val rh = (box.height() + pad * 2).coerceAtMost(out.height - ry - 3)
                if (rw > 2 && rh > 2) {
                    reconstructGradientBackground(out, rx, ry, rw, rh)
                }

                // 2. Render replacement text matching exact original height, left alignment, weight & color
                if (newText.isNotBlank()) {
                    val canvas = Canvas(out)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                        color = textColor
                        typeface = Typeface.create(
                            Typeface.SANS_SERIF,
                            if (autoBold) Typeface.BOLD else Typeface.NORMAL
                        )
                        textAlign = Paint.Align.LEFT
                    }

                    // Match cap-height precisely to original bounding box height
                    val targetHeight = (box.height() * 0.82f).coerceAtLeast(14f)
                    paint.textSize = targetHeight
                    val bounds = Rect()
                    paint.getTextBounds("Ag", 0, 2, bounds)
                    if (bounds.height() > 0) {
                        paint.textSize = (targetHeight * (box.height().toFloat() / bounds.height().toFloat()) * 0.88f)
                            .coerceAtLeast(14f)
                    }

                    // Keep within image right edge if new text is much longer
                    val maxW = (out.width - box.left - 16).toFloat().coerceAtLeast(box.width().toFloat())
                    val measuredW = paint.measureText(newText)
                    if (measuredW > maxW) {
                        paint.textSize = (paint.textSize * (maxW / measuredW)).coerceAtLeast(12f)
                    }

                    val fm = paint.fontMetrics
                    val baselineY = box.exactCenterY() - (fm.ascent + fm.descent) / 2f
                    canvas.drawText(newText, box.left.toFloat(), baselineY, paint)
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

    private fun sampleBorderBackground(bmp: Bitmap, box: Rect): Pair<Int, Boolean> {
        val samples = mutableListOf<Int>()
        val topY = (box.top - 4).coerceIn(0, bmp.height - 1)
        val botY = (box.bottom + 4).coerceIn(0, bmp.height - 1)
        val leftX = (box.left - 4).coerceIn(0, bmp.width - 1)
        val rightX = (box.right + 4).coerceIn(0, bmp.width - 1)

        val stepX = (box.width() / 8).coerceAtLeast(1)
        var x = box.left.coerceIn(0, bmp.width - 1)
        while (x <= box.right.coerceIn(0, bmp.width - 1)) {
            samples.add(bmp.getPixel(x, topY))
            samples.add(bmp.getPixel(x, botY))
            x += stepX
        }
        samples.add(bmp.getPixel(leftX, box.centerY().coerceIn(0, bmp.height - 1)))
        samples.add(bmp.getPixel(rightX, box.centerY().coerceIn(0, bmp.height - 1)))

        val rs = samples.map { Color.red(it) }.sorted()
        val gs = samples.map { Color.green(it) }.sorted()
        val bs = samples.map { Color.blue(it) }.sorted()
        val medR = rs[rs.size / 2]
        val medG = gs[gs.size / 2]
        val medB = bs[bs.size / 2]

        // Check if border is visually uniform (e.g., solid UI card/background)
        var maxSpread = 0
        for (px in samples) {
            val spread = kotlin.math.abs(Color.red(px) - medR) +
                kotlin.math.abs(Color.green(px) - medG) +
                kotlin.math.abs(Color.blue(px) - medB)
            if (spread > maxSpread) maxSpread = spread
        }
        return Color.rgb(medR, medG, medB) to (maxSpread < 55)
    }

    private fun sampleAccurateTextColor(bmp: Bitmap, box: Rect, bgColor: Int): Int {
        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)
        val candidates = mutableListOf<Pair<Int, Int>>()

        val stepX = (box.width() / 16).coerceAtLeast(1)
        val stepY = (box.height() / 10).coerceAtLeast(1)
        var y = box.top.coerceAtLeast(0)
        while (y < box.bottom.coerceAtMost(bmp.height)) {
            var x = box.left.coerceAtLeast(0)
            while (x < box.right.coerceAtMost(bmp.width)) {
                val px = bmp.getPixel(x, y)
                val diff = kotlin.math.abs(Color.red(px) - bgR) +
                    kotlin.math.abs(Color.green(px) - bgG) +
                    kotlin.math.abs(Color.blue(px) - bgB)
                if (diff > 60) {
                    candidates.add(diff to px)
                }
                x += stepX
            }
            y += stepY
        }
        if (candidates.isEmpty()) {
            val lum = 0.299 * bgR + 0.587 * bgG + 0.114 * bgB
            return if (lum > 128) Color.BLACK else Color.WHITE
        }
        // Take top 25% strongest-contrast ink pixels and compute median RGB for exact color match
        val topInk = candidates.sortedByDescending { it.first }.take((candidates.size / 4).coerceAtLeast(1))
        val r = topInk.map { Color.red(it.second) }.sorted()[topInk.size / 2]
        val g = topInk.map { Color.green(it.second) }.sorted()[topInk.size / 2]
        val b = topInk.map { Color.blue(it.second) }.sorted()[topInk.size / 2]
        return Color.rgb(r, g, b)
    }

    private fun detectBoldness(bmp: Bitmap, box: Rect, bgColor: Int): Boolean {
        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)
        var total = 0
        var ink = 0
        val stepX = (box.width() / 20).coerceAtLeast(1)
        val stepY = (box.height() / 10).coerceAtLeast(1)
        var y = box.top.coerceAtLeast(0)
        while (y < box.bottom.coerceAtMost(bmp.height)) {
            var x = box.left.coerceAtLeast(0)
            while (x < box.right.coerceAtMost(bmp.width)) {
                val px = bmp.getPixel(x, y)
                val diff = kotlin.math.abs(Color.red(px) - bgR) +
                    kotlin.math.abs(Color.green(px) - bgG) +
                    kotlin.math.abs(Color.blue(px) - bgB)
                total++
                if (diff > 85) ink++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && (ink.toFloat() / total.toFloat()) > 0.28f
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
            } else if (specialPhotoTool == "COMPRESS") {
                updatePhotoCompressUi()
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

    /**
     * Exact Coons-patch bilinear gradient reconstruction from the 4 clean border edges
     * (zero flat patch, zero blur). Reproduces both solid backgrounds and smooth gradients seamlessly.
     */
    private fun reconstructGradientBackground(bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int) {
        val bw = bmp.width
        val bh = bmp.height
        val xStart = x0.coerceAtLeast(2)
        val yStart = y0.coerceAtLeast(2)
        val xEnd = (x0 + w).coerceAtMost(bw - 3)
        val yEnd = (y0 + h).coerceAtMost(bh - 3)
        val rw = (xEnd - xStart).coerceAtLeast(1)
        val rh = (yEnd - yStart).coerceAtLeast(1)

        val pixels = IntArray(bw * bh)
        bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh)

        for (y in yStart..yEnd) {
            val wy = (y - yStart).toFloat() / rh.toFloat()
            val pLeft = pixels[y * bw + (xStart - 2)]
            val pRight = pixels[y * bw + (xEnd + 2)]
            for (x in xStart..xEnd) {
                val wx = (x - xStart).toFloat() / rw.toFloat()
                val pTop = pixels[(yStart - 2) * bw + x]
                val pBot = pixels[(yEnd + 2) * bw + x]

                val rV = Color.red(pTop) * (1f - wy) + Color.red(pBot) * wy
                val gV = Color.green(pTop) * (1f - wy) + Color.green(pBot) * wy
                val bV = Color.blue(pTop) * (1f - wy) + Color.blue(pBot) * wy

                val rH = Color.red(pLeft) * (1f - wx) + Color.red(pRight) * wx
                val gH = Color.green(pLeft) * (1f - wx) + Color.green(pRight) * wx
                val bH = Color.blue(pLeft) * (1f - wx) + Color.blue(pRight) * wx

                val pTL = pixels[(yStart - 2) * bw + (xStart - 2)]
                val pTR = pixels[(yStart - 2) * bw + (xEnd + 2)]
                val pBL = pixels[(yEnd + 2) * bw + (xStart - 2)]
                val pBR = pixels[(yEnd + 2) * bw + (xEnd + 2)]

                val rC = (1f - wx) * (1f - wy) * Color.red(pTL) +
                    wx * (1f - wy) * Color.red(pTR) +
                    (1f - wx) * wy * Color.red(pBL) +
                    wx * wy * Color.red(pBR)
                val gC = (1f - wx) * (1f - wy) * Color.green(pTL) +
                    wx * (1f - wy) * Color.green(pTR) +
                    (1f - wx) * wy * Color.green(pBL) +
                    wx * wy * Color.green(pBR)
                val bC = (1f - wx) * (1f - wy) * Color.blue(pTL) +
                    wx * (1f - wy) * Color.blue(pTR) +
                    (1f - wx) * wy * Color.blue(pBL) +
                    wx * wy * Color.blue(pBR)

                val r = (rH + rV - rC).toInt().coerceIn(0, 255)
                val g = (gH + gV - gC).toInt().coerceIn(0, 255)
                val b = (bH + bV - bC).toInt().coerceIn(0, 255)
                pixels[y * bw + x] = Color.rgb(r, g, b)
            }
        }
        bmp.setPixels(pixels, 0, bw, 0, 0, bw, bh)
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
