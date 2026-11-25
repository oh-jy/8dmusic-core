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
import j5i.spatial8d.core.SPACE_PRESETS
import j5i.spatial8d.core.SpacePreset
import j5i.spatial8d.core.TransformParams
import j5i.spatial8d.core.matchPresetIn
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.isOnlineAudio
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
        val name = runCatching { contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } }.getOrNull() ?: uri.lastPathSegment ?: t("source.music")
        val item = musicStore.cached().firstOrNull { it.uri == uri.toString() }
            ?: MusicItem(System.nanoTime(), name, uri.toString())
        musicStore.remember(item)
        songs = (musicStore.cached() + listOf(item)).distinctBy { it.uri }.toMutableList()
        queue = musicStore.view(songs)
        currentIndex = songs.indexOfFirst { it.uri == item.uri }
        load(item)
    }

/** 暂存在线结果/合集队列：独立于本地库持久化，切列表或页面重建后上下首仍按这份列表走。 */
internal fun MainActivity.stageOnlineQueue(items: List<MusicItem>, providerId: String, startId: Long? = null) {
    if (items.isEmpty()) return
    onlineQueue = items
    onlineQueueProvider = providerId
    currentQueue = items
    musicStore.saveOnlineQueue(items, startId, providerId)
    service?.syncQueue(items, currentUri)
}

/** 播放与暂存在线队列无关的内容（本地文件、电台等）时，释放合集上下文。 */
private fun MainActivity.releaseOnlineQueueIfOutside(item: MusicItem) {
    if (onlineQueue.isNotEmpty() && onlineQueue.none { it.id == item.id }) {
        onlineQueue = emptyList()
        onlineQueueProvider = ""
        musicStore.clearOnlineQueue()
    }
}

internal fun MainActivity.load(item: MusicItem) {
        load(item, true)
    }

internal fun MainActivity.prepareOnlineSelection(item: MusicItem, status: String): Int {
    val generation = ++loadGeneration
    ++radioGeneration
    radioProbes.forEach { it.stop() }
    radioProbes = emptyList()
    service?.radio?.stop()
    service?.stopLocal()
    isLive = false
    currentStationId = ""
    currentItem = item
    currentUri = item.uri
    ++lyricsGeneration
    currentLyrics = null
    if (isViewReady(this@prepareOnlineSelection::lyricsView)) lyricsView.clear()
    pushLyricsToVisualizer()
    playbackStatus = status
    refreshPlayback()
    return generation
}

private fun mvQualityRank(label: String): Int = when {
    label.endsWith("P", ignoreCase = true) -> label.dropLast(1).toIntOrNull() ?: 0
    label.contains("蓝光") -> 40
    label.contains("超清") -> 30
    label.contains("高清") -> 20
    label.contains("标清") || label.contains("流畅") -> 10
    else -> 0
}

private fun highestMvOption(options: List<j5i.spatial8d.provider.ProviderTrackRef.MvOption>): j5i.spatial8d.provider.ProviderTrackRef.MvOption =
    options.maxWithOrNull(compareBy<j5i.spatial8d.provider.ProviderTrackRef.MvOption>({ it.size > 0L }, { it.size }, { mvQualityRank(it.label) }))
        ?: options.last()

internal fun MainActivity.playProviderMv(item: MusicItem, audioOnly: Boolean, autoSelectHighestQuality: Boolean = false,
    fallbackToAudio: (() -> Unit)? = null) {
    if (recordingKind.isNotEmpty()) { toast(t("playback.stop_recording_mv")); return }
    val returnToOnline = !audioOnly && onlineDialog?.isShowing == true
    val generation = prepareOnlineSelection(item, t("playback.mv.resolving"))
    dismissSource()
    Thread {
        val result = runCatching { j5i.spatial8d.provider.ProviderTrackRef.mvOptions(this, item) }
        main.post {
            if (generation != loadGeneration || isDestroyed || isFinishing) return@post
            result.onSuccess { options ->
                if (autoSelectHighestQuality) {
                    val option = highestMvOption(options)
                    playbackStatus = t("playback.mv.auto").format(j5i.spatial8d.ui.AppLanguage.mvQuality(this, option.label))
                    refreshPlayback()
                    loadProviderMv(item, option, audioOnly = true, generation, fallbackToAudio = fallbackToAudio)
                    return@onSuccess
                }
                playbackStatus = t("playback.mv.choose")
                refreshPlayback()
                lateinit var sheet: android.app.Dialog
                sheet = j5i.spatial8d.ui.ThemeUi.showSheet(this, palette, t("playback.mv.choose"),
                    "${t(if (audioOnly) "playback.mv.audio_only" else "source.mv.video")} · ${t("playback.mv.shared_cache")}") {
                    options.forEach { option ->
                        val cached = j5i.spatial8d.provider.ProviderMvCache.file(this@playProviderMv, item, option).let { it.isFile && it.length() > 1024L }
                        val size = if (option.size > 0L) String.format(java.util.Locale.ROOT, " · %.1f MB", option.size / 1048576.0) else " · ${t("playback.mv.unknown_size")}"
                        addView(j5i.spatial8d.ui.ThemeUi.button(this@playProviderMv, palette,
                            j5i.spatial8d.ui.AppLanguage.mvQuality(this@playProviderMv, option.label) + size + if (cached) " · ${t("plugin.cached")}" else "") {
                            sheet.dismiss()
                            if (generation == loadGeneration) loadProviderMv(item, option, audioOnly, generation, returnToOnline)
                        })
                    }
                }
                sheet.show()
            }.onFailure {
                if (fallbackToAudio != null) { fallbackToAudio(); return@onFailure }
                playbackStatus = "${t("video.mv.resolve_failed")}: ${errorDetail(it)}"
                toast(playbackStatus)
                refreshPlayback()
            }
        }
    }.start()
}

private fun MainActivity.loadProviderMv(item: MusicItem, option: j5i.spatial8d.provider.ProviderTrackRef.MvOption,
    audioOnly: Boolean, generation: Int, returnToOnline: Boolean = false, fallbackToAudio: (() -> Unit)? = null) {
    Thread {
        val result = runCatching {
            j5i.spatial8d.provider.ProviderMvCache.cache(this, item, option,
                { generation == loadGeneration && !isDestroyed }) { copied, total ->
                main.post {
                    if (generation == loadGeneration && !isDestroyed) {
                        val downloaded = String.format(java.util.Locale.ROOT, "%.1f", copied / 1048576.0)
                        val size = if (total > 0L) " / ${String.format(java.util.Locale.ROOT, "%.1f", total / 1048576.0)} MB" else " MB"
                        playbackStatus = t("playback.mv.caching").format(downloaded, size)
                        refreshPlayback()
                    }
                }
            }
        }
        main.post {
            if (generation != loadGeneration || isDestroyed || isFinishing) return@post
            result.onSuccess { media ->
                if (audioOnly) load(media)
                else {
                    playbackStatus = t("playback.mv.playing")
                    refreshPlayback()
                    openExternalMedia(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(media.uri), "video/mp4")
                        .putExtra("j5i.providerMv", Uri.parse(item.uri).getQueryParameter("mv"))
                        .putExtra("j5i.providerMvQuality", option.id)
                        .putExtra("j5i.returnToOnline", returnToOnline))
                }
            }.onFailure {
                if (fallbackToAudio != null) { fallbackToAudio(); return@onFailure }
                playbackStatus = "${t("video.mv.resolve_failed")}: ${errorDetail(it)}"
                toast(playbackStatus)
                refreshPlayback()
            }
        }
    }.start()
}

private fun MainActivity.resolveOnlineAudio(item: MusicItem, autoplay: Boolean, allowMvFallback: Boolean) {
    val generation = prepareOnlineSelection(item, t("playback.online.resolving"))
    Thread {
            val resolved = runCatching { j5i.spatial8d.provider.ProviderTrackRef.resolve(this, item) }
            main.post {
                if (generation != loadGeneration) return@post
                resolved.onSuccess { load(it, autoplay) }
                    .onFailure {
                        val mv = Uri.parse(item.uri).getQueryParameter("mv").orEmpty()
                        if (allowMvFallback && autoplay && mv.isNotBlank()) {
                            playProviderMv(item, audioOnly = true, autoSelectHighestQuality = true)
                        } else {
                            playbackStatus = "${t("playback.online.failed")}: ${errorDetail(it)}"
                            toast(playbackStatus)
                            refreshPlayback()
                        }
                    }
            }
    }.start()
}

internal fun MainActivity.load(item: MusicItem, autoplay: Boolean) {
    if (j5i.spatial8d.provider.ProviderTrackRef.isOnline(item)) {
        if (recordingKind.isNotEmpty()) { toast(t("plugin.stop_recording")); return }
        val uri = Uri.parse(item.uri)
        val source = uri.getQueryParameter("source").orEmpty()
        val hasMv = uri.host == "plugin" && source in setOf("platform.netease", "platform.qq", "platform.kugou") &&
            uri.getQueryParameter("mv").orEmpty().isNotBlank()
        val anonymousMv = autoplay && hasMv && j5i.spatial8d.provider.ProviderCredentials(this).get(source).isEmpty()
        if (anonymousMv) {
            playProviderMv(item, audioOnly = true, autoSelectHighestQuality = true,
                fallbackToAudio = { resolveOnlineAudio(item, autoplay, allowMvFallback = false) })
        } else resolveOnlineAudio(item, autoplay, allowMvFallback = true)
        return
    }
        if (item.album.startsWith("provider:")) {
            val id = item.album.substringAfter(':').substringAfter(':', "")
            if (providerStore.list().none { it.enabled && it.definition.id == id }) {
                toast(t("playback.source.missing"))
                return
            }
        }
        if (item.album.startsWith("plugin:https:")) {
            val id = item.album.substringAfter("plugin:https:")
            if (runCatching { pluginStore.list().none { it.enabled && it.descriptor.id == id } }.getOrDefault(true)) {
                toast(t("playback.plugin.missing"))
                return
            }
        }
        // 所有选歌、切歌和冷启动恢复共用同一条加载链路，旧代号回调全部丢弃。
        if (recordingKind.isNotEmpty()) { playbackStatus = t("plugin.stop_recording"); refreshPlayback(); return }
        releaseOnlineQueueIfOutside(item)
        if (isViewReady(this@load::visualizerView)) visualizerView.pcmClock.reset()
        val generation = ++loadGeneration
        ++radioGeneration
        radioProbes.forEach { it.stop() }
        radioProbes = emptyList()
        val online = item.isOnlineAudio()
        val previous = currentItem
        val previousOnline = previous != null && previous.isOnlineAudio()
        val targetPreset = when {
            online -> SPACE_PRESETS[0]
            isLive || previousOnline -> SPACE_PRESETS[0]
            else -> null
        }
        if (autoplay && targetPreset != null && targetPreset.name != preset.name) {
            preset = targetPreset
            if (isViewReady(this@load::homePresetButton)) refreshPresetSummary()
        }
        isLive = false
        offlineAsrEnabled = false
        asrTranscript = ""
        if (offlineAsrLazy.isInitialized()) offlineAsr.stop()
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
        val keepProviderQueue = onlineQueue.any { it.id == item.id }
        currentQueue = if (keepProviderQueue) onlineQueue else queue
        currentIndex = currentQueue.indexOfFirst { it.id == item.id }
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
        playbackStatus = t("playback.preparing")
        Thread {
            try {
                val remote = item.uri.startsWith("https://") || item.uri.startsWith("http://")
                val isOnline = remote && currentQueue.none { it.uri == item.uri }
                val stream = if (remote) {
                    val pluginId = item.album.substringAfter("plugin:https:", "")
                    val plugin = if (pluginId.isNotEmpty()) j5i.spatial8d.provider.ProviderPluginStore(this).list()
                        .first { it.enabled && it.descriptor.id == pluginId } else null
                    val safe = item.album.startsWith("provider:http:") || item.album.startsWith("provider:https:") || plugin != null
                    val allowHttp = item.album.startsWith("provider:http:") || plugin != null
                    if (item.uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true))
                        TsAdtsMediaDataSource(item.uri, safe, allowHttp) else if (plugin != null) {
                        val referer = if ("referer" in plugin.descriptor.capabilities) "https://www.bilibili.com/" else ""
                        val cookie = j5i.spatial8d.provider.ProviderCredentials(this).get(pluginId)
                        HttpMediaDataSource(item.uri, true, allowHttp, referer, cookie)
                    } else HttpMediaDataSource(item.uri, safe, allowHttp)
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
                android.util.Log.w("8DQueue", "播放加载失败：${item.name}", e)
                main.post {
                    if (generation != loadGeneration) return@post
                    if (e is j5i.spatial8d.audio.DecodeCancelledException) return@post
                    service?.player?.stop()
                    playbackStatus = errorDetail(e).ifBlank { t("playback.load_failed") }
                    if (autoplay && autoplayRequests <= 3 && currentQueue.isNotEmpty()) {
                        // 自动续播最多容错三首，坏文件不能造成无限跳曲。
                        main.postDelayed({ if (generation == loadGeneration) next(auto = true) }, 300)
                    } else if (autoplay) playbackStatus = t("playback.autoplay_stopped").format(playbackStatus)
                    refreshPlayback()
                }
            }
        }.start()
    }

internal fun MainActivity.adoptServiceTrack(item: MusicItem) {
    if (j5i.spatial8d.provider.ProviderTrackRef.isOnline(item) && item.uri != currentUri) {
        load(item)
        return
    }
        if (currentUri == item.uri) return
        if (isViewReady(this@adoptServiceTrack::visualizerView)) visualizerView.pcmClock.reset()
        currentQueue = service?.queueSnapshot?.ifEmpty { currentQueue } ?: currentQueue
        val presetChanged = service?.controlsPreset?.let { preset.name != it.name } == true
        service?.controlsPreset?.let { preset = it }
        currentItem = item
        currentUri = item.uri
        currentIndex = currentQueue.indexOfFirst { it.uri == item.uri }
        loadLyrics(item)
        playbackStatus = t("status.auto.next")
        if (presetChanged && isViewReady(this@adoptServiceTrack::homePresetButton)) refreshPresetSummary()
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
        playbackStatus = t("playback.loaded").format(head.sampleRate)
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
                val restored = SpacePreset(name = "自定义", note = "", period = state.getDouble("period"),
                    elev = state.getDouble("elev"), depth = state.getDouble("depth"), phase = state.getDouble("phase"),
                    direction = state.getDouble("direction"), width = state.getDouble("width"), room = state.getDouble("room"),
                    rt60 = state.getDouble("rt60"), wet = state.getDouble("wet"), raw = state.getBoolean("raw"),
                    trajectory = state.optInt("trajectory", 0).coerceIn(0, 4),
                    sourceDistance = state.optDouble("sourceDistance", 1.0).coerceIn(1.0, 5.0))
                val all = SPACE_PRESETS + customPresets
                val name = matchPresetIn(all, restored.period, restored.elev, restored.depth, restored.room,
                    restored.wet, restored.rt60, restored.phase, restored.direction, restored.width, restored.raw, restored.trajectory, restored.sourceDistance)
                all.firstOrNull { it.name == name } ?: restored
            } catch (_: Exception) {
                SPACE_PRESETS[0]
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
            put("trajectory", preset.trajectory); put("sourceDistance", preset.sourceDistance)
            put("reverse", reverse); put("speed", transform.speed); put("semi", transform.semi); put("tape", transform.tape)
            put("denoise", transform.denoiseLevel)
        }.toString()).apply()
        lastStateSavedAt = SystemClock.uptimeMillis()
    }

internal fun MainActivity.next(auto: Boolean = false) {
        if (isLive) { skipStation(1); return }
        if (!auto) autoplayRequests = 0
        val items = if (currentQueue.isNotEmpty()) currentQueue else queue
        if (currentItem != null && items.indexOfFirst { it.id == currentItem?.id } < 0) {
            if (auto) service?.player?.replay(forcePlay = true) else load(currentItem!!)
            return
        }
        if (items.isEmpty()) {
            playbackStatus = t("playback.queue.empty")
            refreshPlayback()
            showPlaylist()
            return
        }
        if (auto && currentMode() == PlayMode.LOOP_ONE) {
            service?.player?.replay(forcePlay = true)
            return
        }
        val current = items.indexOfFirst { it.id == currentItem?.id }
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
            playbackStatus = t("playback.queue.empty")
            refreshPlayback()
            showPlaylist()
            return
        }
        val current = items.indexOfFirst { it.id == currentItem?.id }
        val target = if (current < 0) items.lastIndex else (current - 1 + items.size) % items.size
        currentQueue = items
        load(items[target])
    }

internal fun MainActivity.showPlaylist(requestedTab: Int? = null) {
        // 弹层回调的视图队列会成为切歌快照，过滤/排序改变时同步更新。
        playlistDialog = PlaylistDialog(this, musicStore, dark, currentItem?.id ?: -1L,
            { item ->
                onlinePlayingUri = null
                if (onlineQueue.none { it.id == item.id }) currentQueue = queue
                load(item)
            }, {
                queue = it
                if (!isLive && onlineQueue.isEmpty() && (onlinePlayingUri == null || currentUri != onlinePlayingUri)) {
                    currentQueue = it
                    service?.syncQueue(it, currentUri)
                }
            }, songs, initialTab = requestedTab ?: when {
                isLive -> 1
                onlinePlayingUri != null -> 2
                else -> 0
            }) { tab, body, dialog ->
                // FM and online share one body; invalidate delayed online
                // callbacks before rebuilding the selected tab.
                providerRequest++
                fmDialog = if (tab == 1) dialog else null
                onlineDialog = if (tab == 2) dialog else null
                sourceSheetBody = if (tab == 0) null else body
                if (tab == 0) body.tag = "local"
                if (tab == 1) {
                    populateSourceSheet(body, false)
                    sourceStatusView = j5i.spatial8d.ui.ThemeUi.text(this, "", 11f, palette,
                        role = j5i.spatial8d.ui.ThemeUi.ROLE_SUB).also { body.addView(it) }
                } else if (tab == 2) {
                    populateSourceSheet(body, true)
                }
            }
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
