package com.visionit.watermark

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.visionit.watermark.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnGetPremium.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Vision Watermark Pro")
                .setMessage("আপাতত সম্পূর্ণ ফ্রি!\n\nবর্তমানে কোনো প্রিমিয়াম সাবস্ক্রিপশন ছাড়াই অ্যাপের সব প্রো ফিচার সম্পূর্ণ ফ্রিতে ব্যবহার করুন।")
                .setPositiveButton("বন্ধ করুন", null)
                .show()
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
