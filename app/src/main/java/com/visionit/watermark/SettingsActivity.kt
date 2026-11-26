package com.visionit.watermark

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.visionit.watermark.databinding.ActivitySettingsBinding
import com.visionit.watermark.util.PremiumPopupHelper

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "Version 7.0"
        binding.btnBack.setOnClickListener { finish() }

        binding.btnGetPremium.setOnClickListener {
            PremiumPopupHelper.show(this)
        }

        binding.rowShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Remove watermarks from videos & photos offline with Vision Watermark!"
                )
            }
            startActivity(Intent.createChooser(shareIntent, "Share Vision Watermark"))
        }

        binding.rowRateUs.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Rate Vision Watermark")
                .setMessage("আমাদের অ্যাপটি ব্যবহার করার জন্য ধন্যবাদ! আপনার মূল্যবান মতামত আমাদের আরও ভালো করতে সাহায্য করবে।")
                .setPositiveButton("ধন্যবাদ") { _, _ ->
                    Toast.makeText(this, "Thanks for your support!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("পরে", null)
                .show()
        }
    }
}
