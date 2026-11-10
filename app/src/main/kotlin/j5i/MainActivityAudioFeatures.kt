package j5i.spatial8d

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.TextView
import com.j5i.spatial8d.MicWavRecorder

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
