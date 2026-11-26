package com.visionit.watermark.engine

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.visionit.watermark.model.RemovalMode
import com.visionit.watermark.ui.WatermarkOverlayView
import java.io.File
import java.util.Locale

object WatermarkProcessor {

    private var activeSession: FFmpegSession? = null

    fun cancelCurrent() {
        activeSession?.let { FFmpegKit.cancel(it.sessionId) }
        activeSession = null
    }

    fun processVideo(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        mode: RemovalMode,
        boxes: List<WatermarkOverlayView.PixelBox>,
        durationMs: Long,
        quickToolCommand: String? = null,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val inputPath = try {
            FFmpegKitConfig.getSafParameterForRead(context, inputUri)
        } catch (e: Exception) {
            null
        } ?: copyToTempFile(context, inputUri)?.absolutePath

        if (inputPath == null) {
            onComplete(false, "Could not read selected video.")
            return
        }

        val commandArgs = if (quickToolCommand != null) {
            buildQuickToolArgs(inputPath, outputFile.absolutePath, quickToolCommand)
        } else {
            buildFfmpegArgs(inputPath, outputFile.absolutePath, mode, boxes)
        }

        activeSession = FFmpegKit.executeWithArgumentsAsync(
            commandArgs,
            { session ->
                activeSession = null
                val returnCode = session.returnCode
                when {
                    ReturnCode.isSuccess(returnCode) -> {
                        onProgress(100)
                        onComplete(true, null)
                    }
                    ReturnCode.isCancel(returnCode) -> {
                        outputFile.delete()
                        onComplete(false, "Cancelled")
                    }
                    else -> {
                        outputFile.delete()
                        onComplete(false, session.failStackTrace ?: "FFmpeg failed (code $returnCode)")
                    }
                }
            },
            { /* logs */ },
            { stats ->
                if (durationMs > 0) {
                    val timeMs = stats.time.toLong()
                    val pct = ((timeMs * 100L) / durationMs).toInt().coerceIn(0, 99)
                    onProgress(pct)
                }
            }
        )
    }

    private fun buildQuickToolArgs(
        inputPath: String,
        outputPath: String,
        cmd: String
    ): Array<String> {
        return when (cmd) {
            "FLIP_H" -> arrayOf(
                "-y", "-i", inputPath,
                "-vf", "hflip",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart",
                outputPath
            )
            "FLIP_V" -> arrayOf(
                "-y", "-i", inputPath,
                "-vf", "vflip",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart",
                outputPath
            )
            "FLIP_HV" -> arrayOf(
                "-y", "-i", inputPath,
                "-vf", "hflip,vflip",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart",
                outputPath
            )
            "COMPRESS_LIGHT" -> arrayOf(
                "-y", "-i", inputPath,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "24",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart",
                outputPath
            )
            "COMPRESS_BALANCED" -> arrayOf(
                "-y", "-i", inputPath,
                "-vf", "scale='min(1280,iw)':-2",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "28",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart",
                outputPath
            )
            "COMPRESS_MAX" -> arrayOf(
                "-y", "-i", inputPath,
                "-vf", "scale='min(854,iw)':-2",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "32",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart",
                outputPath
            )
            else -> arrayOf(
                "-y", "-i", inputPath,
                "-c", "copy", outputPath
            )
        }
    }

    private fun buildFfmpegArgs(
        inputPath: String,
        outputPath: String,
        mode: RemovalMode,
        boxes: List<WatermarkOverlayView.PixelBox>
    ): Array<String> {
        val args = mutableListOf(
            "-y",
            "-i", inputPath
        )

        when (mode) {
            RemovalMode.SMART_ERASE -> {
                val filters = boxes.joinToString(",") { b ->
                    val enablePart = buildEnableClause(b)
                    "delogo=x=${b.x}:y=${b.y}:w=${b.w}:h=${b.h}:show=0$enablePart"
                }
                args += listOf("-vf", filters)
            }

            RemovalMode.BLUR -> {
                val sb = StringBuilder()
                var currentLabel = "[0:v]"
                boxes.forEachIndexed { idx, b ->
                    val baseLabel = "[base$idx]"
                    val blurSrcLabel = "[bsrc$idx]"
                    val blurredLabel = "[blur$idx]"
                    val nextLabel = if (idx == boxes.lastIndex) "[vout]" else "[tmp$idx]"
                    val enablePart = buildEnableClause(b)

                    if (sb.isNotEmpty()) sb.append(";")
                    sb.append("${currentLabel}split=2${baseLabel}${blurSrcLabel};")
                    sb.append("${blurSrcLabel}crop=${b.w}:${b.h}:${b.x}:${b.y},boxblur=10:2${blurredLabel};")
                    sb.append("${baseLabel}${blurredLabel}overlay=${b.x}:${b.y}$enablePart$nextLabel")
                    currentLabel = nextLabel
                }
                args += listOf(
                    "-filter_complex", sb.toString(),
                    "-map", "[vout]",
                    "-map", "0:a?"
                )
            }

            RemovalMode.CROP -> {
                val b = boxes.first()
                val evenW = (b.w / 2) * 2
                val evenH = (b.h / 2) * 2
                args += listOf("-vf", "crop=$evenW:$evenH:${b.x}:${b.y},scale=trunc(iw/2)*2:trunc(ih/2)*2")
            }
        }

        args += listOf(
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "20",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart",
            outputPath
        )

        return args.toTypedArray()
    }

    private fun buildEnableClause(b: WatermarkOverlayView.PixelBox): String {
        if (!b.useTimeRange) return ""
        val startStr = String.format(Locale.US, "%.2f", b.startSec.coerceAtLeast(0f))
        return if (b.endSec > b.startSec) {
            val endStr = String.format(Locale.US, "%.2f", b.endSec)
            ":enable='between(t,$startStr,$endStr)'"
        } else {
            ":enable='gte(t,$startStr)'"
        }
    }

    private fun copyToTempFile(context: Context, uri: Uri): File? {
        return try {
            val temp = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            temp
        } catch (e: Exception) {
            null
        }
    }
}
