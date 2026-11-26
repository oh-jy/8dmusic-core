package j5i.spatial8d.feature.playback
import j5i.spatial8d.feature.ui.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*
import j5i.spatial8d.MainActivity
import android.app.Dialog
import android.net.Uri
import android.text.format.Formatter
import android.widget.LinearLayout
import j5i.spatial8d.provider.ProviderAudioCache
import j5i.spatial8d.provider.ProviderMvCache
import j5i.spatial8d.provider.audioCacheId
import j5i.spatial8d.ui.AppLanguage
import j5i.spatial8d.ui.ThemeUi
import java.io.File

internal fun MainActivity.showPlaybackCacheManager() {
    lateinit var dialog: Dialog
    dialog = ThemeUi.showSheet(this, palette, t("cache.title")) {
        addView(ThemeUi.text(this@showPlaybackCacheManager,
            t("cache.hint"),
            12f, palette, role = ThemeUi.ROLE_SUB))
        listOf("audio", "video", "lyrics", "live").forEach { type ->
            val files = cacheFiles(type)
            val size = Formatter.formatShortFileSize(this@showPlaybackCacheManager, files.sumOf { it.length() })
            addView(ThemeUi.button(this@showPlaybackCacheManager, palette,
                "${t("cache.$type.list")} · ${files.size} · $size") {
                dialog.dismiss(); showCacheItems(type)
            })
        }
    }
    dialog.show()
}

private fun MainActivity.audioCacheProtected(id: Long): Boolean =
    PlaybackCacheMetadata.isActive(File(filesDir, "provider-audio/$id.m4a")) || musicStore.isFavorite(id) ||
        id in favoriteDownloads || currentQueue.any { it.id == id } || service?.queueSnapshot.orEmpty().any { it.id == id } ||
        currentItem?.id == id || service?.currentTrack?.id == id

private fun MainActivity.videoCacheProtected(file: File): Boolean {
    if (PlaybackCacheMetadata.isActive(file) || externalVideoDialog?.isShowing == true) return true
    val items = listOfNotNull(currentItem, service?.currentTrack)
    if (items.any { it.uri.startsWith("online://") }) return true
    return items.any { item ->
        val uri = Uri.parse(item.uri)
        uri.scheme == "file" && runCatching { File(uri.path.orEmpty()).canonicalFile == file.canonicalFile }.getOrDefault(false)
    }
}

private fun MainActivity.cacheFiles(type: String): List<File> {
    val directories = when (type) {
        "audio" -> listOf(File(filesDir, "provider-audio"))
        "video" -> listOf(File(filesDir, "video-cache"))
        "lyrics" -> listOf(File(filesDir, "lyrics"), File(cacheDir, "lyrics"))
        "live" -> listOf(File(filesDir, "live-catalog"))
        else -> emptyList()
    }
    return directories.flatMap { it.listFiles().orEmpty().asList() }.filter {
        it.isFile && when (type) {
            "audio" -> audioCacheId(it.name) != null
            "video" -> isVideoCacheName(it.name)
            "lyrics" -> !it.name.endsWith(".tmp")
            "live" -> it.name == "iyouhun.txt" || it.name == "iptv4.json"
            else -> false
        }
    }.sortedByDescending { it.lastModified() }
}

private fun MainActivity.showCacheItems(type: String) {
    val isAudio = type == "audio"
    val isVideo = type == "video"
    val files = cacheFiles(type)
    val known = (musicStore.onlineCached() + musicStore.cached() + currentQueue + service?.queueSnapshot.orEmpty())
        .associateBy { it.id }
    fun sourceName(source: String): String = when (source) {
        "platform.qq" -> "QQ"
        "platform.kugou" -> "Kugou"
        "platform.netease" -> "NetEase"
        "platform.bilibili" -> "Bilibili"
        "" -> t("cache.unknown_source")
        else -> source
    }
    lateinit var sheet: Dialog
    sheet = ThemeUi.showSheet(this, palette, t("cache.$type.list"), fullHeight = true) {
        if (files.isEmpty()) addView(ThemeUi.text(this@showCacheItems, t("cache.list.empty"), 12f, palette, role = ThemeUi.ROLE_SUB))
        files.forEach { file ->
            val id = audioCacheId(file.name)
            val metadata = PlaybackCacheMetadata.get(this@showCacheItems, file)
            val item = id?.let { known[it] }
            val lyricTitle = if (type == "lyrics") runCatching { org.json.JSONObject(file.readText()).optString("trackName") }.getOrNull() else null
            val titleText = metadata?.title?.takeIf { it.isNotBlank() } ?: item?.name?.takeIf { it.isNotBlank() }
                ?: lyricTitle?.takeIf { it.isNotBlank() } ?: if (type == "live") file.name else t("cache.legacy").format(file.nameWithoutExtension.take(12))
            val source = metadata?.source?.takeIf { it.isNotBlank() }
                ?: item?.let { runCatching { Uri.parse(it.uri).getQueryParameter("source") }.getOrNull() }.orEmpty()
            val details = listOf(if (isAudio || isVideo) sourceName(source) else "", metadata?.quality?.let { AppLanguage.mvQuality(this@showCacheItems, it) }.orEmpty(), Formatter.formatShortFileSize(this@showCacheItems, file.length()))
                .filter { it.isNotBlank() }.joinToString(" · ")
            addView(ThemeUi.row(this@showCacheItems).apply {
                addView(LinearLayout(this@showCacheItems).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ThemeUi.text(this@showCacheItems, titleText, 13f, palette))
                    addView(ThemeUi.text(this@showCacheItems, details, 11f, palette, role = ThemeUi.ROLE_SUB))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.button(this@showCacheItems, palette, t("delete"), ThemeUi.ROLE_CONTROL) {
                    if ((isAudio && id != null && audioCacheProtected(id)) || (isVideo && videoCacheProtected(file))) {
                        toast(t("cache.protected"))
                    } else {
                        lateinit var confirm: Dialog
                        confirm = ThemeUi.showSheet(this@showCacheItems, palette, t("delete"), t("cache.delete.confirm").format(titleText)) {
                            addView(ThemeUi.button(this@showCacheItems, palette, t("delete"), ThemeUi.ROLE_ACCENT_FILL) {
                                confirm.dismiss()
                                if ((isAudio && id != null && audioCacheProtected(id)) || (isVideo && videoCacheProtected(file))) {
                                    toast(t("cache.protected"))
                                } else {
                                    val deleted = runCatching {
                                        if (isAudio && id != null) ProviderAudioCache.deletePrivate(applicationContext, id)
                                        else if (isVideo) ProviderMvCache.delete(applicationContext, file)
                                        else file.delete()
                                    }.getOrDefault(false)
                                    toast(t(if (deleted) "cache.delete.success" else "cache.delete.failed"))
                                    if (deleted && sheet.isShowing) { sheet.dismiss(); showCacheItems(type) }
                                }
                            })
                        }
                        confirm.show()
                    }
                })
            })
        }
    }
    sheet.show()
}
