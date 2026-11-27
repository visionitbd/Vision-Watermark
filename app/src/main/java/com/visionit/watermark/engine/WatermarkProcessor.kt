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
        muteAudio: Boolean = false,
        targetResolution: String = "ORIGINAL",
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

        val effectiveAudio = hasAudio && !muteAudio
        val commandArgs = if (quickToolCommand != null) {
            buildQuickToolArgs(
                inputPath = inputPath,
                outputPath = outputFile.absolutePath,
                cmd = quickToolCommand,
                hasAudio = effectiveAudio,
                targetResolution = targetResolution,
                trimSegments = trimSegments,
                speedFactor = speedFactor,
                overlayPngPath = overlayPngPath,
                overlayX = overlayX,
                overlayY = overlayY
            )
        } else {
            buildFfmpegArgs(inputPath, outputFile.absolutePath, mode, boxes, targetResolution)
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

    private fun scaleFilterFor(targetResolution: String): String? {
        return when (targetResolution) {
            "1080P" -> "scale='if(gte(iw,ih),1920,-2)':'if(gte(iw,ih),-2,1920)':flags=fast_bilinear,scale=trunc(iw/2)*2:trunc(ih/2)*2"
            "720P" -> "scale='if(gte(iw,ih),1280,-2)':'if(gte(iw,ih),-2,1280)':flags=fast_bilinear,scale=trunc(iw/2)*2:trunc(ih/2)*2"
            else -> null
        }
    }

    // Zero-hang, maximum-speed multi-threaded libx264 ultrafast encoding
    private fun ultrafastArgs(crf: String = "24"): List<String> = listOf(
        "-threads", "0",
        "-c:v", "libx264",
        "-preset", "ultrafast",
        "-crf", crf,
        "-pix_fmt", "yuv420p",
        "-movflags", "+faststart"
    )

    private fun buildQuickToolArgs(
        inputPath: String,
        outputPath: String,
        cmd: String,
        hasAudio: Boolean,
        targetResolution: String,
        trimSegments: List<TrimSegment>,
        speedFactor: Float,
        overlayPngPath: String?,
        overlayX: Int,
        overlayY: Int
    ): Array<String> {
        val scale = scaleFilterFor(targetResolution)

        return when (cmd) {
            "FLIP_H", "FLIP_V", "FLIP_HV" -> {
                val flip = when (cmd) {
                    "FLIP_V" -> "vflip"
                    "FLIP_HV" -> "hflip,vflip"
                    else -> "hflip"
                }
                val vf = if (scale != null) "$flip,$scale" else flip
                (listOf("-y", "-i", inputPath, "-vf", vf) + ultrafastArgs() + listOf("-c:a", "copy", outputPath)).toTypedArray()
            }
            "COMPRESS_LIGHT" -> (listOf("-y", "-i", inputPath) + ultrafastArgs("26") + listOf("-c:a", "copy", outputPath)).toTypedArray()
            "COMPRESS_BALANCED" -> (listOf("-y", "-i", inputPath, "-vf", "scale='min(1280,iw)':-2") + ultrafastArgs("29") + listOf("-c:a", "copy", outputPath)).toTypedArray()
            "COMPRESS_MAX" -> (listOf("-y", "-i", inputPath, "-vf", "scale='min(854,iw)':-2") + ultrafastArgs("33") + listOf("-c:a", "copy", outputPath)).toTypedArray()

            "TRIM" -> {
                val sorted = trimSegments.sortedBy { it.startSec }
                if (sorted.size <= 1) {
                    val seg = sorted.firstOrNull() ?: TrimSegment(0f, 10f)
                    val sStr = String.format(Locale.US, "%.2f", seg.startSec.coerceAtLeast(0f))
                    val dStr = String.format(Locale.US, "%.2f", (seg.endSec - seg.startSec).coerceAtLeast(0.4f))
                    // INSTANT zero-reencode stream copy (<0.5 sec!)
                    if (scale == null) {
                        arrayOf("-y", "-ss", sStr, "-i", inputPath, "-t", dStr, "-c", "copy", "-avoid_negative_ts", "make_zero", "-movflags", "+faststart", outputPath)
                    } else {
                        val args = mutableListOf("-y", "-ss", sStr, "-i", inputPath, "-t", dStr, "-vf", scale)
                        args += ultrafastArgs()
                        if (hasAudio) args += listOf("-c:a", "copy") else args += "-an"
                        args += outputPath
                        args.toTypedArray()
                    }
                } else {
                    val fc = StringBuilder()
                    val concatInputs = StringBuilder()
                    sorted.forEachIndexed { idx, seg ->
                        val sStr = String.format(Locale.US, "%.2f", seg.startSec.coerceAtLeast(0f))
                        val eStr = String.format(Locale.US, "%.2f", seg.endSec.coerceAtLeast(seg.startSec + 0.3f))
                        val vScale = if (scale != null) ",$scale" else ""
                        fc.append("[0:v]trim=start=$sStr:end=$eStr,setpts=PTS-STARTPTS$vScale[v$idx];")
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
                    args += ultrafastArgs()
                    args += outputPath
                    args.toTypedArray()
                }
            }

            "SPEED" -> {
                val safeSpeed = speedFactor.coerceIn(0.5f, 2.0f)
                val ptsFactor = String.format(Locale.US, "%.4f", 1f / safeSpeed)
                val tempoStr = String.format(Locale.US, "%.2f", safeSpeed)
                val vf = if (scale != null) "setpts=$ptsFactor*PTS,$scale" else "setpts=$ptsFactor*PTS"
                val args = mutableListOf("-y", "-i", inputPath, "-vf", vf)
                if (hasAudio) args += listOf("-af", "atempo=$tempoStr", "-c:a", "aac", "-b:a", "192k") else args += "-an"
                args += ultrafastArgs()
                args += outputPath
                args.toTypedArray()
            }

            "ADD_TEXT" -> {
                val png = overlayPngPath ?: ""
                val fc = if (scale != null) {
                    "[0:v][1:v]overlay=$overlayX:$overlayY,$scale[vout]"
                } else {
                    "[0:v][1:v]overlay=$overlayX:$overlayY[vout]"
                }
                (listOf("-y", "-i", inputPath, "-i", png, "-filter_complex", fc, "-map", "[vout]", "-map", "0:a?") +
                    ultrafastArgs() + listOf("-c:a", "copy", outputPath)).toTypedArray()
            }

            "TO_AUDIO" -> arrayOf("-y", "-i", inputPath, "-vn", "-c:a", "aac", "-b:a", "192k", outputPath)

            "REVERSE_PROXY" -> arrayOf(
                "-y", "-i", inputPath,
                "-vf", "scale='min(360,iw)':-2,fps=18,reverse",
                "-an", "-threads", "0", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30",
                "-pix_fmt", "yuv420p", outputPath
            )
            "REVERSE" -> {
                val safeSpeed = speedFactor.coerceIn(0.5f, 2.0f)
                val ptsFactor = String.format(Locale.US, "%.4f", 1f / safeSpeed)
                val tempoStr = String.format(Locale.US, "%.2f", safeSpeed)
                val baseVf = if (kotlin.math.abs(safeSpeed - 1f) > 0.03f) "reverse,setpts=$ptsFactor*PTS" else "reverse"
                val vf = if (scale != null) "$baseVf,$scale" else baseVf
                val args = mutableListOf("-y", "-i", inputPath, "-vf", vf)
                if (hasAudio) {
                    val af = if (kotlin.math.abs(safeSpeed - 1f) > 0.03f) "areverse,atempo=$tempoStr" else "areverse"
                    args += listOf("-af", af, "-c:a", "aac", "-b:a", "192k")
                } else {
                    args += "-an"
                }
                args += ultrafastArgs()
                args += outputPath
                args.toTypedArray()
            }
            else -> arrayOf("-y", "-i", inputPath, "-c", "copy", outputPath)
        }
    }

    private fun buildFfmpegArgs(
        inputPath: String,
        outputPath: String,
        mode: RemovalMode,
        boxes: List<WatermarkOverlayView.PixelBox>,
        targetResolution: String
    ): Array<String> {
        val args = mutableListOf("-y", "-i", inputPath)
        val scale = scaleFilterFor(targetResolution)

        when (mode) {
            RemovalMode.SMART_ERASE -> {
                val delogoChain = boxes.joinToString(",") { b ->
                    val enablePart = buildEnableClause(b)
                    "delogo=x=${b.x}:y=${b.y}:w=${b.w}:h=${b.h}:show=0$enablePart"
                }
                val vf = if (scale != null) "$delogoChain,$scale" else delogoChain
                args += listOf("-vf", vf)
            }

            RemovalMode.BLUR -> {
                val sb = StringBuilder()
                var currentLabel = "[0:v]"
                boxes.forEachIndexed { idx, b ->
                    val baseLabel = "[base$idx]"
                    val blurSrcLabel = "[bsrc$idx]"
                    val blurredLabel = "[blur$idx]"
                    val nextLabel = if (idx == boxes.lastIndex) (if (scale != null) "[vpre]" else "[vout]") else "[tmp$idx]"
                    val enablePart = buildEnableClause(b)

                    if (sb.isNotEmpty()) sb.append(";")
                    sb.append("${currentLabel}split=2${baseLabel}${blurSrcLabel};")
                    sb.append("${blurSrcLabel}crop=${b.w}:${b.h}:${b.x}:${b.y},boxblur=10:2${blurredLabel};")
                    sb.append("${baseLabel}${blurredLabel}overlay=${b.x}:${b.y}$enablePart$nextLabel")
                    currentLabel = nextLabel
                }
                if (scale != null) sb.append(";[vpre]$scale[vout]")
                args += listOf("-filter_complex", sb.toString(), "-map", "[vout]", "-map", "0:a?")
            }

            RemovalMode.CROP -> {
                val b = boxes.first()
                val evenW = (b.w / 2) * 2
                val evenH = (b.h / 2) * 2
                val base = "crop=$evenW:$evenH:${b.x}:${b.y},scale=trunc(iw/2)*2:trunc(ih/2)*2"
                val vf = if (scale != null) "$base,$scale" else base
                args += listOf("-vf", vf)
            }
        }

        args += ultrafastArgs()
        args += listOf("-c:a", "copy", outputPath)
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
