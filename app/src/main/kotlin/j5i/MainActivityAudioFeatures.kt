package j5i.spatial8d

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.TextView
import com.j5i.spatial8d.MicWavRecorder
import j5i.spatial8d.ui.VideoExport
import j5i.spatial8d.ui.ThemeUi
import j5i.spatial8d.ui.GifExport
import java.io.File

internal fun MainActivity.updateExportProgress(message: String) {
    playbackStatus = message
    exportProgressText?.text = message
    refreshPlayback()
}

internal fun MainActivity.showExportProgress() {
    if (exportProgressDialog?.isShowing == true) return
    val dialog = ThemeUi.showSheet(this, palette, "导出进度", subtitle = "可关闭此窗口，导出会继续") {
        addView(ThemeUi.text(this@showExportProgress, playbackStatus, 15f, palette).also { exportProgressText = it })
    }
    exportProgressDialog = dialog
    dialog.setOnDismissListener { exportProgressText = null; exportProgressDialog = null }
    dialog.show()
}

internal fun MainActivity.exportMp4(uri: Uri?, landscape: Boolean, resolution: Int, startSeconds: Double, durationSeconds: Double, gif: Boolean = false, preview: Boolean = false, smoothGifLoop: Boolean = false, onComplete: () -> Unit = {}) {
    if (exporting) { showExportProgress(); return }
    val player = service?.player ?: return
    val state = visualizerView.exportState()
    val lyrics = currentLyrics
    val speed = player.transform.speed
    val reversed = player.reverse
    val wav = File.createTempFile("8d-export-", ".wav", cacheDir)
    val video = File.createTempFile("8d-export-", if (gif) ".gif" else ".mp4", cacheDir)
    exporting = true
    exportStartedAt = SystemClock.elapsedRealtime()
    updateExportProgress("准备音频，等待解码完成")
    showExportProgress()
    player.export(wav.outputStream(), onProgress = { value -> main.post {
        updateExportProgress(if (value == 10) "正在预处理音频" else "正在渲染空间音频，请稍候")
    } }, onDone = { ok, message ->
        if (!ok) {
            wav.delete(); video.delete(); if (uri != null) discardPending(uri)
            exportProgressDialog?.dismiss()
            exporting = false; playbackStatus = message; refreshPlayback(); toast(message)
        } else Thread {
            exportStartedAt = SystemClock.elapsedRealtime()
            var error: String? = null
            try {
                var previousProgress = -1
                val report: (Int) -> Unit = { value ->
                    if (value / 5 != previousProgress) {
                        previousProgress = value / 5
                        main.post {
                            val elapsed = (SystemClock.elapsedRealtime() - exportStartedAt) / 1000
                            val remaining = if (value >= 5) (elapsed * (100 - value) / value).coerceAtLeast(0) else null
                            val eta = remaining?.let { " · 预计剩余 ${it} 秒" } ?: ""
                            updateExportProgress("编码${if (gif) "GIF" else "视频"} $value% · 已用时 ${elapsed} 秒$eta")
                        }
                    }
                }
                if (gif) GifExport.render(applicationContext, wav, video, state, lyrics, landscape, startSeconds,
                    if (durationSeconds == 0.0) 5.0 else durationSeconds, speed, reversed, smoothGifLoop, report)
                else VideoExport.render(applicationContext, wav, video,
                    if (preview) state.copy(creative = state.creative.copy(outroText = "")) else state,
                    lyrics, landscape, resolution, speed, reversed, startSeconds, durationSeconds, report)
                if (!preview) {
                    main.post { updateExportProgress("正在保存文件") }
                    val stream = contentResolver.openOutputStream(uri ?: error("缺少导出位置")) ?: error("无法创建导出文件")
                    stream.use { output -> video.inputStream().use { it.copyTo(output) } }
                }
            } catch (exception: Exception) {
                android.util.Log.e("8DExport", "video export failed", exception)
                error = exception.message ?: "未知错误"
            } finally { wav.delete(); if (!preview || error != null) video.delete() }
            main.post {
                var saved = false
                try {
                    if (error == null) { onComplete(); saved = true } else if (uri != null) discardPending(uri)
                    playbackStatus = if (error == null) if (preview) "预览已生成" else "${if (gif) "GIF" else "MP4"} 导出完成" else "导出失败: $error"
                } catch (exception: Exception) {
                    if (uri != null) discardPending(uri); video.delete(); playbackStatus = "MP4 保存失败: ${exception.message}"
                }
                exporting = false; exportProgressDialog?.dismiss(); refreshPlayback(); toast(playbackStatus)
                if (saved) {
                    if (preview) showVideoPreview(video)
                    else if (uri != null) showExportedVideoActions(uri, if (gif) "image/gif" else "video/mp4")
                }
            }
        }.start()
    })
}

internal fun MainActivity.showVideoPreview(file: File) {
    lateinit var player: android.widget.VideoView
    val dialog = ThemeUi.showSheet(this, palette, "导出预览") {
        player = android.widget.VideoView(this@showVideoPreview)
        addView(player, android.widget.LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showVideoPreview, 300f)))
    }
    dialog.setOnDismissListener { player.stopPlayback(); file.delete() }
    player.setVideoPath(file.absolutePath)
    player.setOnPreparedListener { it.isLooping = true; player.start() }
    player.setOnErrorListener { _, _, _ -> toast("无法播放预览"); dialog.dismiss(); true }
    dialog.show()
}

internal fun MainActivity.exportPublicVideo(name: String, landscape: Boolean, resolution: Int, startSeconds: Double, durationSeconds: Double, gif: Boolean = false, smoothGifLoop: Boolean = false) {
    if (android.os.Build.VERSION.SDK_INT < 29) {
        exportVideoLandscape = landscape; exportVideoResolution = resolution
        exportVideoStart = startSeconds; exportVideoDuration = durationSeconds
        exportVideoSmoothGifLoop = smoothGifLoop
        if (gif) saveGif.launch("$name-8D.gif") else saveMp4.launch("$name-8D.mp4")
        return
    }
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "$name-8D.${if (gif) "gif" else "mp4"}")
        put(MediaStore.Video.Media.MIME_TYPE, if (gif) "image/gif" else "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, if (gif) "Pictures/8D" else "Movies/8D")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val collection = if (gif) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val uri = contentResolver.insert(collection, values) ?: return
        exportMp4(uri, landscape, resolution, startSeconds, durationSeconds, gif, smoothGifLoop = smoothGifLoop) {
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
    }
}

internal fun MainActivity.showExportedVideoActions(uri: Uri, mime: String) {
    ThemeUi.showSheet(this, palette, "导出完成") {
        addView(ThemeUi.button(this@showExportedVideoActions, palette, "预览", ThemeUi.ROLE_SURFACE) {
            runCatching {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { toast("没有可用的视频播放器") }
        })
        addView(ThemeUi.button(this@showExportedVideoActions, palette, "分享", ThemeUi.ROLE_ACCENT_FILL) {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime)
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri("export", uri)
            runCatching { startActivity(android.content.Intent.createChooser(intent, "分享作品")) }
                .onFailure { toast("没有可用的分享应用") }
        })
    }.show()
}

internal fun MainActivity.exportWav(uri: Uri, onComplete: () -> Unit = {}) {
        if (exporting) { toast("已有导出任务，请等待完成"); return }
        val player = service?.player ?: return
        exporting = true
        val stream = contentResolver.openOutputStream(uri) ?: run {
            exporting = false
            toast("无法创建导出文件")
            return
        }
        playbackStatus = "正在导出 WAV…"
        var published = false
        player.export(stream, mp3 = false,
            onProgress = { percent -> main.post { playbackStatus = "导出 $percent%"; refreshPlayback() } },
            onDone = { ok, message ->
                exporting = false
                if (ok) { onComplete(); published = true }
                if (!published) discardPending(uri)
                playbackStatus = message; refreshPlayback(); toast(message)
            })
    }

internal fun MainActivity.exportMp3(uri: Uri, onComplete: () -> Unit = {}) {
        if (exporting) { toast("已有导出任务，请等待完成"); return }
        val player = service?.player ?: return
        exporting = true
        val mp3 = java.io.File(cacheDir, "8d-export.mp3")
        playbackStatus = "正在导出 MP3…"
        val stream = contentResolver.openOutputStream(uri) ?: run {
            exporting = false
            toast("无法创建导出文件")
            return
        }
        player.export(stream, mp3 = true, mp3Path = mp3.absolutePath,
            onProgress = { percent -> main.post { playbackStatus = "导出 $percent%"; refreshPlayback() } },
            onDone = { ok, message ->
                mp3.delete()
                if (ok) {
                    try {
                        playbackStatus = message
                        onComplete()
                    } catch (e: Exception) {
                        playbackStatus = "MP3 保存失败: ${e.message}"
                        discardPending(uri)
                    }
                } else { playbackStatus = message; discardPending(uri) }
                exporting = false
                refreshPlayback()
                toast(playbackStatus)
            })
    }

internal fun MainActivity.exportPublic(name: String, mp3: Boolean) {
        if (exporting) { toast("已有导出任务，请等待完成"); return }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name-8D.${if (mp3) "mp3" else "wav"}")
            put(MediaStore.Audio.Media.MIME_TYPE, if (mp3) "audio/mpeg" else "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/8D")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        // 应用向公共 Music/8D 写自己创建的条目，不需要全盘存储权限。
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return
        val done = {
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            Unit
        }
        if (mp3) exportMp3(uri, done) else exportWav(uri, done)
    }

internal fun MainActivity.discardPending(uri: Uri) {
        // 导出失败时撤销未发布条目，避免 Music 里留下 0 字节文件。
        if ("media" == uri.authority) contentResolver.delete(uri, "${MediaStore.Audio.Media.IS_PENDING}=1", null)
    }

internal fun MainActivity.togglePlaybackRecording(statusView: TextView) {
        if (recordingKind == "playback") {
            service?.player?.stopRecording(); service?.radio?.stopRecording()
            finishRecording("播放录音", statusView)
            return
        }
        if (recordingKind.isNotEmpty()) { toast("请先停止麦克风录音"); return }
        val playing = if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true
        if (!playing) { statusView.text = "先播放本地音乐或电台，再开始录制最终输出"; return }
        val path = java.io.File(cacheDir, "rec_${System.currentTimeMillis()}.wav").absolutePath
        val started = if (isLive) service?.radio?.startRecording(path) == true else service?.player?.startRecording(path) == true
        if (!started) { statusView.text = "录音启动失败，请稍后重试"; return }
        beginRecording("playback", path, statusView)
    }

internal fun MainActivity.startMicRecording() {
        if (recordingKind.isNotEmpty()) { toast("请先停止当前录音"); return }
        service?.player?.pause(); service?.radio?.pause()
        val path = java.io.File(cacheDir, "mic_${System.currentTimeMillis()}.wav").absolutePath
        Thread {
            val recorder = MicWavRecorder()
            recorder.setListener { data, length ->
                if (offlineAsrEnabled) offlineAsr.acceptPcm(data, length, 44100, 1)
            }
            if (!recorder.start(path, 44100, 1)) {
                main.post { toast("麦克风初始化失败，可能被其它应用占用") }
                return@Thread
            }
            micRecorder = recorder
            if (offlineAsrEnabled) offlineAsr.start()
            main.post { beginRecording("mic", path, sourceStatusView ?: android.widget.TextView(this@startMicRecording)) }
        }.start()
    }

internal fun MainActivity.beginRecording(kind: String, path: String, statusView: TextView) {
        recordingKind = kind; recordingPath = path; recordingStartedAt = SystemClock.elapsedRealtime()
        statusView.text = "${if (kind == "mic") "麦克风" else "播放"}录音已开始"
        dismissSource()
        refreshPlayback()
    playlistDialog?.refreshContent()
    }

internal fun MainActivity.finishMicRecording(statusView: TextView) {
        val recorder = micRecorder ?: return
        micRecorder = null
        Thread {
            recorder.stop()
            recorder.setListener(null)
            main.post { finishRecording("麦克风录音", statusView) }
        }.start()
    }
