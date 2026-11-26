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

    data class TrimSegment(val startSec: Float, val endSec: Float)

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
        hasAudio: Boolean = true,
        trimSegments: List<TrimSegment> = emptyList(),
        speedFactor: Float = 1f,
        overlayPngPath: String? = null,
        overlayX: Int = 20,
        overlayY: Int = 20,
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
            buildQuickToolArgs(
                inputPath = inputPath,
                outputPath = outputFile.absolutePath,
                cmd = quickToolCommand,
                hasAudio = hasAudio,
                trimSegments = trimSegments,
                speedFactor = speedFactor,
                overlayPngPath = overlayPngPath,
                overlayX = overlayX,
                overlayY = overlayY
            )
        } else {
            buildFfmpegArgs(inputPath, outputFile.absolutePath, mode, boxes)
        }

        val totalTrimMs = trimSegments.sumOf { ((it.endSec - it.startSec) * 1000f).toLong().coerceAtLeast(100L) }
        val effectiveDurationMs = when (quickToolCommand) {
            "TRIM" -> totalTrimMs.coerceAtLeast(500L)
            "SPEED", "REVERSE" -> (durationMs / speedFactor).toLong().coerceAtLeast(500L)
            else -> durationMs
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
                if (effectiveDurationMs > 0) {
                    val timeMs = stats.time.toLong()
                    val pct = ((timeMs * 100L) / effectiveDurationMs).toInt().coerceIn(0, 99)
                    onProgress(pct)
                }
            }
        )
    }

    private fun buildQuickToolArgs(
        inputPath: String,
        outputPath: String,
        cmd: String,
        hasAudio: Boolean,
        trimSegments: List<TrimSegment>,
        speedFactor: Float,
        overlayPngPath: String?,
        overlayX: Int,
        overlayY: Int
    ): Array<String> {
        return when (cmd) {
            "FLIP_H" -> arrayOf(
                "-y", "-i", inputPath, "-vf", "hflip",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", outputPath
            )
            "FLIP_V" -> arrayOf(
                "-y", "-i", inputPath, "-vf", "vflip",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", outputPath
            )
            "FLIP_HV" -> arrayOf(
                "-y", "-i", inputPath, "-vf", "hflip,vflip",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", outputPath
            )
            "COMPRESS_LIGHT" -> arrayOf(
                "-y", "-i", inputPath,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "24",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", outputPath
            )
            "COMPRESS_BALANCED" -> arrayOf(
                "-y", "-i", inputPath, "-vf", "scale='min(1280,iw)':-2",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "28",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", outputPath
            )
            "COMPRESS_MAX" -> arrayOf(
                "-y", "-i", inputPath, "-vf", "scale='min(854,iw)':-2",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "32",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", outputPath
            )
            "TRIM" -> {
                val sorted = trimSegments.sortedBy { it.startSec }
                if (sorted.size <= 1) {
                    val seg = sorted.firstOrNull() ?: TrimSegment(0f, 10f)
                    val sStr = String.format(Locale.US, "%.2f", seg.startSec.coerceAtLeast(0f))
                    val dStr = String.format(Locale.US, "%.2f", (seg.endSec - seg.startSec).coerceAtLeast(0.4f))
                    val args = mutableListOf(
                        "-y", "-ss", sStr, "-i", inputPath, "-t", dStr,
                        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20", "-pix_fmt", "yuv420p"
                    )
                    if (hasAudio) args += listOf("-c:a", "aac", "-b:a", "192k") else args += "-an"
                    args += listOf("-movflags", "+faststart", outputPath)
                    args.toTypedArray()
                } else {
                    // Multi-cut concat of multiple kept segments!
                    val fc = StringBuilder()
                    val concatInputs = StringBuilder()
                    sorted.forEachIndexed { idx, seg ->
                        val sStr = String.format(Locale.US, "%.2f", seg.startSec.coerceAtLeast(0f))
                        val eStr = String.format(Locale.US, "%.2f", seg.endSec.coerceAtLeast(seg.startSec + 0.3f))
                        fc.append("[0:v]trim=start=$sStr:end=$eStr,setpts=PTS-STARTPTS[v$idx];")
                        if (hasAudio) {
                            fc.append("[0:a]atrim=start=$sStr:end=$eStr,asetpts=PTS-STARTPTS[a$idx];")
                            concatInputs.append("[v$idx][a$idx]")
                        } else {
                            concatInputs.append("[v$idx]")
                        }
                    }
                    if (hasAudio) {
                        fc.append("${concatInputs}concat=n=${sorted.size}:v=1:a=1[vout][aout]")
                    } else {
                        fc.append("${concatInputs}concat=n=${sorted.size}:v=1:a=0[vout]")
                    }
                    val args = mutableListOf("-y", "-i", inputPath, "-filter_complex", fc.toString(), "-map", "[vout]")
                    if (hasAudio) args += listOf("-map", "[aout]", "-c:a", "aac", "-b:a", "192k") else args += "-an"
                    args += listOf(
                        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                        "-pix_fmt", "yuv420p", "-movflags", "+faststart", outputPath
                    )
                    args.toTypedArray()
                }
            }
            "SPEED" -> {
                val safeSpeed = speedFactor.coerceIn(0.5f, 2.0f)
                val ptsFactor = String.format(Locale.US, "%.4f", 1f / safeSpeed)
                val tempoStr = String.format(Locale.US, "%.2f", safeSpeed)
                val args = mutableListOf("-y", "-i", inputPath, "-vf", "setpts=$ptsFactor*PTS")
                if (hasAudio) args += listOf("-af", "atempo=$tempoStr", "-c:a", "aac", "-b:a", "192k") else args += "-an"
                args += listOf(
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                    "-pix_fmt", "yuv420p", "-movflags", "+faststart", outputPath
                )
                args.toTypedArray()
            }
            "ADD_TEXT" -> {
                val png = overlayPngPath ?: ""
                arrayOf(
                    "-y", "-i", inputPath, "-i", png,
                    "-filter_complex", "[0:v][1:v]overlay=$overlayX:$overlayY",
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                    "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", outputPath
                )
            }
            "TO_AUDIO" -> arrayOf(
                "-y", "-i", inputPath, "-vn", "-c:a", "aac", "-b:a", "192k", outputPath
            )
            "REVERSE" -> {
                val safeSpeed = speedFactor.coerceIn(0.5f, 2.0f)
                val ptsFactor = String.format(Locale.US, "%.4f", 1f / safeSpeed)
                val tempoStr = String.format(Locale.US, "%.2f", safeSpeed)
                val vf = if (kotlin.math.abs(safeSpeed - 1f) > 0.03f) "reverse,setpts=$ptsFactor*PTS" else "reverse"
                val args = mutableListOf("-y", "-i", inputPath, "-vf", vf)
                if (hasAudio) {
                    val af = if (kotlin.math.abs(safeSpeed - 1f) > 0.03f) "areverse,atempo=$tempoStr" else "areverse"
                    args += listOf("-af", af, "-c:a", "aac", "-b:a", "192k")
                } else {
                    args += "-an"
                }
                args += listOf(
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                    "-pix_fmt", "yuv420p", "-movflags", "+faststart", outputPath
                )
                args.toTypedArray()
            }
            else -> arrayOf("-y", "-i", inputPath, "-c", "copy", outputPath)
        }
    }

    private fun buildFfmpegArgs(
        inputPath: String,
        outputPath: String,
        mode: RemovalMode,
        boxes: List<WatermarkOverlayView.PixelBox>
    ): Array<String> {
        val args = mutableListOf("-y", "-i", inputPath)

        when (mode) {
            RemovalMode.SMART_ERASE -> {
                // Tight delogo + mild unsharp texture recovery so erased area looks cleaner
                val delogoChain = boxes.joinToString(",") { b ->
                    val enablePart = buildEnableClause(b)
                    "delogo=x=${b.x}:y=${b.y}:w=${b.w}:h=${b.h}:show=0$enablePart"
                }
                args += listOf("-vf", "$delogoChain,unsharp=3:3:0.35:3:3:0.0")
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
                args += listOf("-filter_complex", sb.toString(), "-map", "[vout]", "-map", "0:a?")
            }

            RemovalMode.CROP -> {
                val b = boxes.first()
                val evenW = (b.w / 2) * 2
                val evenH = (b.h / 2) * 2
                args += listOf("-vf", "crop=$evenW:$evenH:${b.x}:${b.y},scale=trunc(iw/2)*2:trunc(ih/2)*2")
            }
        }

        args += listOf(
            "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
            "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", outputPath
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
                temp.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            }
            temp
        } catch (e: Exception) {
            null
        }
    }
}
