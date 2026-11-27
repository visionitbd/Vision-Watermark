package com.visionit.watermark.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.visionit.watermark.databinding.ItemRecentExportBinding
import com.visionit.watermark.model.ExportedVideoItem
import com.visionit.watermark.util.GalleryHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecentExportsAdapter(
    private val scope: CoroutineScope,
    private val onPlay: (ExportedVideoItem) -> Unit,
    private val onShare: (ExportedVideoItem) -> Unit
) : RecyclerView.Adapter<RecentExportsAdapter.ViewHolder>() {

    private val items = mutableListOf<ExportedVideoItem>()
    private val thumbCache = mutableMapOf<String, Bitmap>()

    fun submitList(newItems: List<ExportedVideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentExportBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemRecentExportBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExportedVideoItem) {
            binding.tvDurationBadge.text = GalleryHelper.formatDuration(item.durationMs)
            binding.tvSizeBadge.text = GalleryHelper.formatSize(item.sizeBytes)

            val key = item.uri.toString()
            val cached = thumbCache[key]
            if (cached != null) {
                binding.ivThumbnail.setImageBitmap(cached)
            } else {
                binding.ivThumbnail.setImageBitmap(null)
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(binding.root.context, item.uri)
                            retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } catch (_: Exception) {
                            null
                        } finally {
                            try { retriever.release() } catch (_: Exception) {}
                        }
                    }
                    if (bmp != null) {
                        thumbCache[key] = bmp
                        binding.ivThumbnail.setImageBitmap(bmp)
                    }
                }
            }

            binding.root.setOnClickListener { onPlay(item) }
            binding.btnPlayItem.setOnClickListener { onPlay(item) }
            binding.btnShareItem.setOnClickListener { onShare(item) }
        }
    }
}
