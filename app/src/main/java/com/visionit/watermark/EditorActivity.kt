package com.visionit.watermark

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.visionit.watermark.databinding.ActivityEditorBinding
import com.visionit.watermark.engine.WatermarkProcessor
import com.visionit.watermark.model.RemovalMode
import com.visionit.watermark.util.GalleryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_INITIAL_MODE = "extra_initial_mode"
        const val EXTRA_QUICK_TOOL = "extra_quick_tool"
        private const val MAX_BOXES = 3
    }

    private lateinit var binding: ActivityEditorBinding
    private var player: ExoPlayer? = null
    private lateinit var videoUri: Uri

    private var videoWidth = 1280
    private var videoHeight = 720
    private var videoDurationMs = 10_000L
    private var currentMode = RemovalMode.SMART_ERASE
    private var quickTool: String? = null
    private var flipH = true
    private var flipV = false
    private var compressLevel = "COMPRESS_BALANCED"
    private var savedOutputUri: Uri? = null
    private var isSeeking = false

    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!isSeeking && p.duration > 0) {
                val pos = p.currentPosition
                val dur = p.duration
                binding.seekBar.max = dur.toInt()
                binding.seekBar.progress = pos.toInt()
                binding.tvCurrentTime.text = GalleryHelper.formatDuration(pos)
                binding.tvTotalTime.text = "/ ${GalleryHelper.formatDuration(dur)}"
                if (quickTool == null) {
                    binding.watermarkOverlay.setCurrentPlayhead(pos)
                    binding.timelineView.setPlayhead(pos)
                }
            }
            handler.postDelayed(this, 60)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (uriStr.isNullOrBlank()) {
            finish()
            return
        }
        videoUri = Uri.parse(uriStr)
        quickTool = intent.getStringExtra(EXTRA_QUICK_TOOL)

        setupControls()
        configureQuickToolIfNeeded()
        loadVideoMetadataAndPlayer()
    }

    private fun configureQuickToolIfNeeded() {
        when (quickTool) {
            "FLIP" -> {
                binding.tvEditorHeader.text = "Flip Video"
                binding.modeChipsRow.visibility = View.GONE
                binding.watermarkOverlay.visibility = View.GONE
                binding.areaBarRow.visibility = View.GONE
                binding.timelineCard.visibility = View.GONE
                binding.flipControlsPanel.visibility = View.VISIBLE
                applyFlipPreview()
            }
            "COMPRESS" -> {
                binding.tvEditorHeader.text = "Compress Video"
                binding.modeChipsRow.visibility = View.GONE
                binding.watermarkOverlay.visibility = View.GONE
                binding.areaBarRow.visibility = View.GONE
                binding.timelineCard.visibility = View.GONE
                binding.compressControlsPanel.visibility = View.VISIBLE
                updateCompressChips()
            }
            else -> {
                when (intent.getStringExtra(EXTRA_INITIAL_MODE)) {
                    "BLUR" -> selectMode(RemovalMode.BLUR)
                    "CROP" -> selectMode(RemovalMode.CROP)
                    else -> selectMode(RemovalMode.SMART_ERASE)
                }
            }
        }
    }

    private fun applyFlipPreview() {
        binding.playerView.scaleX = if (flipH) -1f else 1f
        binding.playerView.scaleY = if (flipV) -1f else 1f
        styleChip(binding.btnFlipHorizontal, flipH)
        styleChip(binding.btnFlipVertical, flipV)
    }

    private fun updateCompressChips() {
        styleChip(binding.btnCompLight, compressLevel == "COMPRESS_LIGHT")
        styleChip(binding.btnCompBalanced, compressLevel == "COMPRESS_BALANCED")
        styleChip(binding.btnCompMax, compressLevel == "COMPRESS_MAX")
    }

    private fun styleChip(view: TextView, active: Boolean) {
        view.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        view.setTextColor(if (active) Color.parseColor("#04101D") else Color.parseColor("#F4F8FF"))
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnExport.setOnClickListener { startProcessing() }

        // Flip buttons
        binding.btnFlipHorizontal.setOnClickListener {
            flipH = !flipH
            if (!flipH && !flipV) flipH = true
            applyFlipPreview()
        }
        binding.btnFlipVertical.setOnClickListener {
            flipV = !flipV
            if (!flipH && !flipV) flipV = true
            applyFlipPreview()
        }

        // Compress buttons
        binding.btnCompLight.setOnClickListener { compressLevel = "COMPRESS_LIGHT"; updateCompressChips() }
        binding.btnCompBalanced.setOnClickListener { compressLevel = "COMPRESS_BALANCED"; updateCompressChips() }
        binding.btnCompMax.setOnClickListener { compressLevel = "COMPRESS_MAX"; updateCompressChips() }

        binding.watermarkOverlay.onBoxesChanged = {
            refreshAreaChipsAndTimeline()
        }

        binding.timelineView.onBoxSelected = { idx ->
            binding.watermarkOverlay.selectBox(idx)
        }

        binding.timelineView.onRangeChanged = { _, _, _ ->
            refreshAreaChipsAndTimeline()
            binding.watermarkOverlay.invalidate()
        }

        binding.timelineView.onScrubTo = { targetMs ->
            player?.seekTo(targetMs)
            binding.seekBar.progress = targetMs.toInt()
            binding.tvCurrentTime.text = GalleryHelper.formatDuration(targetMs)
            binding.watermarkOverlay.setCurrentPlayhead(targetMs)
        }

        binding.btnPlayPause.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                p.play()
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val pos = progress.toLong()
                    player?.seekTo(pos)
                    binding.tvCurrentTime.text = GalleryHelper.formatDuration(pos)
                    if (quickTool == null) {
                        binding.watermarkOverlay.setCurrentPlayhead(pos)
                        binding.timelineView.setPlayhead(pos)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
            }
        })

        binding.btnAddArea.setOnClickListener {
            if (currentMode == RemovalMode.CROP) {
                Toast.makeText(this, "Crop mode uses 1 frame", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (binding.watermarkOverlay.getBoxes().size >= MAX_BOXES) {
                Toast.makeText(this, "Maximum $MAX_BOXES areas allowed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val start = (player?.currentPosition ?: 0L)
            binding.watermarkOverlay.addDefaultBox(startMs = start, endMs = videoDurationMs)
        }

        binding.btnUndo.setOnClickListener {
            if (!binding.watermarkOverlay.undo()) {
                Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDeleteBox.setOnClickListener {
            binding.watermarkOverlay.removeBoxAt(binding.watermarkOverlay.selectedBoxIndex)
        }

        binding.chipSmartErase.setOnClickListener { selectMode(RemovalMode.SMART_ERASE) }
        binding.chipBlur.setOnClickListener { selectMode(RemovalMode.BLUR) }
        binding.chipCrop.setOnClickListener { selectMode(RemovalMode.CROP) }

        binding.btnCancelProcess.setOnClickListener {
            WatermarkProcessor.cancelCurrent()
            binding.processingOverlay.visibility = View.GONE
        }

        binding.btnPreviewResult.setOnClickListener {
            savedOutputUri?.let { GalleryHelper.playVideo(this, it) }
        }

        binding.btnShareResult.setOnClickListener {
            savedOutputUri?.let { GalleryHelper.shareVideo(this, it) }
        }

        binding.btnDoneResult.setOnClickListener {
            finish()
        }

        refreshAreaChipsAndTimeline()
    }

    private fun selectMode(mode: RemovalMode) {
        currentMode = mode
        binding.watermarkOverlay.mode = mode
        styleChip(binding.chipSmartErase, mode == RemovalMode.SMART_ERASE)
        styleChip(binding.chipBlur, mode == RemovalMode.BLUR)
        styleChip(binding.chipCrop, mode == RemovalMode.CROP)

        val isCrop = mode == RemovalMode.CROP
        binding.areaBarRow.visibility = if (isCrop) View.GONE else View.VISIBLE
        binding.timelineCard.visibility = if (isCrop) View.GONE else View.VISIBLE
        refreshAreaChipsAndTimeline()
    }

    private fun refreshAreaChipsAndTimeline() {
        val boxes = binding.watermarkOverlay.getBoxes()
        val selected = binding.watermarkOverlay.selectedBoxIndex

        binding.tvAreaCount.text = "${boxes.size}/$MAX_BOXES"
        binding.timelineView.setData(boxes, selected, videoDurationMs)

        val container = binding.areaChipsContainer
        container.removeAllViews()
        val density = resources.displayMetrics.density

        boxes.forEachIndexed { idx, box ->
            val isSelected = idx == selected
            val end = if (box.endMs > 0L) box.endMs else videoDurationMs
            val chip = TextView(this).apply {
                text = if (isSelected) {
                    val rangeStr = "${GalleryHelper.formatDuration(box.startMs)}–${GalleryHelper.formatDuration(end)}"
                    if (boxes.size > 1) "#${idx + 1} $rangeStr ✕" else "#${idx + 1} $rangeStr"
                } else {
                    "#${idx + 1}"
                }
                textSize = 11.5f
                isSingleLine = true
                gravity = Gravity.CENTER
                setPadding(
                    (10 * density).toInt(),
                    (4 * density).toInt(),
                    (10 * density).toInt(),
                    (4 * density).toInt()
                )
                setTextColor(if (isSelected) Color.parseColor("#04101D") else box.color)
                setTypeface(typeface, android.graphics.Typeface.BOLD)

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 18 * density
                    if (isSelected) {
                        setColor(box.color)
                    } else {
                        setColor(Color.parseColor("#18253D"))
                        setStroke((1.5f * density).toInt(), box.color)
                    }
                }

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (32 * density).toInt()
                ).apply {
                    marginEnd = (6 * density).toInt()
                }
                layoutParams = lp

                setOnClickListener {
                    if (isSelected && boxes.size > 1) {
                        binding.watermarkOverlay.removeBoxAt(idx)
                    } else {
                        binding.watermarkOverlay.selectBox(idx)
                    }
                }
            }
            container.addView(chip)
        }
    }

    private fun loadVideoMetadataAndPlayer() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@EditorActivity, videoUri)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
                    val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10_000L

                    if (rot == 90 || rot == 270) {
                        videoWidth = h
                        videoHeight = w
                    } else {
                        videoWidth = w
                        videoHeight = h
                    }
                    if (dur > 0) videoDurationMs = dur
                } catch (_: Exception) {
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            }

            binding.tvVideoMeta.text =
                "${GalleryHelper.formatDuration(videoDurationMs)} • ${videoWidth}×${videoHeight}"
            binding.tvTotalTime.text = "/ ${GalleryHelper.formatDuration(videoDurationMs)}"
            binding.watermarkOverlay.setVideoDimensions(videoWidth, videoHeight)
            binding.watermarkOverlay.setVideoDuration(videoDurationMs)

            if (quickTool == null) {
                extractFilmstripThumbnails()
            }

            val exo = ExoPlayer.Builder(this@EditorActivity).build().apply {
                setMediaItem(MediaItem.fromUri(videoUri))
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            val rot = videoSize.unappliedRotationDegrees
                            val w = if (rot == 90 || rot == 270) videoSize.height else videoSize.width
                            val h = if (rot == 90 || rot == 270) videoSize.width else videoSize.height
                            videoWidth = w
                            videoHeight = h
                            binding.watermarkOverlay.setVideoDimensions(w, h)
                            binding.tvVideoMeta.text =
                                "${GalleryHelper.formatDuration(videoDurationMs)} • ${videoWidth}×${videoHeight}"
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && duration > 0) {
                            videoDurationMs = duration
                            binding.watermarkOverlay.setVideoDuration(duration)
                            binding.tvVideoMeta.text =
                                "${GalleryHelper.formatDuration(videoDurationMs)} • ${videoWidth}×${videoHeight}"
                        }
                    }
                })
                prepare()
                playWhenReady = true
            }
            player = exo
            binding.playerView.player = exo
            handler.post(progressUpdater)
        }
    }

    private fun extractFilmstripThumbnails() {
        lifecycleScope.launch {
            val thumbs = withContext(Dispatchers.IO) {
                val list = mutableListOf<Bitmap>()
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@EditorActivity, videoUri)
                    val frameCount = 8
                    val stepUs = (videoDurationMs * 1000L) / frameCount
                    for (i in 0 until frameCount) {
                        val timeUs = (i * stepUs + stepUs / 2L).coerceAtLeast(0L)
                        val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (raw != null) {
                            val scaled = Bitmap.createScaledBitmap(raw, 120, 90, true)
                            if (scaled != raw) raw.recycle()
                            list.add(scaled)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
                list
            }
            if (thumbs.isNotEmpty()) {
                binding.timelineView.setThumbnails(thumbs)
            }
        }
    }

    private fun startProcessing() {
        player?.pause()
        binding.btnPlayPause.setImageResource(R.drawable.ic_play)

        val pixelBoxes = binding.watermarkOverlay.computePixelBoxes(videoWidth, videoHeight)
        val tempOut = File(cacheDir, "vwatermark_${System.currentTimeMillis()}.mp4")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val finalName = when (quickTool) {
            "FLIP" -> "VWatermark_Flip_$stamp.mp4"
            "COMPRESS" -> "VWatermark_Comp_$stamp.mp4"
            else -> "VWatermark_$stamp.mp4"
        }

        val resolvedQuickCmd = when (quickTool) {
            "FLIP" -> when {
                flipH && flipV -> "FLIP_HV"
                flipV -> "FLIP_V"
                else -> "FLIP_H"
            }
            "COMPRESS" -> compressLevel
            else -> null
        }

        savedOutputUri = null
        binding.processingOverlay.visibility = View.VISIBLE
        binding.resultCard.visibility = View.GONE
        binding.btnCancelProcess.visibility = View.VISIBLE
        binding.circularProgress.progress = 0
        binding.tvPercent.text = "0%"
        binding.tvProcessBrand.text = getString(R.string.app_title)
        binding.tvProcessStatus.text = when (quickTool) {
            "FLIP" -> "Flipping video…"
            "COMPRESS" -> "Compressing video…"
            else -> getString(R.string.removing_watermark)
        }

        WatermarkProcessor.processVideo(
            context = this,
            inputUri = videoUri,
            outputFile = tempOut,
            mode = currentMode,
            boxes = pixelBoxes,
            durationMs = videoDurationMs,
            quickToolCommand = resolvedQuickCmd,
            onProgress = { pct ->
                runOnUiThread {
                    binding.circularProgress.progress = pct
                    binding.tvPercent.text = "$pct%"
                }
            },
            onComplete = { success, error ->
                if (success) {
                    lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            val uri = GalleryHelper.saveVideoToGallery(this@EditorActivity, tempOut, finalName)
                            tempOut.delete()
                            uri
                        }
                        savedOutputUri = saved
                        binding.circularProgress.progress = 100
                        binding.tvPercent.text = "100%"
                        binding.tvProcessStatus.text = "Done! Saved to Gallery"
                        binding.btnCancelProcess.visibility = View.GONE
                        binding.resultCard.visibility = View.VISIBLE
                    }
                } else {
                    runOnUiThread {
                        binding.processingOverlay.visibility = View.GONE
                        if (error != "Cancelled") {
                            Toast.makeText(
                                this@EditorActivity,
                                "Error: ${error ?: "Processing failed"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(progressUpdater)
        WatermarkProcessor.cancelCurrent()
        player?.release()
        player = null
    }
}
