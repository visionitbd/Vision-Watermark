package com.visionit.watermark

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
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
    }

    private lateinit var binding: ActivityEditorBinding
    private var player: ExoPlayer? = null
    private lateinit var videoUri: Uri

    private var videoWidth = 1280
    private var videoHeight = 720
    private var videoDurationMs = 0L
    private var currentMode = RemovalMode.SMART_ERASE
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
                binding.tvTime.text =
                    "${GalleryHelper.formatDuration(pos)} / ${GalleryHelper.formatDuration(dur)}"
            }
            handler.postDelayed(this, 100)
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

        setupControls()
        loadVideoMetadataAndPlayer()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.watermarkOverlay.onSelectionChanged = {
            syncBoxControls()
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
                    player?.seekTo(progress.toLong())
                    binding.tvTime.text =
                        "${GalleryHelper.formatDuration(progress.toLong())} / ${GalleryHelper.formatDuration(videoDurationMs)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
            }
        })

        binding.btnAddBox.setOnClickListener {
            if (currentMode == RemovalMode.CROP) {
                Toast.makeText(this, "Crop mode uses a single frame", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (binding.watermarkOverlay.getBoxes().size >= 5) {
                Toast.makeText(this, "Maximum 5 watermark boxes supported", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.watermarkOverlay.addDefaultBox()
        }

        binding.btnRemoveBox.setOnClickListener {
            if (!binding.watermarkOverlay.removeSelectedBox()) {
                Toast.makeText(this, "At least 1 box is required", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchTimeRange.setOnCheckedChangeListener { _, isChecked ->
            val box = binding.watermarkOverlay.getSelectedBox() ?: return@setOnCheckedChangeListener
            box.useTimeRange = isChecked
            binding.timeRangePanel.visibility = if (isChecked) View.VISIBLE else View.GONE
            syncTimeRangeButtons()
        }

        binding.btnSetStart.setOnClickListener {
            val box = binding.watermarkOverlay.getSelectedBox() ?: return@setOnClickListener
            val sec = (player?.currentPosition ?: 0L) / 1000f
            box.startSec = sec
            if (box.endSec > 0f && box.endSec <= box.startSec) {
                box.endSec = -1f
            }
            syncTimeRangeButtons()
        }

        binding.btnSetEnd.setOnClickListener {
            val box = binding.watermarkOverlay.getSelectedBox() ?: return@setOnClickListener
            val sec = (player?.currentPosition ?: 0L) / 1000f
            if (sec <= box.startSec) {
                Toast.makeText(this, "End time must be after Start time", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            box.endSec = sec
            syncTimeRangeButtons()
        }

        binding.btnResetRange.setOnClickListener {
            val box = binding.watermarkOverlay.getSelectedBox() ?: return@setOnClickListener
            box.startSec = 0f
            box.endSec = -1f
            syncTimeRangeButtons()
        }

        binding.chipSmartErase.setOnClickListener { selectMode(RemovalMode.SMART_ERASE) }
        binding.chipBlur.setOnClickListener { selectMode(RemovalMode.BLUR) }
        binding.chipCrop.setOnClickListener { selectMode(RemovalMode.CROP) }

        binding.btnRemoveWatermark.setOnClickListener { startWatermarkRemoval() }

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

        syncBoxControls()
    }

    private fun selectMode(mode: RemovalMode) {
        currentMode = mode
        binding.watermarkOverlay.mode = mode

        fun styleChip(view: android.widget.TextView, active: Boolean) {
            view.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            view.setTextColor(if (active) Color.parseColor("#04101D") else Color.parseColor("#F4F8FF"))
        }

        styleChip(binding.chipSmartErase, mode == RemovalMode.SMART_ERASE)
        styleChip(binding.chipBlur, mode == RemovalMode.BLUR)
        styleChip(binding.chipCrop, mode == RemovalMode.CROP)

        val isCrop = mode == RemovalMode.CROP
        binding.switchTimeRange.isEnabled = !isCrop
        if (isCrop) {
            binding.switchTimeRange.isChecked = false
            binding.timeRangePanel.visibility = View.GONE
        }
        syncBoxControls()
    }

    private fun syncBoxControls() {
        val count = binding.watermarkOverlay.getBoxes().size
        binding.tvBoxCount.text = if (currentMode == RemovalMode.CROP) "Crop Frame" else "$count Box${if (count > 1) "es" else ""}"
        val box = binding.watermarkOverlay.getSelectedBox() ?: return
        binding.switchTimeRange.isChecked = box.useTimeRange && currentMode != RemovalMode.CROP
        binding.timeRangePanel.visibility =
            if (box.useTimeRange && currentMode != RemovalMode.CROP) View.VISIBLE else View.GONE
        syncTimeRangeButtons()
    }

    private fun syncTimeRangeButtons() {
        val box = binding.watermarkOverlay.getSelectedBox() ?: return
        binding.btnSetStart.text = String.format(Locale.US, "Start: %.1fs", box.startSec)
        binding.btnSetEnd.text = if (box.endSec > 0f) {
            String.format(Locale.US, "End: %.1fs", box.endSec)
        } else {
            "End: Full"
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
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

                    // Swap dimensions for 90/270 degree portrait videos so overlay matches FFmpeg auto-rotated output
                    if (rot == 90 || rot == 270) {
                        videoWidth = h
                        videoHeight = w
                    } else {
                        videoWidth = w
                        videoHeight = h
                    }
                    videoDurationMs = dur
                } catch (_: Exception) {
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            }

            binding.watermarkOverlay.setVideoDimensions(videoWidth, videoHeight)

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
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && duration > 0) {
                            videoDurationMs = duration
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

    private fun startWatermarkRemoval() {
        player?.pause()
        binding.btnPlayPause.setImageResource(R.drawable.ic_play)

        val pixelBoxes = binding.watermarkOverlay.computePixelBoxes(videoWidth, videoHeight)
        val tempOut = File(cacheDir, "vwatermark_${System.currentTimeMillis()}.mp4")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val finalName = "VWatermark_$stamp.mp4"

        savedOutputUri = null
        binding.processingOverlay.visibility = View.VISIBLE
        binding.resultCard.visibility = View.GONE
        binding.btnCancelProcess.visibility = View.VISIBLE
        binding.circularProgress.progress = 0
        binding.tvPercent.text = "0%"
        binding.tvProcessStatus.text = getString(R.string.processing_status)

        WatermarkProcessor.processVideo(
            context = this,
            inputUri = videoUri,
            outputFile = tempOut,
            mode = currentMode,
            boxes = pixelBoxes,
            durationMs = videoDurationMs,
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
                        binding.tvProcessStatus.text = "Watermark removed!"
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
