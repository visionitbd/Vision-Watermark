# Vision Watermark (V-Watermark)

Offline Android Video Watermark Remover built with Kotlin, Media3 ExoPlayer, and FFmpegKit (`dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl:8.1.9`).

## Features
- **3 Removal Modes**: Smart Erase (`delogo`), Smooth Gaussian Blur, and Crop.
- **Multi-Box & Moving Watermarks**: Place up to 5 resizable boxes and optionally assign Start–End time ranges per box.
- **Instant Start & Fast Export**: Direct SAF read + `libx264 ultrafast` + lossless audio stream copy (`-c:a copy`).
- **Gallery Integration**: Saves directly to `Movies/VisionWatermark` with in-app Recent Exports list.

## Build APK via GitHub Actions
1. Create a new repository on GitHub and upload all files from this folder.
2. **Important**: If uploading via browser drag-and-drop, make sure `.github/workflows/build-apk.yml` is present (browser drag-and-drop skips hidden `.github` folders — add it via **Add file → Create new file** if missing).
3. Open the **Actions** tab → click **Build V-Watermark APK** → wait ~2–3 minutes.
4. Download **V-Watermark-APK** from Artifacts, unzip it, and install `V-Watermark-v1.0.apk` on your phone.
