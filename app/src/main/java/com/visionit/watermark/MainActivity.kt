package com.visionit.watermark

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.visionit.watermark.databinding.ActivityMainBinding
import com.visionit.watermark.ui.RecentExportsAdapter
import com.visionit.watermark.util.GalleryHelper
import com.visionit.watermark.util.PremiumPopupHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RecentExportsAdapter
    private var pendingEditorMode: String = "SMART_ERASE"
    private var pendingQuickTool: String? = null
    private var pendingPhotoMode: String = "SMART_ERASE"

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val intent = Intent(this, EditorActivity::class.java).apply {
                putExtra(EditorActivity.EXTRA_VIDEO_URI, uri.toString())
                putExtra(EditorActivity.EXTRA_INITIAL_MODE, pendingEditorMode)
                if (pendingQuickTool != null) {
                    putExtra(EditorActivity.EXTRA_QUICK_TOOL, pendingQuickTool)
                }
            }
            pendingQuickTool = null
            startActivity(intent)
        }
    }

    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val intent = Intent(this, PhotoEditorActivity::class.java).apply {
                putExtra(PhotoEditorActivity.EXTRA_PHOTO_URI, uri.toString())
                putExtra(PhotoEditorActivity.EXTRA_INITIAL_MODE, pendingPhotoMode)
            }
            startActivity(intent)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshRecentExports()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RecentExportsAdapter(
            scope = lifecycleScope,
            onPlay = { GalleryHelper.playVideo(this, it.uri) },
            onShare = { GalleryHelper.shareVideo(this, it.uri) }
        )

        // Single-column Premium Horizontal Cards for My Videos!
        binding.rvRecentExports.layoutManager = LinearLayoutManager(this)
        binding.rvRecentExports.adapter = adapter

        // Top bar Settings & Premium Crown
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnPremiumCrown.setOnClickListener {
            PremiumPopupHelper.show(this)
        }

        // Hero buttons
        binding.btnChooseVideo.setOnClickListener { openVideoPicker("SMART_ERASE", null) }
        binding.btnChoosePhoto.setOnClickListener { openPhotoPicker("SMART_ERASE") }

        // Row 1
        binding.toolWatermark.setOnClickListener { openVideoPicker("SMART_ERASE", null) }
        binding.toolPhotoErase.setOnClickListener { openPhotoPicker("SMART_ERASE") }
        binding.toolEditText.setOnClickListener { openPhotoPicker("EDIT_TEXT") }
        binding.toolCrop.setOnClickListener { openVideoPicker("CROP", null) }

        // Row 2 (Flip & Compress now support BOTH Video & Photo!)
        binding.toolBlur.setOnClickListener { openVideoPicker("BLUR", null) }
        binding.toolFlip.setOnClickListener { showMediaTypeChooser("Flip Video / Photo", "FLIP", "FLIP") }
        binding.toolCompress.setOnClickListener { showMediaTypeChooser("Compress Video / Photo", "COMPRESS", "COMPRESS") }
        binding.toolTrim.setOnClickListener { openVideoPicker("SMART_ERASE", "TRIM") }

        // Row 3 (Add Text now supports BOTH Video & Photo!)
        binding.toolSpeed.setOnClickListener { openVideoPicker("SMART_ERASE", "SPEED") }
        binding.toolAddLogo.setOnClickListener { showMediaTypeChooser("Add Text / Logo", "ADD_TEXT", "ADD_TEXT") }
        binding.toolAudio.setOnClickListener { openVideoPicker("SMART_ERASE", "TO_AUDIO") }
        binding.toolReverse.setOnClickListener { openVideoPicker("SMART_ERASE", "REVERSE") }

        // Bottom nav
        binding.navHome.setOnClickListener { switchTab(showHome = true) }
        binding.navVideos.setOnClickListener { switchTab(showHome = false) }

        requestGalleryPermissionsIfNeeded()
    }

    private fun showMediaTypeChooser(title: String, videoTool: String, photoTool: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_choose_media, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val w = (resources.displayMetrics.widthPixels * 0.84f).toInt()
        dialog.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)

        view.findViewById<TextView>(R.id.tvChooserTitle).text = title
        view.findViewById<TextView>(R.id.btnPickVideoOpt).setOnClickListener {
            dialog.dismiss()
            openVideoPicker("SMART_ERASE", videoTool)
        }
        view.findViewById<TextView>(R.id.btnPickPhotoOpt).setOnClickListener {
            dialog.dismiss()
            openPhotoPicker(photoTool)
        }
        dialog.show()
    }

    private fun openVideoPicker(initialMode: String, quickTool: String?) {
        pendingEditorMode = initialMode
        pendingQuickTool = quickTool
        pickVideoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    private fun openPhotoPicker(photoMode: String) {
        pendingPhotoMode = photoMode
        pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun switchTab(showHome: Boolean) {
        binding.homeTabContainer.visibility = if (showHome) View.VISIBLE else View.GONE
        binding.videosTabContainer.visibility = if (showHome) View.GONE else View.VISIBLE

        val activeColor = ContextCompat.getColor(this, R.color.cyan_accent)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        binding.ivNavHome.setColorFilter(if (showHome) activeColor else inactiveColor)
        binding.tvNavHome.setTextColor(if (showHome) activeColor else inactiveColor)
        binding.ivNavVideos.setColorFilter(if (!showHome) activeColor else inactiveColor)
        binding.tvNavVideos.setTextColor(if (!showHome) activeColor else inactiveColor)

        if (!showHome) refreshRecentExports()
    }

    override fun onResume() {
        super.onResume()
        refreshRecentExports()
    }

    private fun requestGalleryPermissionsIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun refreshRecentExports() {
        lifecycleScope.launch {
            val exports = withContext(Dispatchers.IO) {
                GalleryHelper.loadRecentExports(this@MainActivity)
            }
            adapter.submitList(exports)
            binding.tvEmptyExports.visibility = if (exports.isEmpty()) View.VISIBLE else View.GONE
            binding.rvRecentExports.visibility = if (exports.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
