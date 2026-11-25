package com.visionit.watermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.visionit.watermark.databinding.ActivityPhotoEditorBinding
import com.visionit.watermark.model.RemovalMode
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
        private const val MAX_BOXES = 3
    }

    private lateinit var binding: ActivityPhotoEditorBinding
    private var currentBitmap: Bitmap? = null
    private val bitmapUndoStack = ArrayDeque<Bitmap>()
    private var currentMode = RemovalMode.SMART_ERASE

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
        loadPhoto(Uri.parse(uriStr))
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.photoOverlay.onBoxesChanged = {
            binding.tvAreaCount.text = "${binding.photoOverlay.getBoxes().size}/$MAX_BOXES"
        }

        binding.btnAddArea.setOnClickListener {
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
                currentBitmap?.let {
                    binding.photoOverlay.setVideoDimensions(it.width, it.height)
                    binding.tvPhotoMeta.text = "${it.width}×${it.height}"
                }
            } else if (!binding.photoOverlay.undo()) {
                Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            }
        }

        binding.chipSmartErase.setOnClickListener { selectMode(RemovalMode.SMART_ERASE) }
        binding.chipBlur.setOnClickListener { selectMode(RemovalMode.BLUR) }
        binding.chipCrop.setOnClickListener { selectMode(RemovalMode.CROP) }

        binding.btnApplyErase.setOnClickListener { applyPhotoEdit() }

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

    private fun selectMode(mode: RemovalMode) {
        currentMode = mode
        binding.photoOverlay.mode = mode
        fun style(tv: TextView, active: Boolean) {
            tv.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            tv.setTextColor(if (active) Color.parseColor("#04101D") else Color.parseColor("#F4F8FF"))
        }
        style(binding.chipSmartErase, mode == RemovalMode.SMART_ERASE)
        style(binding.chipBlur, mode == RemovalMode.BLUR)
        style(binding.chipCrop, mode == RemovalMode.CROP)
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
            binding.tvPhotoMeta.text = "${bmp.width}×${bmp.height}"
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
            binding.tvPhotoMeta.text = "${updated.width}×${updated.height}"
            Toast.makeText(this@PhotoEditorActivity, "Applied! Tap Save to export.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Fast bilinear boundary interpolation (same core principle as FFmpeg delogo) + gentle feathering.
     */
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
            val topPx = pixels[(yStart - 1) * bw + xStart]
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

                // Weight horizontal vs vertical by proximity to nearest edge
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
        // Soft 2px smoothing pass so seam blends invisibly
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

        // Horizontal pass
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
        // Vertical pass
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
}
