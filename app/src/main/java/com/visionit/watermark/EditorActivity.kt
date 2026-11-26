package com.visionit.watermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private var videoHasAudio = true
    private var currentMode = RemovalMode.SMART_ERASE
    private var quickTool: String? = null
    private var flipH = true
    private var flipV = false
    private var compressLevel = "COMPRESS_BALANCED"
    private var speedFactor = 1.0f
    private var isReversePreviewActive = false
    private var reversePreviewPosMs = 0L

    // Add Text / Logo state
    private var selectedFontKey = "MODERN"
    private var selectedTextColor = Color.WHITE
    private var selectedLogoBitmap: Bitmap? = null
    private var selectedTypeface: Typeface = Typeface.DEFAULT_BOLD

    private var savedOutputUri: Uri? = null
    private var isAudioOutput = false
    private var isSeeking = false

    private val pickLogoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bmp != null) {
                    selectedLogoBitmap = bmp
                    binding.watermarkOverlay.previewLogoBitmap = bmp
                    binding.btnPickLogo.text = "Clear Logo"
                    Toast.makeText(this@EditorActivity, "Logo added! Drag box to resize & place.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (isReversePreviewActive) {
                val stepMs = (110f * speedFactor).toLong().coerceAtLeast(45L)
                reversePreviewPosMs -= stepMs
                if (reversePreviewPosMs <= 0L) reversePreviewPosMs = videoDurationMs
                p.seekTo(reversePreviewPosMs)
                binding.seekBar.max = videoDurationMs.toInt()
                binding.seekBar.progress = reversePreviewPosMs.toInt()
                binding.tvCurrentTime.text = GalleryHelper.formatDuration(reversePreviewPosMs)
                handler.postDelayed(this, 100)
                return
            }

            if (!isSeeking && p.duration > 0) {
                var pos = p.currentPosition
                val dur = p.duration

                // Live Multi-Cut Trim Preview: automatically skip cut-out gaps between kept clips!
                if (quickTool == "TRIM" && p.isPlaying) {
                    val clips = binding.watermarkOverlay.getBoxes()
                        .map { it.startMs to (if (it.endMs > it.startMs) it.endMs else dur) }
                        .sortedBy { it.first }
                    if (clips.isNotEmpty()) {
                        val inAnyClip = clips.any { pos in it.first..it.second }
                        if (!inAnyClip) {
                            val nextClip = clips.firstOrNull { it.first > pos } ?: clips.first()
                            pos = nextClip.first
                            p.seekTo(pos)
                        }
                    }
                }

                binding.seekBar.max = dur.toInt()
                binding.seekBar.progress = pos.toInt()
                binding.tvCurrentTime.text = GalleryHelper.formatDuration(pos)
                binding.tvTotalTime.text = "/ ${GalleryHelper.formatDuration(dur)}"
                if (quickTool == null || quickTool == "TRIM") {
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
            "TRIM" -> {
                binding.tvEditorHeader.text = "Multi-Cut Trim"
                binding.modeChipsRow.visibility = View.GONE
                binding.watermarkOverlay.visibility = View.GONE
                binding.areaBarRow.visibility = View.VISIBLE
                binding.btnAddArea.text = "+ Cut Clip"
                binding.tvQuickToolBanner.visibility = View.VISIBLE
                binding.tvQuickToolBanner.text = "Keep up to 3 clips — playback previews only kept clips!"
                binding.timelineCard.visibility = View.VISIBLE
            }
            "SPEED" -> {
                binding.tvEditorHeader.text = "Video Speed"
                binding.modeChipsRow.visibility = View.GONE
                binding.watermarkOverlay.visibility = View.GONE
                binding.areaBarRow.visibility = View.GONE
                binding.timelineCard.visibility = View.GONE
                binding.speedControlsPanel.visibility = View.VISIBLE
                binding.btnPreviewReverse.visibility = View.GONE
                binding.tvSpeedLabel.text = "Drag slider for live speed preview"
                speedFactor = 1.0f
                binding.seekSpeed.progress = 10 // 0.5 + 10*0.05 = 1.00x
                updateSpeedUi()
            }
            "ADD_TEXT" -> {
                binding.tvEditorHeader.text = "Add Text / Logo"
                binding.modeChipsRow.visibility = View.GONE
                binding.areaBarRow.visibility = View.GONE
                binding.timelineCard.visibility = View.GONE
                binding.watermarkOverlay.visibility = View.VISIBLE
                binding.watermarkOverlay.isAddOverlayMode = true
                binding.addTextControlsPanel.visibility = View.VISIBLE
                selectFont("MODERN")
                selectTextColor(Color.WHITE)
                binding.watermarkOverlay.previewText = binding.etWatermarkText.text?.toString().orEmpty()
            }
            "TO_AUDIO" -> {
                binding.tvEditorHeader.text = "Extract Audio"
                binding.modeChipsRow.visibility = View.GONE
                binding.watermarkOverlay.visibility = View.GONE
                binding.areaBarRow.visibility = View.GONE
                binding.timelineCard.visibility = View.GONE
                binding.tvQuickToolBanner.visibility = View.VISIBLE
                binding.tvQuickToolBanner.text = "Tap Export to extract high-quality audio (192kbps) to Music"
            }
            "REVERSE" -> {
                binding.tvEditorHeader.text = "Reverse Video"
                binding.modeChipsRow.visibility = View.GONE
                binding.watermarkOverlay.visibility = View.GONE
                binding.areaBarRow.visibility = View.GONE
                binding.timelineCard.visibility = View.GONE
                binding.speedControlsPanel.visibility = View.VISIBLE
                binding.btnPreviewReverse.visibility = View.VISIBLE
                binding.tvSpeedLabel.text = "Reverse Speed (adjust freely)"
                speedFactor = 1.0f
                binding.seekSpeed.progress = 10
                updateSpeedUi()
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

    private fun updateSpeedUi() {
        binding.tvSpeedValue.text = String.format(Locale.US, "%.2fx", speedFactor)
        if (!isReversePreviewActive) {
            player?.setPlaybackSpeed(speedFactor)
        }
    }

    private fun selectFont(key: String) {
        selectedFontKey = key
        selectedTypeface = try {
            when (key) {
                "BANGLA_SANS" -> Typeface.createFromAsset(assets, "fonts/bangla_sans_bold.ttf")
                "BANGLA_SERIF" -> Typeface.createFromAsset(assets, "fonts/bangla_serif_bold.ttf")
                "ITALIC" -> Typeface.createFromAsset(assets, "fonts/stylish_italic.ttf")
                "MONO" -> Typeface.createFromAsset(assets, "fonts/tech_mono.ttf")
                else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
        binding.watermarkOverlay.previewTypeface = selectedTypeface

        styleChip(binding.fontModern, key == "MODERN")
        styleChip(binding.fontBanglaSans, key == "BANGLA_SANS")
        styleChip(binding.fontBanglaSerif, key == "BANGLA_SERIF")
        styleChip(binding.fontItalic, key == "ITALIC")
        styleChip(binding.fontMono, key == "MONO")
    }

    private fun selectTextColor(color: Int) {
        selectedTextColor = color
        binding.watermarkOverlay.previewTextColor = color
        styleChip(binding.colorWhite, color == Color.WHITE)
        styleChip(binding.colorYellow, color == Color.parseColor("#FFD600"))
        styleChip(binding.colorCyan, color == Color.parseColor("#00E5FF"))
        styleChip(binding.colorRed, color == Color.parseColor("#FF5C72"))
        styleChip(binding.colorBlack, color == Color.BLACK)
        if (color != Color.parseColor("#FFD600")) binding.colorYellow.setTextColor(Color.parseColor("#FFD600"))
        if (color != Color.parseColor("#00E5FF")) binding.colorCyan.setTextColor(Color.parseColor("#00E5FF"))
        if (color != Color.parseColor("#FF5C72")) binding.colorRed.setTextColor(Color.parseColor("#FF5C72"))
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

        // Smooth continuous Speed Slider (0.50x to 2.00x in 0.05x steps!)
        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speedFactor = 0.50f + (progress * 0.05f)
                updateSpeedUi()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // Reverse Live Preview Toggle
        binding.btnPreviewReverse.setOnClickListener {
            isReversePreviewActive = !isReversePreviewActive
            if (isReversePreviewActive) {
                player?.pause()
                reversePreviewPosMs = (player?.currentPosition ?: videoDurationMs).let {
                    if (it <= 200L) videoDurationMs else it
                }
                binding.btnPreviewReverse.text = "Stop Reverse Preview"
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            } else {
                binding.btnPreviewReverse.text = "Play Reverse Preview"
                binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            }
        }

        // Add Text / Logo controls
        binding.etWatermarkText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.watermarkOverlay.previewText = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnPickLogo.setOnClickListener {
            if (selectedLogoBitmap != null) {
                selectedLogoBitmap = null
                binding.watermarkOverlay.previewLogoBitmap = null
                binding.btnPickLogo.text = "+ Logo"
            } else {
                pickLogoLauncher.launch(arrayOf("image/*"))
            }
        }

        binding.fontModern.setOnClickListener { selectFont("MODERN") }
        binding.fontBanglaSans.setOnClickListener { selectFont("BANGLA_SANS") }
        binding.fontBanglaSerif.setOnClickListener { selectFont("BANGLA_SERIF") }
        binding.fontItalic.setOnClickListener { selectFont("ITALIC") }
        binding.fontMono.setOnClickListener { selectFont("MONO") }

        binding.colorWhite.setOnClickListener { selectTextColor(Color.WHITE) }
        binding.colorYellow.setOnClickListener { selectTextColor(Color.parseColor("#FFD600")) }
        binding.colorCyan.setOnClickListener { selectTextColor(Color.parseColor("#00E5FF")) }
        binding.colorRed.setOnClickListener { selectTextColor(Color.parseColor("#FF5C72")) }
        binding.colorBlack.setOnClickListener { selectTextColor(Color.BLACK) }

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
            if (isReversePreviewActive) {
                isReversePreviewActive = false
                binding.btnPreviewReverse.text = "Play Reverse Preview"
                binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                return@setOnClickListener
            }
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
                    reversePreviewPosMs = pos
                    binding.tvCurrentTime.text = GalleryHelper.formatDuration(pos)
                    if (quickTool == null || quickTool == "TRIM") {
                        binding.watermarkOverlay.setCurrentPlayhead(pos)
                        binding.timelineView.setPlayhead(pos)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { isSeeking = false }
        })

        binding.btnAddArea.setOnClickListener {
            if (quickTool == null && currentMode == RemovalMode.CROP) {
                Toast.makeText(this, "Crop mode uses 1 frame", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (binding.watermarkOverlay.getBoxes().size >= MAX_BOXES) {
                Toast.makeText(this, "Maximum $MAX_BOXES items allowed", Toast.LENGTH_SHORT).show()
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
            savedOutputUri?.let { uri ->
                if (isAudioOutput) GalleryHelper.playAudio(this, uri) else GalleryHelper.playVideo(this, uri)
            }
        }

        binding.btnShareResult.setOnClickListener {
            savedOutputUri?.let { uri ->
                if (isAudioOutput) GalleryHelper.shareAudio(this, uri) else GalleryHelper.shareVideo(this, uri)
            }
        }

        binding.btnDoneResult.setOnClickListener { finish() }

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
            val prefix = if (quickTool == "TRIM") "Clip ${idx + 1}" else "#${idx + 1}"
            val chip = TextView(this).apply {
                text = if (isSelected) {
                    val rangeStr = "${GalleryHelper.formatDuration(box.startMs)}–${GalleryHelper.formatDuration(end)}"
                    if (boxes.size > 1) "$prefix $rangeStr ✕" else "$prefix $rangeStr"
                } else {
                    prefix
                }
                textSize = 11.5f
                isSingleLine = true
                gravity = Gravity.CENTER
                setPadding(
                    (10 * density).toInt(), (4 * density).toInt(),
                    (10 * density).toInt(), (4 * density).toInt()
                )
                setTextColor(if (isSelected) Color.parseColor("#04101D") else box.color)
                setTypeface(typeface, Typeface.BOLD)

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

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (32 * density).toInt()
                ).apply { marginEnd = (6 * density).toInt() }

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
                    val hasAud = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)

                    if (rot == 90 || rot == 270) {
                        videoWidth = h; videoHeight = w
                    } else {
                        videoWidth = w; videoHeight = h
                    }
                    if (dur > 0) videoDurationMs = dur
                    videoHasAudio = (hasAud == "yes")
                } catch (_: Exception) {
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }

            binding.tvVideoMeta.text =
                "${GalleryHelper.formatDuration(videoDurationMs)} • ${videoWidth}×${videoHeight}"
            binding.tvTotalTime.text = "/ ${GalleryHelper.formatDuration(videoDurationMs)}"
            binding.watermarkOverlay.setVideoDimensions(videoWidth, videoHeight)
            binding.watermarkOverlay.setVideoDuration(videoDurationMs)

            if (quickTool == null || quickTool == "TRIM") {
                extractFilmstripThumbnails()
            }

            val exo = ExoPlayer.Builder(this@EditorActivity).build().apply {
                setMediaItem(MediaItem.fromUri(videoUri))
                repeatMode = Player.REPEAT_MODE_ONE
                if (quickTool == "SPEED") setPlaybackSpeed(speedFactor)
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            val rot = videoSize.unappliedRotationDegrees
                            val w = if (rot == 90 || rot == 270) videoSize.height else videoSize.width
                            val h = if (rot == 90 || rot == 270) videoSize.width else videoSize.height
                            videoWidth = w; videoHeight = h
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
                    try { retriever.release() } catch (_: Exception) {}
                }
                list
            }
            if (thumbs.isNotEmpty()) binding.timelineView.setThumbnails(thumbs)
        }
    }

    private fun renderOverlayPng(boxW: Int, boxH: Int): File {
        val w = boxW.coerceIn(64, videoWidth)
        val h = boxH.coerceIn(40, videoHeight)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val logo = selectedLogoBitmap
        if (logo != null && !logo.isRecycled) {
            val dst = Rect(0, 0, w, h)
            canvas.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val text = binding.etWatermarkText.text?.toString()?.trim().orEmpty()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = selectedTextColor
                typeface = selectedTypeface
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f, 2f, 2f, Color.argb(200, 0, 0, 0))
            }
            var sizePx = (h * 0.62f).coerceAtLeast(20f)
            paint.textSize = sizePx
            val measured = paint.measureText(text)
            if (measured > w - 16f && measured > 0f) {
                sizePx *= ((w - 16f) / measured)
                paint.textSize = sizePx.coerceAtLeast(14f)
            }
            val fm = paint.fontMetrics
            val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, w / 2f, baseline, paint)
        }

        val pngFile = File(cacheDir, "wm_overlay_${System.currentTimeMillis()}.png")
        pngFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return pngFile
    }

    private fun startProcessing() {
        isReversePreviewActive = false
        player?.pause()
        binding.btnPlayPause.setImageResource(R.drawable.ic_play)

        val pixelBoxes = binding.watermarkOverlay.computePixelBoxes(videoWidth, videoHeight)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        isAudioOutput = (quickTool == "TO_AUDIO")

        val tempOut = if (isAudioOutput) {
            File(cacheDir, "vwatermark_audio_$stamp.m4a")
        } else {
            File(cacheDir, "vwatermark_$stamp.mp4")
        }

        val finalName = when (quickTool) {
            "FLIP" -> "VWatermark_Flip_$stamp.mp4"
            "COMPRESS" -> "VWatermark_Comp_$stamp.mp4"
            "TRIM" -> "VWatermark_Trim_$stamp.mp4"
            "SPEED" -> "VWatermark_Speed_$stamp.mp4"
            "ADD_TEXT" -> "VWatermark_Logo_$stamp.mp4"
            "TO_AUDIO" -> "VWatermark_Audio_$stamp.m4a"
            "REVERSE" -> "VWatermark_Rev_$stamp.mp4"
            else -> "VWatermark_$stamp.mp4"
        }

        val resolvedQuickCmd = when (quickTool) {
            "FLIP" -> when {
                flipH && flipV -> "FLIP_HV"
                flipV -> "FLIP_V"
                else -> "FLIP_H"
            }
            "COMPRESS" -> compressLevel
            else -> quickTool
        }

        val trimSegs = if (quickTool == "TRIM") {
            binding.watermarkOverlay.getBoxes().map { b ->
                val endMs = if (b.endMs > b.startMs) b.endMs else videoDurationMs
                WatermarkProcessor.TrimSegment(b.startMs / 1000f, endMs / 1000f)
            }
        } else emptyList()

        var pngFile: File? = null
        var overlayX = 20
        var overlayY = 20
        if (quickTool == "ADD_TEXT") {
            val labelText = binding.etWatermarkText.text?.toString()?.trim().orEmpty()
            if (selectedLogoBitmap == null && labelText.isBlank()) {
                Toast.makeText(this, "Enter text or pick a logo first", Toast.LENGTH_SHORT).show()
                return
            }
            val firstBox = pixelBoxes.first()
            pngFile = renderOverlayPng(firstBox.w, firstBox.h)
            overlayX = firstBox.x
            overlayY = firstBox.y
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
            "TRIM" -> "Trimming & merging clips…"
            "SPEED" -> String.format(Locale.US, "Changing speed (%.2fx)…", speedFactor)
            "ADD_TEXT" -> "Applying text / logo…"
            "TO_AUDIO" -> "Extracting audio…"
            "REVERSE" -> String.format(Locale.US, "Reversing video (%.2fx)…", speedFactor)
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
            hasAudio = videoHasAudio,
            trimSegments = trimSegs,
            speedFactor = speedFactor,
            overlayPngPath = pngFile?.absolutePath,
            overlayX = overlayX,
            overlayY = overlayY,
            onProgress = { pct ->
                runOnUiThread {
                    binding.circularProgress.progress = pct
                    binding.tvPercent.text = "$pct%"
                }
            },
            onComplete = { success, error ->
                pngFile?.delete()
                if (success) {
                    lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            val uri = if (isAudioOutput) {
                                GalleryHelper.saveAudioToMusic(this@EditorActivity, tempOut, finalName)
                            } else {
                                GalleryHelper.saveVideoToGallery(this@EditorActivity, tempOut, finalName)
                            }
                            tempOut.delete()
                            uri
                        }
                        savedOutputUri = saved
                        binding.circularProgress.progress = 100
                        binding.tvPercent.text = "100%"
                        binding.tvProcessStatus.text = if (isAudioOutput) {
                            "Saved to Music / VisionWatermark"
                        } else {
                            "Done! Saved to Gallery"
                        }
                        binding.tvSavedLabel.text = if (isAudioOutput) "Saved to Music" else getString(R.string.saved_to_gallery)
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
