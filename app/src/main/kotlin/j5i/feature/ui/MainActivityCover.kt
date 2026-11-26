package j5i.spatial8d.feature.ui
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*
import j5i.spatial8d.MainActivity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.Mp3Cover
import j5i.spatial8d.audio.Lyrics
import j5i.spatial8d.ui.ThemeUi

internal fun MainActivity.coverBytesForExport(item: MusicItem): ByteArray? {
    val manual = musicStore.coverFor(item).takeIf { it.isNotBlank() }?.let { path ->
        runCatching { File(Uri.parse(path).path ?: path).takeIf(File::isFile)?.readBytes() }.getOrNull()
    }
    if (manual != null) return manual
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, Uri.parse(musicStore.readableUri(item).toString()))
            retriever.embeddedPicture
        } finally { retriever.release() }
    }.getOrNull()
}

internal fun MainActivity.refreshCover() {
    if (!isViewReady(this::disc)) return
    val item = currentItem?.takeUnless { isLive }
    val key = item?.uri.orEmpty()
    if (coverLoadedUri == key) return
    coverLoadedUri = key
    coverHasImage = false
    disc.setImageResource(j5i.spatial8d.R.drawable.ic_app_round_xxxhdpi)
    val generation = ++coverGeneration
    if (item == null) return
    Thread {
        val bitmap = coverBytesForExport(item)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        main.post {
            if (generation != coverGeneration || currentItem?.uri != item.uri || isDestroyed) return@post
            if (bitmap != null) {
                coverHasImage = true
                disc.setImageBitmap(bitmap.toCircularCover())
            } else if (autoCoverAttempts.add(item.uri)) fetchAutomaticCover(item, generation)
        }
    }.start()
}

private fun Bitmap.toCircularCover(): Bitmap {
    val edge = minOf(width, height)
    if (edge <= 0) return this
    val left = (width - edge) / 2
    val top = (height - edge) / 2
    val square = Bitmap.createBitmap(this, left, top, edge, edge)
    val result = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        shader = android.graphics.BitmapShader(square, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
    }
    canvas.drawCircle(edge / 2f, edge / 2f, edge / 2f, paint)
    return result
}

internal fun MainActivity.showCoverActions() {
    val item = currentItem?.takeUnless { isLive } ?: return
    ++coverGeneration
    lateinit var dialog: android.app.Dialog
    lateinit var titleInput: android.widget.EditText
    lateinit var artistInput: android.widget.EditText
    lateinit var status: TextView
    lateinit var results: LinearLayout
    var searchGeneration = 0
    var previews: java.util.concurrent.ExecutorService? = null
    fun search() {
        val title = titleInput.text.toString().trim()
        val artist = artistInput.text.toString().trim()
        if (title.isBlank()) { titleInput.error = "请输入歌曲名称"; return }
        val generation = ++searchGeneration
        previews?.shutdownNow()
        results.removeAllViews()
        status.text = "正在检索封面…"
        Thread {
            val result = runCatching { searchMatchingCovers(title, artist) }
            main.post {
                if (isDestroyed || !dialog.isShowing || generation != searchGeneration) return@post
                result.onSuccess { found ->
                    status.text = if (found.candidates.isEmpty()) "未找到匹配封面，可修改歌名或歌手后重试\n${found.status}"
                        else "找到 ${found.candidates.size} 个候选\n${found.status}"
                    previews = showCoverCandidates(item, found.candidates, dialog, results)
                }.onFailure { status.text = "封面检索失败，可修改条件后重试：${it.message ?: "网络错误"}" }
            }
        }.start()
    }
    val artist = defaultCoverArtist(item)
    dialog = ThemeUi.showSheet(this, palette, "检索封面", "可修改歌名和歌手，同时检索 QQ音乐、酷狗、网易云和 iTunes") {
        addView(ThemeUi.text(this@showCoverActions, "歌曲名称", 11f, palette, role = ThemeUi.ROLE_SUB))
        titleInput = ThemeUi.input(this@showCoverActions, palette, "请输入歌曲名称").apply {
            setText(Lyrics.searchTitle(item.name, artist))
        }
        addView(titleInput)
        addView(ThemeUi.text(this@showCoverActions, "歌手（可留空）", 11f, palette, role = ThemeUi.ROLE_SUB))
        artistInput = ThemeUi.input(this@showCoverActions, palette, "请输入歌手名称").apply { setText(artist) }
        addView(artistInput)
        addView(ThemeUi.button(this@showCoverActions, palette, "检索封面", ThemeUi.ROLE_ACCENT) {
            search()
        })
        if (coverHasImage) addView(ThemeUi.button(this@showCoverActions, palette, "恢复自动封面") {
            autoCoverAttempts.remove(item.uri)
            updateDisplayedCover(item, "")
            dialog.dismiss()
        })
        status = ThemeUi.text(this@showCoverActions, "修改条件后点击检索封面，点击图片即可保存", 11f, palette, role = ThemeUi.ROLE_SUB)
        addView(status)
        results = LinearLayout(this@showCoverActions).apply { orientation = LinearLayout.VERTICAL }
        addView(results)
    }
    dialog.setOnDismissListener { ++searchGeneration; previews?.shutdownNow() }
    dialog.show()
    if (!coverHasImage) search()
}

private fun defaultCoverArtist(item: MusicItem): String = when {
        item.name.contains(" - ") -> item.name.substringBefore(" - ")
        item.name.contains(" · ") -> item.name.substringAfterLast(" · ")
        else -> item.artist
    }.trim().takeUnless { it in setOf("未知歌手", "<unknown>", "unknown", "Unknown Artist") }.orEmpty()

private fun searchMatchingCovers(title: String, artist: String): CoverSearchResult {
    val converters = object : ThreadLocal<android.icu.text.Transliterator?>() {
        override fun initialValue() = if (android.os.Build.VERSION.SDK_INT >= 29)
            android.icu.text.Transliterator.getInstance("Traditional-Simplified") else null
    }
    return CoverSearch.search(title, artist, { converters.get()?.transliterate(it) ?: it }) { message ->
        if (android.util.Log.isLoggable("8DCover", android.util.Log.INFO)) android.util.Log.i("8DCover", message())
    }
}

private fun MainActivity.fetchAutomaticCover(item: MusicItem, generation: Int) {
    Thread {
        val result = runCatching {
            val artist = defaultCoverArtist(item)
            val candidate = searchMatchingCovers(Lyrics.searchTitle(item.name, artist), artist).candidates.firstOrNull()
                ?: return@Thread
            val bytes = CoverSearch.imageBytes(candidate.imageUrl)
            require(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null)
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(item.uri.toByteArray())
                .joinToString("") { "%02x".format(it) }
            File(filesDir, "covers/$key.auto.img").apply { parentFile?.mkdirs(); writeBytes(bytes) }.absolutePath
        }
        main.post {
            if (isDestroyed || generation != coverGeneration || currentItem?.uri != item.uri || isLive || coverHasImage) return@post
            result.onSuccess { path -> updateDisplayedCover(item, path) }
        }
    }.start()
}

private fun MainActivity.showCoverCandidates(item: MusicItem, ordered: List<CoverCandidate>, sheet: android.app.Dialog,
    results: LinearLayout): java.util.concurrent.ExecutorService? {
    if (ordered.isEmpty()) return null
    val previews = mutableListOf<Pair<ImageView, String>>()
    val grid = GridLayout(this).apply { columnCount = 3 }
                    ordered.forEachIndexed { index, row ->
                        val previewUrl = row.previewUrl
                        val title = "${row.title}\n${row.artist}\n${row.source} · ${row.album.ifBlank { "专辑未知" }}"
                        val image = ImageView(this@showCoverCandidates).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(palette.surfaceAlt)
                            layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(context, 104f))
                        }
                        val tile = LinearLayout(this@showCoverCandidates).apply {
                            orientation = LinearLayout.VERTICAL
                            background = ThemeUi.bgDrawable(context, palette.surfaceAlt, 8f)
                            setPadding(ThemeUi.dp(context, 4f), ThemeUi.dp(context, 4f), ThemeUi.dp(context, 4f), ThemeUi.dp(context, 4f))
                            addView(image)
                            addView(TextView(context).apply {
                                text = title
                                textSize = 10f
                                setTextColor(palette.text)
                                maxLines = 3
                                gravity = Gravity.CENTER
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(context, 52f))
                            })
                            setOnClickListener {
                                downloadCover(item, row.imageUrl, sheet)
                            }
                        }
                        val params = GridLayout.LayoutParams(
                            GridLayout.spec(index / 3), GridLayout.spec(index % 3, 1, 1f)
                        ).apply {
                            width = 0
                            height = ThemeUi.dp(this@showCoverCandidates, 164f)
                            setMargins(ThemeUi.dp(this@showCoverCandidates, 3f), ThemeUi.dp(this@showCoverCandidates, 3f),
                                ThemeUi.dp(this@showCoverCandidates, 3f), ThemeUi.dp(this@showCoverCandidates, 3f))
                        }
                        grid.addView(tile, params)
                        previews += image to previewUrl
                    }
    results.addView(grid)
    val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
                previews.forEach { (image, url) -> executor.execute {
                    if (Thread.currentThread().isInterrupted || isDestroyed) return@execute
                    val bitmap = runCatching {
                        val bytes = CoverSearch.imageBytes(url)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                    if (bitmap != null) main.post {
                        if (sheet.isShowing && !isDestroyed && image.isAttachedToWindow) image.setImageBitmap(bitmap)
                    }
                } }
    executor.shutdown()
    return executor
}

private fun MainActivity.downloadCover(item: MusicItem, url: String, dialog: android.app.Dialog) {
    Thread {
        val result = runCatching {
            val bytes = CoverSearch.imageBytes(url)
            require(bytes.size in 1024..5_000_000)
            require(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) { "封面图片无效" }
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(item.uri.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(filesDir, "covers/$key.img").apply { parentFile?.mkdirs(); writeBytes(bytes) }
            val uri = Uri.parse(item.uri)
            val original = uri.path?.let(::File)
            val embedded = uri.scheme == "file" && original != null && original.extension.equals("mp3", true) &&
                runCatching { Mp3Cover.write(original, bytes) }.isSuccess
            file.absolutePath to embedded
        }
        main.post {
            result.onSuccess { (path, embedded) ->
                updateDisplayedCover(item, path)
                dialog.dismiss()
                toast(if (embedded) "封面已写入歌曲文件" else "封面已关联歌曲，原文件未修改")
            }.onFailure { toast("封面保存失败：${it.message ?: "网络错误"}") }
        }
    }.start()
}

private fun MainActivity.updateDisplayedCover(item: MusicItem, path: String) {
    val updated = musicStore.updateCover(item, path)
    fun replace(items: List<MusicItem>) = items.map { if (it.uri == item.uri) it.copy(coverUri = path) else it }
    songs = replace(songs).toMutableList()
    queue = replace(queue)
    currentQueue = replace(currentQueue)
    onlineQueue = replace(onlineQueue)
    service?.syncQueue(currentQueue, currentUri)
    if (currentItem?.uri == item.uri && !isLive) {
        currentItem = updated
        coverLoadedUri = null
        refreshCover()
        refreshPlayback()
    }
}
