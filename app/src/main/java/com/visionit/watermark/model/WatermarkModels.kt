package com.visionit.watermark.model

import android.graphics.Color
import android.net.Uri

enum class RemovalMode {
    SMART_ERASE,
    BLUR,
    CROP
}

object BoxColors {
    val PALETTE = intArrayOf(
        Color.parseColor("#FFB324"), // #1 Amber Gold
        Color.parseColor("#00E5C3"), // #2 Cyan Mint
        Color.parseColor("#A77BFF"), // #3 Violet
        Color.parseColor("#FF5C93"), // #4 Rose Pink
        Color.parseColor("#4DA6FF")  // #5 Sky Blue
    )

    fun colorForIndex(idx: Int): Int = PALETTE[idx % PALETTE.size]
}

/**
 * Coordinates are normalized (0f..1f) relative to the rendered video frame.
 * startMs / endMs define the exact time window where this box is active.
 */
data class WatermarkBox(
    val id: Int,
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float,
    var color: Int,
    var startMs: Long = 0L,
    var endMs: Long = 0L // 0 or >= duration means full video
)

data class ExportedVideoItem(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long
)
