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
        listOf("audio", "video", "lyrics", "live", "picked", "provider_plugins", "covers", "other").forEach { type ->
            val files = managedCacheFiles(type)
            val size = Formatter.formatShortFileSize(this@showPlaybackCacheManager, files.sumOf { it.length() })
            addView(ThemeUi.button(this@showPlaybackCacheManager, palette,
                "${categoryLabel(type)} · ${files.size} · $size") {
                dialog.dismiss()
                if (type in setOf("audio", "video", "lyrics", "live")) showCacheItems(type)
                else showManagedFiles(type)
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

private fun MainActivity.categoryLabel(type: String): String = when (type) {
    "audio" -> t("cache.audio.list")
    "video" -> t("cache.video.list")
    "lyrics" -> t("cache.lyrics.list")
    "live" -> t("cache.live.list")
    else -> t("cache.category.$type")
}

private fun MainActivity.managedCacheFiles(type: String): List<File> {
    val directories = when (type) {
        "audio" -> listOf(File(filesDir, "provider-audio"))
        "video" -> listOf(File(filesDir, "video-cache"))
        "lyrics" -> listOf(File(filesDir, "lyrics"), File(cacheDir, "lyrics"))
        "live" -> listOf(File(filesDir, "live-catalog"))
        "picked" -> listOf(File(filesDir, "picked"), File(filesDir, "online-favorites"))
        "provider_plugins" -> listOf(File(filesDir, "provider-plugins"))
        "covers" -> listOf(File(filesDir, "covers"))
        "other" -> listOf(
            File(filesDir, "provider-audio/.part"), File(cacheDir, "updates"),
            File(cacheDir, "decoded-pcm"), File(cacheDir, "code_cache")
        )
        else -> emptyList()
    }
    return directories.flatMap { it.walkTopDown().filter { it.isFile }.asSequence() }.toList()
        .sortedByDescending { it.lastModified() }
}

private fun MainActivity.managedFileProtected(type: String, file: File): Boolean {
    return when (type) {
        "picked", "provider_plugins", "covers" -> true
        "other" -> file.name == "lyrics-edited" || file.path.contains("${File.separator}lyrics-edited${File.separator}") ||
            file.path.contains("${File.separator}shared_prefs${File.separator}") ||
            file.path.contains("${File.separator}databases${File.separator}")
        else -> true
    }
}

private fun MainActivity.showManagedFiles(type: String) {
    val label = categoryLabel(type)
    val files = managedCacheFiles(type)
    lateinit var sheet: Dialog
    sheet = ThemeUi.showSheet(this, palette, label, fullHeight = true) {
        if (files.isEmpty()) addView(ThemeUi.text(this@showManagedFiles, t("cache.list.empty"), 12f, palette, role = ThemeUi.ROLE_SUB))
        files.forEach { file ->
            val details = listOf(
                Formatter.formatShortFileSize(this@showManagedFiles, file.length()),
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))
            ).joinToString(" · ")
            addView(ThemeUi.row(this@showManagedFiles).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = ThemeUi.dp(this@showManagedFiles, 2f)
                }
                background = ThemeUi.alternatingRowBackground(this, palette, files.indexOf(file), false)
                addView(LinearLayout(this@showManagedFiles).apply {
                    orientation = LinearLayout.VERTICAL
                    val displayName = if (type == "picked") {
                        val id = file.name.substringAfter("music_").substringBefore('.').toLongOrNull()
                            ?: file.name.substringBefore('.').toLongOrNull()
                        (musicStore.cached() + musicStore.onlineCached()).firstOrNull { it.id == id }?.name ?: file.name
                    } else file.name
                    addView(ThemeUi.text(this@showManagedFiles, displayName, 13f, palette))
                    addView(ThemeUi.text(this@showManagedFiles, details, 11f, palette, role = ThemeUi.ROLE_SUB))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.button(this@showManagedFiles, palette, t("delete"), ThemeUi.ROLE_CONTROL) {
                    if (managedFileProtected(type, file)) {
                        toast(t("cache.protected"))
                    } else {
                        lateinit var confirm: Dialog
                        confirm = ThemeUi.showSheet(this@showManagedFiles, palette, t("delete"), t("cache.delete.confirm").format(file.name)) {
                            addView(ThemeUi.button(this@showManagedFiles, palette, t("delete"), ThemeUi.ROLE_ACCENT_FILL) {
                                confirm.dismiss()
                                val deleted = runCatching {
                                    if (managedFileProtected(type, file)) false else file.delete()
                                }.getOrDefault(false)
                                toast(t(if (deleted) "cache.delete.success" else "cache.delete.failed"))
                                if (deleted && sheet.isShowing) { sheet.dismiss(); showManagedFiles(type) }
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

private fun MainActivity.showCacheItems(type: String) {
    val isAudio = type == "audio"
    val isVideo = type == "video"
    val files = when (type) {
        "video" -> managedCacheFiles(type)
        else -> cacheFiles(type)
    }
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
                                        else if (isVideo) runCatching { ProviderMvCache.delete(applicationContext, file) }.getOrDefault(false)
                                            .takeIf { it } ?: file.delete()
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
