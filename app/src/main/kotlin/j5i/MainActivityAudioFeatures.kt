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
    val dialog = ThemeUi.showSheet(this, palette, t("export.progress.title"), subtitle = t("export.progress.hint")) {
        addView(ThemeUi.text(this@showExportProgress, playbackStatus, 15f, palette).also { exportProgressText = it })
    }
    exportProgressDialog = dialog
    dialog.setOnDismissListener { exportProgressText = null; exportProgressDialog = null }
    dialog.show()
}

internal fun MainActivity.exportMp4(uri: Uri?, landscape: Boolean, resolution: Int, startSeconds: Double, durationSeconds: Double, gif: Boolean = false, preview: Boolean = false, smoothGifLoop: Boolean = false, previewTailSeconds: Int = 0, onComplete: () -> Unit = {}) {
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
    var phase = t("export.preparing")
    fun displayProgress() {
        val elapsed = (SystemClock.elapsedRealtime() - exportStartedAt) / 1000
        updateExportProgress(t("export.elapsed").format(phase, elapsed))
    }
    val heartbeat = object : Runnable {
        override fun run() {
            if (!exporting) return
            displayProgress()
            main.postDelayed(this, 1000)
        }
    }
    displayProgress()
    main.postDelayed(heartbeat, 1000)
    showExportProgress()
    player.export(wav.outputStream(), onProgress = { value -> main.post {
        if (exporting) {
            phase = t(if (value == 10) "export.preprocess" else "export.render")
            displayProgress()
        }
    } }, onDone = { ok, message ->
        if (!ok) {
            wav.delete(); video.delete(); if (uri != null) discardPending(uri)
            exportProgressDialog?.dismiss()
            exporting = false; main.removeCallbacks(heartbeat); playbackStatus = message; refreshPlayback(); toast(message)
        } else Thread {
            var error: String? = null
            try {
                var previousProgress = -1
                val report: (Int) -> Unit = { value ->
                    if (value != previousProgress) {
                        previousProgress = value
                        main.post {
                            if (exporting) {
                                phase = t("export.encoding").format(if (gif) "GIF" else t("video.title"), value)
                                displayProgress()
                            }
                        }
                    }
                }
                if (gif) GifExport.render(applicationContext, wav, video, state, lyrics, landscape, startSeconds,
                    if (durationSeconds == 0.0) 5.0 else durationSeconds, speed, reversed, smoothGifLoop, report)
                else VideoExport.render(applicationContext, wav, video,
                    if (preview && previewTailSeconds == 0) state.copy(creative = state.creative.copy(outroText = "")) else state,
                    lyrics, landscape, resolution, speed, reversed, startSeconds, durationSeconds, previewTailSeconds, report)
                if (!preview) {
                    main.post { if (exporting) { phase = t("export.saving"); displayProgress() } }
                    val stream = contentResolver.openOutputStream(uri ?: error(t("export.no_destination"))) ?: error(t("mv.export.create_failed"))
                    stream.use { output -> video.inputStream().use { it.copyTo(output) } }
                }
            } catch (exception: Exception) {
                android.util.Log.e("8DExport", "video export failed", exception)
                error = errorDetail(exception).ifBlank { t("export.unknown_error") }
            } finally { wav.delete(); if (!preview || error != null) video.delete() }
            main.post {
                var saved = false
                try {
                    if (error == null) { onComplete(); saved = true } else if (uri != null) discardPending(uri)
                    playbackStatus = if (error == null) if (preview) t("export.preview.ready") else t("export.complete.format").format(if (gif) "GIF" else "MP4") else "${t("export.failed")}: $error"
                } catch (exception: Exception) {
                    if (uri != null) discardPending(uri); video.delete(); playbackStatus = "${t("export.save_failed").format("MP4")}: ${errorDetail(exception)}"
                }
                exporting = false; main.removeCallbacks(heartbeat); exportProgressDialog?.dismiss(); refreshPlayback(); toast(playbackStatus)
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
    val dialog = ThemeUi.showSheet(this, palette, t("export.preview.title")) {
        player = android.widget.VideoView(this@showVideoPreview)
        addView(android.widget.FrameLayout(this@showVideoPreview).apply {
            addView(player, android.widget.FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
        }, android.widget.LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showVideoPreview, 300f)))
    }
    dialog.setOnDismissListener { player.stopPlayback(); file.delete() }
    player.setVideoPath(file.absolutePath)
    player.setOnPreparedListener { media ->
        val frame = player.parent as android.widget.FrameLayout
        frame.post {
            if (media.videoWidth > 0 && media.videoHeight > 0 && frame.width > 0 && frame.height > 0) {
                val scale = minOf(frame.width.toFloat() / media.videoWidth, frame.height.toFloat() / media.videoHeight)
                player.layoutParams = android.widget.FrameLayout.LayoutParams(
                    (media.videoWidth * scale).toInt(), (media.videoHeight * scale).toInt(), android.view.Gravity.CENTER)
            }
        }
        media.isLooping = true; player.start()
    }
    player.setOnErrorListener { _, _, _ -> toast(t("export.preview.failed")); dialog.dismiss(); true }
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
    ThemeUi.showSheet(this, palette, t("export.complete")) {
        addView(ThemeUi.button(this@showExportedVideoActions, palette, t("export.preview"), ThemeUi.ROLE_SURFACE) {
            runCatching {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { toast(t("export.no_player")) }
        })
        addView(ThemeUi.button(this@showExportedVideoActions, palette, t("export.share"), ThemeUi.ROLE_ACCENT_FILL) {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime)
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri("export", uri)
            runCatching { startActivity(android.content.Intent.createChooser(intent, t("export.share.title"))) }
                .onFailure { toast(t("export.no_share")) }
        })
    }.show()
}

internal fun MainActivity.exportWav(uri: Uri, onComplete: () -> Unit = {}) {
        if (exporting) { toast(t("mv.export.busy")); return }
        val player = service?.player ?: return
        exporting = true
        val stream = contentResolver.openOutputStream(uri) ?: run {
            exporting = false
            toast(t("mv.export.create_failed"))
            return
        }
        playbackStatus = t("export.format.progress").format("WAV")
        var published = false
        player.export(stream, mp3 = false,
            onProgress = { percent -> main.post { playbackStatus = t("export.percent").format(percent); refreshPlayback() } },
            onDone = { ok, message ->
                exporting = false
                if (ok) { onComplete(); published = true }
                if (!published) discardPending(uri)
                playbackStatus = message; refreshPlayback(); toast(message)
            })
    }

internal fun MainActivity.exportMp3(uri: Uri, onComplete: () -> Unit = {}) {
        if (exporting) { toast(t("mv.export.busy")); return }
        val player = service?.player ?: return
        exporting = true
        val mp3 = java.io.File(cacheDir, "8d-export.mp3")
        playbackStatus = t("export.format.progress").format("MP3")
        val stream = contentResolver.openOutputStream(uri) ?: run {
            exporting = false
            toast(t("mv.export.create_failed"))
            return
        }
        player.export(stream, mp3 = true, mp3Path = mp3.absolutePath,
            onProgress = { percent -> main.post { playbackStatus = t("export.percent").format(percent); refreshPlayback() } },
            onDone = { ok, message ->
                mp3.delete()
                if (ok) {
                    try {
                        playbackStatus = message
                        onComplete()
                    } catch (e: Exception) {
                        playbackStatus = "${t("export.save_failed").format("MP3")}: ${errorDetail(e)}"
                        discardPending(uri)
                    }
                } else { playbackStatus = message; discardPending(uri) }
                exporting = false
                refreshPlayback()
                toast(playbackStatus)
            })
    }

internal fun MainActivity.exportPublic(name: String, mp3: Boolean) {
        if (exporting) { toast(t("mv.export.busy")); return }
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
            finishRecording(t("record.playback"), statusView)
            return
        }
        if (recordingKind.isNotEmpty()) { toast(t("record.stop_mic")); return }
        val playing = if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true
        if (!playing) { statusView.text = t("record.play_first"); return }
        val path = java.io.File(cacheDir, "rec_${System.currentTimeMillis()}.wav").absolutePath
        val started = if (isLive) service?.radio?.startRecording(path) == true else service?.player?.startRecording(path) == true
        if (!started) { statusView.text = t("record.start_failed"); return }
        beginRecording("playback", path, statusView)
    }

internal fun MainActivity.startMicRecording() {
        if (recordingKind.isNotEmpty()) { toast(t("record.stop_current")); return }
        service?.player?.pause(); service?.radio?.pause()
        val path = java.io.File(cacheDir, "mic_${System.currentTimeMillis()}.wav").absolutePath
        Thread {
            val recorder = MicWavRecorder()
            recorder.setListener { data, length ->
                if (offlineAsrEnabled) offlineAsr.acceptPcm(data, length, 44100, 1)
            }
            if (!recorder.start(path, 44100, 1)) {
                main.post { toast(t("record.mic_failed")) }
                return@Thread
            }
            micRecorder = recorder
            if (offlineAsrEnabled) offlineAsr.start()
            main.post { beginRecording("mic", path, sourceStatusView ?: android.widget.TextView(this@startMicRecording)) }
        }.start()
    }

internal fun MainActivity.beginRecording(kind: String, path: String, statusView: TextView) {
        recordingKind = kind; recordingPath = path; recordingStartedAt = SystemClock.elapsedRealtime()
        statusView.text = t("record.started").format(t(if (kind == "mic") "record.mic" else "record.playback"))
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
            main.post { finishRecording(t("record.mic"), statusView) }
        }.start()
    }
