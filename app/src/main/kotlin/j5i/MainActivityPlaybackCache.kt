package j5i.spatial8d

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
        addView(ThemeUi.button(this@showPlaybackCacheManager, palette, t("cache.audio.list")) {
            dialog.dismiss(); showCacheItems(true)
        })
        addView(ThemeUi.button(this@showPlaybackCacheManager, palette, t("cache.video.list")) {
            dialog.dismiss(); showCacheItems(false)
        })
        addView(ThemeUi.button(this@showPlaybackCacheManager, palette, t("cache.audio.clear")) {
            val directory = File(filesDir, "provider-audio")
            val ids = directory.listFiles().orEmpty().mapNotNull { audioCacheId(it.name) }.iterator()
            var removed = 0
            var failed = 0
            fun next() {
                if (!ids.hasNext()) {
                    toast(if (failed == 0) t("cache.audio.cleared").format(removed) else t("cache.partial").format(removed, failed))
                    return
                }
                val id = ids.next()
                if (!audioCacheProtected(id)) {
                    if (runCatching { ProviderAudioCache.deletePrivate(applicationContext, id) }.getOrDefault(false)) removed++ else failed++
                }
                main.post { next() }
            }
            next()
        })
        addView(ThemeUi.button(this@showPlaybackCacheManager, palette, t("cache.video.clear")) {
            val directory = File(filesDir, "video-cache")
            val files = directory.listFiles().orEmpty().filter { it.isFile && isVideoCacheName(it.name) }.iterator()
            var removed = 0
            var failed = 0
            fun next() {
                if (!files.hasNext()) {
                    toast(if (failed == 0) t("cache.video.cleared").format(removed) else t("cache.partial").format(removed, failed))
                    return
                }
                val file = files.next()
                if (!videoCacheProtected(file)) {
                    if (runCatching { ProviderMvCache.delete(applicationContext, file) }.getOrDefault(false)) removed++ else failed++
                }
                main.post { next() }
            }
            next()
        })
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

private fun MainActivity.showCacheItems(isAudio: Boolean) {
    val directory = File(filesDir, if (isAudio) "provider-audio" else "video-cache")
    val files = directory.listFiles().orEmpty().filter {
        it.isFile && if (isAudio) audioCacheId(it.name) != null else isVideoCacheName(it.name)
    }.sortedByDescending { it.lastModified() }
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
    sheet = ThemeUi.showSheet(this, palette, t(if (isAudio) "cache.audio.list" else "cache.video.list"), fullHeight = true) {
        if (files.isEmpty()) addView(ThemeUi.text(this@showCacheItems, t("cache.list.empty"), 12f, palette, role = ThemeUi.ROLE_SUB))
        files.forEach { file ->
            val id = audioCacheId(file.name)
            val metadata = PlaybackCacheMetadata.get(this@showCacheItems, file)
            val item = id?.let { known[it] }
            val titleText = metadata?.title?.takeIf { it.isNotBlank() } ?: item?.name?.takeIf { it.isNotBlank() }
                ?: t("cache.legacy").format(file.nameWithoutExtension.take(12))
            val source = metadata?.source?.takeIf { it.isNotBlank() }
                ?: item?.let { runCatching { Uri.parse(it.uri).getQueryParameter("source") }.getOrNull() }.orEmpty()
            val details = listOf(sourceName(source), metadata?.quality?.let { AppLanguage.mvQuality(this@showCacheItems, it) }.orEmpty(), Formatter.formatShortFileSize(this@showCacheItems, file.length()))
                .filter { it.isNotBlank() }.joinToString(" · ")
            addView(ThemeUi.row(this@showCacheItems).apply {
                addView(LinearLayout(this@showCacheItems).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ThemeUi.text(this@showCacheItems, titleText, 13f, palette))
                    addView(ThemeUi.text(this@showCacheItems, details, 11f, palette, role = ThemeUi.ROLE_SUB))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.button(this@showCacheItems, palette, t("delete"), ThemeUi.ROLE_CONTROL) {
                    if ((isAudio && id != null && audioCacheProtected(id)) || (!isAudio && videoCacheProtected(file))) {
                        toast(t("cache.protected"))
                    } else {
                        lateinit var confirm: Dialog
                        confirm = ThemeUi.showSheet(this@showCacheItems, palette, t("delete"), t("cache.delete.confirm").format(titleText)) {
                            addView(ThemeUi.button(this@showCacheItems, palette, t("delete"), ThemeUi.ROLE_ACCENT_FILL) {
                                confirm.dismiss()
                                if ((isAudio && id != null && audioCacheProtected(id)) || (!isAudio && videoCacheProtected(file))) {
                                    toast(t("cache.protected"))
                                } else {
                                    val deleted = runCatching {
                                        if (isAudio && id != null) ProviderAudioCache.deletePrivate(applicationContext, id)
                                        else ProviderMvCache.delete(applicationContext, file)
                                    }.getOrDefault(false)
                                    toast(t(if (deleted) "cache.delete.success" else "cache.delete.failed"))
                                    if (deleted && sheet.isShowing) { sheet.dismiss(); showCacheItems(isAudio) }
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
