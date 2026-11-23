package j5i.spatial8d

import android.app.Dialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import j5i.spatial8d.audio.Lyrics
import j5i.spatial8d.audio.LyricsCandidate
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.ui.ThemeUi

internal fun MainActivity.scheduleLyricsPrefetch() {
        main.removeCallbacks(prefetchLyrics)
        if (providerStore.list().none { it.enabled }) return
        // 冷启动先完成恢复与当前歌词请求，再串行补曲库缓存，避免争抢首屏网络。
        main.postDelayed(prefetchLyrics, 8_000)
    }

internal fun MainActivity.loadLyrics(item: MusicItem, manualTitle: String? = null) {
        val generation = ++lyricsGeneration
        var finished = false
        currentLyrics = null
        pushLyricsToVisualizer()
        if (isViewReady(this@loadLyrics::lyricsView)) lyricsView.clear()
        val title = manualTitle ?: Lyrics.searchTitle(item.name, item.artist)
        main.postDelayed({
            if (!finished && generation == lyricsGeneration && currentItem?.uri == item.uri) {
                finished = true
                lyricsView.showError(manualTitle ?: Lyrics.displayTitle(item.name), timedOut = true)
            }
        }, 25_000)
        lyrics.load(title, item.artist, item.duration / 1000.0, { result ->
            if (finished || generation != lyricsGeneration || currentItem?.uri != item.uri) return@load
            finished = true
            result.onSuccess {
                currentLyrics = it
                lyricsView.setLyrics(it)
                pushLyricsToVisualizer()
                updateDriving()
            }.onFailure { lyricsView.showError(manualTitle ?: Lyrics.displayTitle(item.name)) }
        }, { progress ->
            if (!finished && generation == lyricsGeneration && currentItem?.uri == item.uri) lyricsView.showProgress(progress)
        }, allowNetwork = providerStore.list().any { it.enabled })
    }

    /** 把当前歌词推给音频可视化视图（含弹窗副本），用于穿梭模式叠加字幕。 */

internal fun MainActivity.pushLyricsToVisualizer() {
        if (isViewReady(this@pushLyricsToVisualizer::visualizerView)) visualizerView.setLyrics(currentLyrics)
        popupVisualizer?.setLyrics(currentLyrics)
        updateDriving()
    }

    /** 长按歌词触发手动修正：允许搜索更新，或从多路候选里选择并预览。 */

internal fun MainActivity.showLyricsCandidates(item: MusicItem) {
        if (providerStore.list().none { it.enabled }) { toast(t("lyrics.enable_source")); return }
        if (lyricsDialog?.isShowing == true) return
        val title = Lyrics.searchTitle(item.name, item.artist)
        val artist = item.artist
        val duration = item.duration / 1000.0
        val sheet = ThemeUi.showSheet(this, palette, t("lyrics.correct"), t("lyrics.correct.hint"), fullHeight = true) {
            val titleInput = ThemeUi.input(this@showLyricsCandidates, palette, t("lyrics.title")).apply { setText(title) }
            val artistInput = ThemeUi.input(this@showLyricsCandidates, palette, t("lyrics.artist.optional")).apply {
                setText(artist.takeUnless { it.equals("未知歌手", ignoreCase = true) } ?: "")
            }
            addView(titleInput)
            addView(artistInput)
            addView(ThemeUi.text(this@showLyricsCandidates, t("lyrics.choose"), 12f, palette, role = ThemeUi.ROLE_SUB))

            // 候选按来源分组，可切换 tab 只看某一源。LRCLIB 自带完整 LRC，通常最可靠。
            val sourceOrder = listOf("LRCLIB", "QQ", "酷狗")
            var bySource: Map<String, List<LyricsCandidate>> = emptyMap()
            var activeSource = "LRCLIB"
            val list = LinearLayout(this@showLyricsCandidates).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val status = ThemeUi.text(this@showLyricsCandidates, t("lyrics.searching"), 12f, palette, role = ThemeUi.ROLE_SUB)
            val tabRow = LinearLayout(this@showLyricsCandidates).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(tabRow)
            addView(list)
            addView(status)

            fun renderList() {
                list.removeAllViews()
                val items = bySource[activeSource].orEmpty()
                if (items.isEmpty()) {
                    if (bySource.isNotEmpty()) status.text = t("lyrics.no_candidates")
                    return
                }
                status.text = t("lyrics.candidates").format(activeSource, items.size)
                items.take(20).forEach { candidate ->
                    list.addView(LyricsCandidateRow(this@showLyricsCandidates, candidate, duration) { applyLyricsCandidate(item, candidate) })
                }
            }

            fun renderTabs() {
                tabRow.removeAllViews()
                sourceOrder.forEach { source ->
                    val count = bySource[source]?.size ?: 0
                    val label = if (count > 0) "$source ($count)" else source
                    val selected = source == activeSource
                    val tab = ThemeUi.text(this@showLyricsCandidates, label, 12f, palette,
                        role = if (selected) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_SUB).apply {
                        setPadding(ThemeUi.dp(this@showLyricsCandidates, 12f), ThemeUi.dp(this@showLyricsCandidates, 6f),
                            ThemeUi.dp(this@showLyricsCandidates, 12f), ThemeUi.dp(this@showLyricsCandidates, 6f))
                        background = if (selected) ThemeUi.bgDrawable(this@showLyricsCandidates, palette.control, 8f) else null
                        setOnClickListener { activeSource = source; renderTabs(); renderList() }
                    }
                    tabRow.addView(tab)
                }
            }

            fun runSearch() {
                val q = titleInput.text.toString().trim().ifEmpty { title }
                val a = artistInput.text.toString().trim()
                bySource = emptyMap()
                list.removeAllViews()
                status.text = t("lyrics.searching")
                renderTabs()
                lyrics.candidates(q, a, duration, { result ->
                    result.onSuccess { all ->
                        bySource = all.groupBy { it.source }
                        // 默认落在第一个有结果的源，避免打开就是空列表。
                        activeSource = sourceOrder.firstOrNull { !bySource[it].isNullOrEmpty() } ?: "LRCLIB"
                        renderTabs()
                        renderList()
                    }.onFailure { status.text = it.message ?: t("lyrics.search_failed") }
                }, { stage -> status.text = stage })
            }
            addView(ThemeUi.button(this@showLyricsCandidates, palette, t("lyrics.search"), ThemeUi.ROLE_ACCENT) { runSearch() })
            titleInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
            artistInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
            runSearch()
        }
        lyricsDialog = sheet
        sheet.show()
    }

    /** 选中候选后先预览歌词，确认后再写入本地缓存。 */

internal fun MainActivity.applyLyricsCandidate(item: MusicItem, candidate: LyricsCandidate) {
        val title = Lyrics.searchTitle(item.name, item.artist)
        val artist = item.artist
        val duration = item.duration / 1000.0
        var previewView: TextView? = null
        var subtitleView: TextView? = null
        val preview = Dialog(this).apply {
            val body = LinearLayout(this@applyLyricsCandidate).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ThemeUi.dp(this@applyLyricsCandidate, 18f), ThemeUi.dp(this@applyLyricsCandidate, 20f),
                    ThemeUi.dp(this@applyLyricsCandidate, 18f), ThemeUi.dp(this@applyLyricsCandidate, 10f))
                background = ThemeUi.bgDrawable(this@applyLyricsCandidate, palette.surface, 14f)
                addView(ThemeUi.text(this@applyLyricsCandidate, t("lyrics.preview"), 16f, palette, bold = true))
                val subtitle = ThemeUi.text(this@applyLyricsCandidate,
                    "${candidate.trackName} · ${candidate.artistName}", 12f, palette, role = ThemeUi.ROLE_SUB)
                subtitleView = subtitle
                addView(subtitle)
                val previewLines = TextView(this@applyLyricsCandidate).apply {
                    textSize = 13f
                    setTextColor(palette.text)
                    setPadding(0, ThemeUi.dp(this@applyLyricsCandidate, 10f), 0, ThemeUi.dp(this@applyLyricsCandidate, 10f))
                    maxLines = 14
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                previewView = previewLines
                addView(previewLines)
                addView(ThemeUi.row(this@applyLyricsCandidate, gravity = Gravity.END).apply {
                    addView(ThemeUi.text(this@applyLyricsCandidate, t("cancel"), 13f, palette, role = ThemeUi.ROLE_SUB).apply {
                        setPadding(ThemeUi.dp(this@applyLyricsCandidate, 12f), ThemeUi.dp(this@applyLyricsCandidate, 10f),
                            ThemeUi.dp(this@applyLyricsCandidate, 12f), ThemeUi.dp(this@applyLyricsCandidate, 10f))
                        setOnClickListener { dismiss() }
                    })
                    addView(ThemeUi.text(this@applyLyricsCandidate, t("lyrics.use"), 13f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                        setPadding(ThemeUi.dp(this@applyLyricsCandidate, 12f), ThemeUi.dp(this@applyLyricsCandidate, 10f),
                            ThemeUi.dp(this@applyLyricsCandidate, 12f), ThemeUi.dp(this@applyLyricsCandidate, 10f))
                        setOnClickListener {
                            dismiss()
                            lyricsDialog?.dismiss()
                            ++lyricsGeneration
                            lyrics.applyCandidate(title, artist, duration, candidate, { result ->
                                if (currentItem?.uri != item.uri) return@applyCandidate
                                result.onSuccess {
                                    currentLyrics = it
                                    lyricsView.setLyrics(it)
                                    updateDriving()
                                    pushLyricsToVisualizer()
                                }
                                    .onFailure { toast(it.message ?: t("lyrics.fetch_failed")) }
                            })
                        }
                    })
                })
            }
            setContentView(body)
            window?.setBackgroundDrawable(null)
            window?.setDimAmount(0.45f)
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setOnShowListener { window?.setLayout(ThemeUi.dp(this@applyLyricsCandidate, 320f), ViewGroup.LayoutParams.WRAP_CONTENT) }
        }
        preview.show()
        previewView?.text = t("lyrics.preview.loading")
        lyrics.previewCandidate(title, artist, duration, candidate, { result ->
            result.onSuccess { previewResult ->
                previewView?.text = if (previewResult.lines.isNotEmpty())
                    previewResult.lines.take(12).joinToString("\n") { it.text }
                else previewResult.plainLyrics.ifBlank { t("lyrics.preview.empty") }
                // 预览阶段拿到了真实 LRC，补上时长对比，帮助用户判断是否对得上原曲。
                val lrcDuration = previewResult.lines.lastOrNull()?.let { it.timeMs / 1000.0 } ?: 0.0
                val parts = mutableListOf("${candidate.trackName} · ${candidate.artistName}")
                if (duration > 0) parts += t("lyrics.current").format(time(duration))
                if (lrcDuration > 0) parts += "LRC ${time(lrcDuration)}"
                subtitleView?.text = parts.joinToString("  ·  ")
            }.onFailure {
                previewView?.text = "${t("lyrics.preview.failed")}: ${it.message}"
            }
        })
    }

private class LyricsCandidateRow(
        private val activity: MainActivity,
        private val candidate: LyricsCandidate,
        private val trackDurationSeconds: Double,
        private val onClick: () -> Unit
    ) : LinearLayout(activity) {
        init {
            orientation = LinearLayout.VERTICAL
            setPadding(ThemeUi.dp(activity, 10f), ThemeUi.dp(activity, 10f),
                ThemeUi.dp(activity, 10f), ThemeUi.dp(activity, 10f))
            background = ThemeUi.bgDrawable(activity, activity.palette.control, 8f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ThemeUi.dp(activity, 6f)
            }
            isClickable = true
            setOnClickListener { onClick() }

            // 主行：歌名 / 歌手 / 来源。
            addView(ThemeUi.text(activity,
                "${candidate.trackName}  ·  ${candidate.artistName}  ·  ${candidate.source}",
                13f, activity.palette, bold = true))

            // 副行：同步标记 + 当前曲目时长 vs 歌词/LRC 时长。
            val parts = mutableListOf<String>()
            parts += activity.t(if (candidate.synced) "lyrics.synced" else "lyrics.plain")
            if (trackDurationSeconds > 0) parts += activity.t("lyrics.current").format(activity.time(trackDurationSeconds))
            if (candidate.lrcDurationSeconds > 0) parts += "LRC ${activity.time(candidate.lrcDurationSeconds)}"
            else if (candidate.durationSeconds > 0) parts += activity.t("lyrics.duration").format(activity.time(candidate.durationSeconds))
            addView(ThemeUi.text(activity, parts.joinToString("  ·  "),
                11f, activity.palette, role = ThemeUi.ROLE_SUB))
        }
    }
