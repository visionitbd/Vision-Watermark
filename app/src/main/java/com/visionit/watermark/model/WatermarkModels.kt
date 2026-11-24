package com.visionit.watermark.model

import android.net.Uri

enum class RemovalMode {
    SMART_ERASE,
    BLUR,
    CROP
}

/**
 * Coordinates are normalized (0f..1f) relative to the rendered video frame.
 */
data class WatermarkBox(
    val id: Int,
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float,
    var useTimeRange: Boolean = false,
    var startSec: Float = 0f,
    var endSec: Float = -1f // -1 means until end of video
)

data class ExportedVideoItem(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long
)
