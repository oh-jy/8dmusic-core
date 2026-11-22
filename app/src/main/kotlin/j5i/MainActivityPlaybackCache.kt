package j5i.spatial8d

import android.app.Dialog
import j5i.spatial8d.provider.ProviderAudioCache
import j5i.spatial8d.provider.audioCacheId
import j5i.spatial8d.ui.ThemeUi
import java.io.File

internal fun MainActivity.showPlaybackCacheManager() {
    lateinit var dialog: Dialog
    dialog = ThemeUi.showSheet(this, palette, "缓存管理") {
        addView(ThemeUi.text(this@showPlaybackCacheManager,
            "清理可重新下载的试听音频和视频缓存；收藏下载、播放历史及进度不会删除。使用中的音频会保留。",
            12f, palette, role = ThemeUi.ROLE_SUB))
        addView(ThemeUi.button(this@showPlaybackCacheManager, palette, "清理试听音频缓存") {
            val directory = File(filesDir, "provider-audio")
            val protected = directory.listFiles().orEmpty().mapNotNull { audioCacheId(it.name) }
                .filter { musicStore.isFavorite(it) }.toMutableSet()
            protected += favoriteDownloads
            protected += currentQueue.map { it.id }
            protected += service?.queueSnapshot.orEmpty().map { it.id }
            currentItem?.let { protected += it.id }
            Thread {
                val result = runCatching { ProviderAudioCache.clearPlaybackCache(applicationContext, protected) }
                main.post {
                    result.onSuccess { (removed, failed) ->
                        toast(if (failed == 0) "已清理 $removed 个试听音频缓存" else "已清理 $removed 个，$failed 个清理失败")
                    }.onFailure { toast("音频缓存清理失败") }
                }
            }.start()
        })
        addView(ThemeUi.button(this@showPlaybackCacheManager, palette, "清理视频缓存") {
            if (externalVideoDialog?.isShowing == true) {
                toast("请先关闭视频再清理缓存")
            } else {
                val directory = File(filesDir, "video-cache")
                runCatching {
                    var removed = 0
                    var failed = 0
                    directory.listFiles().orEmpty().filter {
                        it.isFile && it.name.endsWith(".mp4") && it.canonicalFile.parentFile == directory.canonicalFile
                    }.forEach { if (it.delete()) removed++ else failed++ }
                    removed to failed
                }.onSuccess { (removed, failed) ->
                    toast(if (failed == 0) "已清理 $removed 个视频缓存" else "已清理 $removed 个，$failed 个清理失败")
                }.onFailure { toast("视频缓存清理失败") }
            }
        })
    }
    dialog.show()
}
