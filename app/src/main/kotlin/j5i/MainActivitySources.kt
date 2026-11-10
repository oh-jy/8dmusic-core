package j5i.spatial8d

import android.Manifest
import android.app.Dialog
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.HorizontalScrollView
import j5i.spatial8d.core.RadioStation
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.ui.ThemeUi
import kotlin.math.roundToInt

internal fun MainActivity.showSourceSheet(online: Boolean) {
        if (if (online) !onlineUnlocked else !fmUnlocked) return
        val current = if (online) onlineDialog else fmDialog
        if (current?.isShowing == true) return
        sourceStatusView = null
        val sheet = ThemeUi.showSheet(this, palette, if (online) t("online") else t("fm"), "8D",
            cancelLabel = t("close"), fullHeight = true, scrollable = false) {
            populateSourceSheet(this, online)
        }
        if (online) onlineDialog = sheet else fmDialog = sheet
        sourceStatusView = ThemeUi.text(this, t(if (online) "online.status" else "fm.status"), 11f, palette,
            role = ThemeUi.ROLE_SUB)
        sheet.findViewById<ViewGroup>(android.R.id.content)?.let { content ->
            (content.getChildAt(0) as? LinearLayout)?.addView(sourceStatusView)
        }
        sheet.show()
    }

internal fun MainActivity.tabRow(labels: List<String>, selected: Int, compact: Boolean = false,
        action: (Int) -> Unit): LinearLayout =
        ThemeUi.row(this).apply {
            val padding = if (compact) 2f else 5f
            setPadding(0, ThemeUi.dp(this@tabRow, padding), 0, ThemeUi.dp(this@tabRow, padding))
            background = ThemeUi.bgDrawable(this@tabRow, palette.control, 10f)
            labels.forEachIndexed { i, label ->
                addView(ThemeUi.button(this@tabRow, palette, label,
                    if (i == selected) ThemeUi.ROLE_SURFACE else ThemeUi.ROLE_TEXT) {
                        // 点击后同步整排按钮的角色与颜色，不能只切内容而保留首项高亮。
                        for (index in 0 until childCount) {
                            (getChildAt(index) as? Button)?.let { button ->
                                button.tag = if (index == i) ThemeUi.ROLE_SURFACE else ThemeUi.ROLE_TEXT
                                ThemeUi.applyButton(button, palette)
                                button.setTextColor(if (index == i) palette.accent else palette.sub)
                            }
                        }
                        action(i)
                    }.apply {
                    if (i == selected) setTextColor(palette.accent) else setTextColor(palette.sub)
                    if (compact || labels.size > 4) {
                        textSize = if (compact) 12f else 10f
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(0, 0, 0, 0)
                    }
                    layoutParams = LinearLayout.LayoutParams(0,
                        ThemeUi.dp(this@tabRow, if (compact) 28f else 36f), 1f)
                })
            }
        }


internal fun MainActivity.downloadOnlineFavorite(item: MusicItem) {
        if (!favoriteDownloads.add(item.id)) { toast("正在下载，请稍候"); return }
        toast("正在下载原始音频，完成后加入收藏")
        val app = applicationContext
        Thread {
            val result = runCatching {
                if (j5i.spatial8d.provider.ProviderTrackRef.isOnline(item) ||
                    item.album.startsWith("plugin:https:") || item.album.startsWith("provider:")) {
                    return@runCatching j5i.spatial8d.provider.ProviderAudioCache.cache(app, item, false)
                }
                require(!item.uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) { "暂不支持收藏分段直播流" }
                val dir = java.io.File(app.filesDir, "online-favorites").apply { mkdirs() }
                val target = java.io.File(dir, "${item.id}.audio")
                val part = java.io.File(dir, "${item.id}.part")
                try {
                    if (!target.isFile || target.length() == 0L) {
                        if (item.uri.startsWith("http://") || item.uri.startsWith("https://")) {
                            if (Log.isLoggable("8DFavorite", Log.INFO))
                                Log.i("8DFavorite", "下载在线收藏原始音频 method=downloadOnlineFavorite id=${item.id}")
                            val response = j5i.spatial8d.provider.ProviderNetwork.open(item.uri, item.album.startsWith("provider:http:"))
                            response.use {
                                check(it.isSuccessful) { "音频下载失败（HTTP ${it.code}）" }
                                val body = requireNotNull(it.body) { "音频响应为空" }
                                val expected = body.contentLength()
                                body.byteStream().use { input -> part.outputStream().use { output -> input.copyTo(output) } }
                                check(expected <= 0 || part.length() == expected) { "音频下载不完整" }
                            }
                        } else {
                            requireNotNull(app.contentResolver.openInputStream(android.net.Uri.parse(item.uri))) { "无法读取缓存音频" }
                                .use { input -> part.outputStream().use { input.copyTo(it) } }
                        }
                        check(part.length() > 0) { "音频文件为空" }
                        val extractor = android.media.MediaExtractor()
                        try {
                            extractor.setDataSource(part.absolutePath)
                            check((0 until extractor.trackCount).any {
                                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                            }) { "下载内容不是可识别的音频" }
                        } finally { extractor.release() }
                        check(part.renameTo(target)) { "保存音频失败" }
                    }
                    item.copy(uri = android.net.Uri.fromFile(target).toString(), size = target.length(), album = "在线收藏")
                } finally { if (part.exists()) part.delete() }
            }
            main.post {
                favoriteDownloads.remove(item.id)
                result.onSuccess { saved ->
                    musicStore.remember(saved)
                    songs = (musicStore.cached().filterNot { it.id == saved.id } + saved).toMutableList()
                    musicStore.saveLibrary(songs)
                    if (!musicStore.isFavorite(saved.id)) musicStore.toggleFavorite(saved.id)
                    queue = musicStore.view(songs)
                    toast("已下载并加入收藏")
                    if (!isDestroyed) refreshPlayback()
                }.onFailure { toast(it.message ?: "下载失败，未加入收藏") }
            }
        }.start()
    }


internal fun MainActivity.dismissSource() { fmDialog?.dismiss(); onlineDialog?.dismiss() }

internal fun MainActivity.playRadio(station: RadioStation) {
        val entry = providerStore.list().firstOrNull { it.enabled && station.id.startsWith(it.definition.id + "/") }
            ?: run { toast("来源不存在或已停用"); return }
        radioAllowHttp = entry.definition.usesHttp
        if (recordingKind.isNotEmpty()) { toast("请先停止当前录音，再切换音源"); return }
        if (isViewReady(this@playRadio::visualizerView)) visualizerView.pcmClock.reset()
        ++loadGeneration
        radioProbes.forEach { it.stop() }
        radioProbes = emptyList()
        ++lyricsGeneration
        currentLyrics = null
        if (isViewReady(this@playRadio::lyricsView)) lyricsView.clear()
        pushLyricsToVisualizer()
        if (!isLive) preset = j5i.spatial8d.core.SPACE_PRESETS[0]
        service?.stopLocal()
        currentItem = MusicItem(-1, station.name, "")
        currentUri = null
        isLive = true
        offlineAsrEnabled = false
        asrTranscript = ""
        if (offlineAsrLazy.isInitialized()) offlineAsr.stop()
        val generation = ++radioGeneration
        currentStationId = station.id
        // 优先尝试 HLS；其余线路按原顺序回退。
        radioSources = station.sources.sortedBy { if (it.quality.contains("HLS")) 0 else 1 }
        // 每个频道独立记住最近成功线路；下次先尝试它，失败仍顺序回退其它源。
        val preferredUrl = preferences.getString("radioSource:${station.id}", null)
        val preferred = radioSources.indexOfFirst { it.url == preferredUrl }.coerceAtLeast(0)
        stationIndex = importedStations().indexOfFirst { it.id == station.id }
        raceRadioSources(preferred, generation)
        dismissSource()
    }

    /** 同一频道多路并行探测；正式解码失败时仍回退其他源。 */

internal fun MainActivity.raceRadioSources(preferred: Int, generation: Int) {
        val allIndices = listOf(preferred) + radioSources.indices.filterNot { it == preferred }
        val indices = allIndices.take(3)
        playbackStatus = "并行连接 ${indices.size} 路音源…"
        var selected = false
        var failed = 0
        val probes = indices.map { index ->
            val source = radioSources[index]
            j5i.spatial8d.audio.RadioPlayer(probe = true).also { probe ->
                probe.preset = preset
                probe.semi = transform.semi.roundToInt()
                probe.onReady = { main.post {
                    if (generation != radioGeneration || selected) return@post
                    selected = true
                    radioSourceIndex = index
                    preferences.edit().putString("radioSource:$currentStationId", source.url).apply()
                    radioProbes.forEach { it.stop() }
                    radioProbes = emptyList()
                    radioSources = listOf(source) + radioSources.filterIndexed { i, _ -> i != index }
                    tryRadioSource(0, generation)
                } }
                probe.onError = { main.post {
                    if (generation == radioGeneration && !selected && ++failed == indices.size) {
                        selected = true
                        radioProbes.forEach { it.stop() }
                        radioProbes = emptyList()
                        radioSources = allIndices.drop(indices.size).map { radioSources[it] }
                        tryRadioSource(0, generation)
                    }
                } }
            }
        }
        radioProbes = probes
        requestPlaybackFocus()
        indices.forEachIndexed { i, index -> probes[i].load(radioSources[index].url, true, radioAllowHttp) }
        refreshAll()
    }

    /** 单台内部按顺序回退；失败不跳到另一台，避免用户选择了 A 却听到 B。 */

internal fun MainActivity.tryRadioSource(index: Int, generation: Int = radioGeneration) {
        if (generation != radioGeneration) return
        if (index >= radioSources.size) {
            playbackStatus = "所有备用源均不可用"
            service?.radio?.stop()
            refreshAll()
            return
        }
        radioSourceIndex = index
        val source = radioSources[index]
        val name = currentItem?.name ?: "电台"
        playbackStatus = "连接 ${index + 1}/${radioSources.size} · ${source.label}"
        service?.radio?.preset = preset
        service?.radio?.denoise = transform.denoiseLevel
        service?.radio?.semi = transform.semi.roundToInt()
        service?.radio?.onReady = { main.post {
            if (generation == radioGeneration) preferences.edit().putString("radioSource:$currentStationId", source.url).apply()
            playbackStatus = "电台播放中 · $name · ${source.label}"
            refreshPlayback()
        } }
        service?.radio?.onError = { main.post { if (generation == radioGeneration) tryRadioSource(index + 1, generation) } }
        service?.radio?.onEnd = { main.post { if (generation == radioGeneration) tryRadioSource(index + 1, generation) } }
        service?.radio?.onReconnecting = { main.post {
            if (generation == radioGeneration) { playbackStatus = it; refreshPlayback() }
        } }
        requestPlaybackFocus()
        service?.radio?.load(source.url, true, radioAllowHttp)
        refreshAll()
    }

internal fun MainActivity.skipStation(direction: Int) {
        val stations = importedStations()
        if (stations.isEmpty()) return
        val current = stations.indexOfFirst { it.id == currentStationId }
        playRadio(stations[(current + direction + stations.size) % stations.size])
    }
