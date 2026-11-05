package j5i.spatial8d

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.provider.OpenableColumns
import j5i.spatial8d.audio.AudioDecoder
import j5i.spatial8d.audio.Lyrics
import com.j5i.spatial8d.HttpMediaDataSource
import com.j5i.spatial8d.TsAdtsMediaDataSource
import j5i.spatial8d.core.DEFAULT_SPACE_PRESET
import j5i.spatial8d.core.SPACE_PRESETS
import j5i.spatial8d.core.SpacePreset
import j5i.spatial8d.core.TransformParams
import j5i.spatial8d.core.matchPresetIn
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.PlayMode
import j5i.spatial8d.ui.PlaylistDialog
import org.json.JSONObject

internal fun MainActivity.refreshTrackTechnicalInfo() {
        val item = currentItem
        val uri = item?.uri?.takeUnless { isLive || it.isBlank() }
        if (trackInfoUri == uri) return
        trackInfoUri = uri
        trackTechnicalInfo = ""
        // Do not open a second network stream just to populate the information line.
        if (uri == null || uri.startsWith("http", ignoreCase = true)) return
        Thread {
            val extractor = android.media.MediaExtractor()
            val details = try {
                extractor.setDataSource(this, Uri.parse(uri), null)
                val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                fun value(key: String): Int = if (format?.containsKey(key) == true) format.getInteger(key) else 0
                audioDetails(format?.getString(android.media.MediaFormat.KEY_MIME).orEmpty(),
                    value(android.media.MediaFormat.KEY_SAMPLE_RATE), value(android.media.MediaFormat.KEY_BIT_RATE))
            } catch (_: Exception) { "" } finally { extractor.release() }
            main.post {
                if (!isDestroyed && !isLive && currentItem?.uri == uri && trackInfoUri == uri) {
                    trackTechnicalInfo = details
                    refreshPlayback()
                }
            }
        }.start()
    }

internal fun MainActivity.addAndPlay(uri: Uri) {
        // SAF 授权跨重启保留，手动添加的歌曲不会因为应用重启而从队列失效。
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment ?: "音频"
        val item = MusicItem(System.nanoTime(), name, uri.toString())
        musicStore.remember(item)
        songs = (musicStore.cached() + listOf(item)).distinctBy { it.uri }.toMutableList()
        queue = musicStore.view(songs)
        currentIndex = songs.indexOfFirst { it.uri == item.uri }
        load(item)
    }

internal fun MainActivity.load(item: MusicItem) {
        load(item, true)
    }

internal fun MainActivity.load(item: MusicItem, autoplay: Boolean) {
        // 所有选歌、切歌和冷启动恢复共用同一条加载链路，旧代号回调全部丢弃。
        if (recordingKind.isNotEmpty()) { playbackStatus = "请先停止录音，再切换歌曲"; refreshPlayback(); return }
        if (isViewReady(this@load::visualizerView)) visualizerView.pcmClock.reset()
        val generation = ++loadGeneration
        ++radioGeneration
        radioProbes.forEach { it.stop() }
        radioProbes = emptyList()
        isLive = false
        stationIndex = -1
        currentStationId = ""
        radioSources = emptyList()
        radioSourceIndex = -1
        service?.radio?.stop()
        if (autoplay) autoplayRequests++ else autoplayRequests = 0
        val resumePosition = if (autoplay) 0.0 else restorePosition
        service?.player?.stop()
        currentItem = item
        loadLyrics(item)
        queue = musicStore.view(songs)
        currentIndex = queue.indexOfFirst { it.id == item.id && it.uri == item.uri }
        currentQueue = queue
        currentUri = item.uri
        playbackStatus = t("status.preparing")
        service?.let {
            it.controlsPreset = preset
            it.controlsTransform = transform
            it.controlsReverse = reverse
            it.syncQueue(currentQueue, currentUri)
            it.onTrackChanged = { next -> main.post { adoptServiceTrack(next) } }
            it.load(item, currentQueue, autoplay)
            return
        }
        songTitle.text = Lyrics.displayTitle(item.name)
        playbackStatus = "正在准备播放…"
        Thread {
            try {
                val remote = item.uri.startsWith("https://") || item.uri.startsWith("http://")
                val isOnline = remote && currentQueue.none { it.uri == item.uri }
                val stream = if (remote) {
                    if (item.uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true))
                        TsAdtsMediaDataSource(item.uri) else HttpMediaDataSource(item.uri)
                } else null
                try {
                    if (stream != null) AudioDecoder(this).decode(stream, { percent ->
                        main.post { if (generation == loadGeneration && service?.player?.isPlaying != true) {
                            playbackStatus = t("status.preparing"); refreshPlayback()
                        } }
                    }, { generation == loadGeneration }, onHead = { head, _ ->
                        main.post { prepareLoaded(item, head, autoplay, resumePosition, generation) }
                    }) else AudioDecoder(this).decode(musicStore.readableUri(item), { percent ->
                    main.post { if (generation == loadGeneration && service?.player?.isPlaying != true) {
                        playbackStatus = t("status.preparing"); refreshPlayback()
                    } }
                }, { generation == loadGeneration }, onHead = { head, _ ->
                    main.post { prepareLoaded(item, head, autoplay, resumePosition, generation) }
                })
                } finally { stream?.close() }
                main.post {
                    if (generation == loadGeneration) {
                        if (service?.player?.isPlaying != true) playbackStatus = t("status.ready")
                        refreshPlayback()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("8DQueue", "local load failed: ${item.name} uri=${item.uri}", e)
                main.post {
                    if (generation != loadGeneration) return@post
                    if (e.message?.contains("已取消旧音频解码") == true) return@post
                    service?.player?.stop()
                    playbackStatus = e.message ?: "加载失败"
                    if (autoplay && autoplayRequests <= 3 && currentQueue.isNotEmpty()) {
                        // 自动续播最多容错三首，坏文件不能造成无限跳曲。
                        main.postDelayed({ if (generation == loadGeneration) next(auto = true) }, 300)
                    } else if (autoplay) playbackStatus = "连续加载失败，已停止自动续播：$playbackStatus"
                    refreshPlayback()
                }
            }
        }.start()
    }

internal fun MainActivity.adoptServiceTrack(item: MusicItem) {
        if (currentUri == item.uri) return
        if (isViewReady(this@adoptServiceTrack::visualizerView)) visualizerView.pcmClock.reset()
        currentQueue = service?.queueSnapshot?.ifEmpty { currentQueue } ?: currentQueue
        currentItem = item
        currentUri = item.uri
        currentIndex = currentQueue.indexOfFirst { it.uri == item.uri }
        loadLyrics(item)
        playbackStatus = t("status.auto.next")
        refreshPlayback()
    }

    /** 本地文件与在线音频共用首块起播，后续 PCM 继续写入同一播放缓冲。 */

internal fun MainActivity.prepareLoaded(item: MusicItem, head: j5i.spatial8d.core.PcmBuffer,
        autoplay: Boolean, resumePosition: Double, generation: Int) {
        if (generation != loadGeneration) return
        service?.player?.prepare(head, item.duration / 1000.0)
        service?.player?.preset = preset
        service?.player?.transform = transform
        service?.player?.reverse = reverse
        // prepare 已把起点设为零；恢复位置时再定位一次，避免连续重建两代渲染缓存。
        if (resumePosition > 0.0) service?.player?.seek(resumePosition)
        playbackStatus = "已加载 · ${head.sampleRate} Hz"
        if (service?.player?.isPlaying == true) playbackStatus = t("status.playing")
        seekGuardUntil = SystemClock.elapsedRealtime() + 1500
        if (!item.uri.startsWith("http")) preferences.edit().putString("lastUri", item.uri).apply()
        if (autoplayRequests > 0) {
            requestPlaybackFocus()
            service?.player?.play()
        }
        refreshPlayback()
        savePlaybackState()
    }

internal fun MainActivity.adoptOrRestorePlayback() {
        if (currentItem != null) return
        val active = service?.currentTrack
        if (active != null && service?.player?.isPrepared == true) {
            currentQueue = service?.queueSnapshot ?: queue
            adoptServiceTrack(active)
            refreshPlayback()
            return
        }
        val uri = restoreUri ?: preferences.getString("lastUri", null) ?: return
        val item = songs.firstOrNull { it.uri == uri }
        if (item == null) {
            currentUri = uri
            return
        }
        currentUri = uri
        currentItem = item
        currentQueue = queue
        currentIndex = queue.indexOfFirst { it.id == item.id && it.uri == item.uri }
        songTitle.text = Lyrics.displayTitle(item.name)
        if (service?.player?.isPrepared == true) {
            // Activity 重建时播放器仍在运行，直接接管真实播放头，不能再次 stop/load 清零进度。
            restorePosition = 0.0
            refreshPlayback()
        } else {
            // 进程被回收后服务为空，再从持久化位置恢复为暂停状态。
            load(item, false)
        }
    }

internal fun MainActivity.restorePlaybackState() {
        val text = preferences.getString("playbackState", null) ?: return
        try {
            val state = JSONObject(text)
            preset = try {
                val restored = SpacePreset("标准绕头 · 默认", "", state.getDouble("period"),
                    state.getDouble("elev"), state.getDouble("depth"), state.getDouble("phase"),
                    state.getDouble("direction"), state.getDouble("width"), state.getDouble("room"),
                    state.getDouble("rt60"), state.getDouble("wet"), state.getBoolean("raw"))
                matchingPresetOrDefault(restored, SPACE_PRESETS + customPresets)
            } catch (_: Exception) {
                DEFAULT_SPACE_PRESET
            }
            reverse = state.getBoolean("reverse")
            transform = TransformParams(state.getDouble("speed"), state.getDouble("semi"),
                state.getBoolean("tape"), denoise = state.optInt("denoise", 65))
            restorePosition = state.getDouble("position")
            restoreUri = state.getString("uri")
        } catch (_: Exception) {}
    }

internal fun MainActivity.savePlaybackState() {
        val player = service?.player ?: return
        val uri = currentUri ?: return
        // 保存歌曲、位置和完整听音参数；冷启动只恢复并暂停，不自动播放。
        preferences.edit().putString("playbackState", JSONObject().apply {
            put("uri", uri); put("position", player.position)
            put("period", preset.period); put("elev", preset.elev); put("depth", preset.depth)
            put("phase", preset.phase); put("direction", preset.direction); put("width", preset.width)
            put("room", preset.room); put("rt60", preset.rt60); put("wet", preset.wet); put("raw", preset.raw)
            put("reverse", reverse); put("speed", transform.speed); put("semi", transform.semi); put("tape", transform.tape)
            put("denoise", transform.denoiseLevel)
        }.toString()).apply()
        lastStateSavedAt = SystemClock.uptimeMillis()
    }

internal fun MainActivity.next(auto: Boolean = false) {
        if (isLive) { skipStation(1); return }
        if (!auto) autoplayRequests = 0
        val items = if (currentQueue.isNotEmpty()) currentQueue else queue
        if (currentItem != null && items.indexOfFirst { it.uri == currentUri } < 0) {
            if (auto) service?.player?.replay(forcePlay = true) else load(currentItem!!)
            return
        }
        if (items.isEmpty()) {
            playbackStatus = "播放列表为空，请先添加或扫描音频"
            refreshPlayback()
            showPlaylist()
            return
        }
        if (auto && currentMode() == PlayMode.LOOP_ONE) {
            service?.player?.replay(forcePlay = true)
            return
        }
        val current = items.indexOfFirst { it.uri == currentUri }
        val nextIndex = when (currentMode()) {
            PlayMode.SHUFFLE -> if (items.size > 1) (items.indices.filter { it != current }).random() else 0
            else -> (current + 1 + items.size) % items.size
        }
        currentQueue = items
        Log.i("8DQueue", "next auto=$auto index=$nextIndex title=${items[nextIndex].name}")
        load(items[nextIndex])
    }

internal fun MainActivity.previous() {
        if (isLive) { skipStation(-1); return }
        val items = if (currentQueue.isNotEmpty()) currentQueue else queue
        if (items.isEmpty()) {
            playbackStatus = "播放列表为空，请先添加或扫描音频"
            refreshPlayback()
            showPlaylist()
            return
        }
        val current = items.indexOfFirst { it.uri == currentUri }
        val target = if (current < 0) items.lastIndex else (current - 1 + items.size) % items.size
        currentQueue = items
        load(items[target])
    }

internal fun MainActivity.showPlaylist() {
        // 弹层回调的视图队列会成为切歌快照，过滤/排序改变时同步更新。
        playlistDialog = PlaylistDialog(this, musicStore, dark, songs.firstOrNull { it.uri == currentUri }?.id ?: -1L,
            { item -> currentQueue = queue; load(item) }, {
                queue = it
                currentQueue = it
                service?.syncQueue(it, currentUri)
            }, songs)
        playlistDialog?.show()
    }

internal fun MainActivity.isSourceMissing(uri: Uri): Boolean = when {
        uri.scheme == "file" || uri.scheme == null ->
            !java.io.File(uri.path.orEmpty()).exists()
        uri.scheme == "content" -> runCatching {
            contentResolver.query(uri, arrayOf(android.provider.BaseColumns._ID), null, null, null)
                ?.use { !it.moveToFirst() } ?: false
        }.getOrDefault(false)
        else -> false
    }

internal fun MainActivity.requestPlaybackFocus() {
        service?.requestPlaybackFocus()
    }
