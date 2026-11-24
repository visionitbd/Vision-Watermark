package com.visionit.watermark

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.visionit.watermark.databinding.ActivityMainBinding
import com.visionit.watermark.ui.RecentExportsAdapter
import com.visionit.watermark.util.GalleryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RecentExportsAdapter

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            val intent = Intent(this, EditorActivity::class.java).apply {
                putExtra(EditorActivity.EXTRA_VIDEO_URI, uri.toString())
            }
            startActivity(intent)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
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

        binding.rvRecentExports.layoutManager = LinearLayoutManager(this)
        binding.rvRecentExports.adapter = adapter

        binding.btnSelectVideo.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*"))
        }

        requestGalleryPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshRecentExports()
    }

    private fun requestGalleryPermissionIfNeeded() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(perm)
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
