package j5i.spatial8d.ui

import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.widget.TextView
import android.widget.Toast
import j5i.spatial8d.data.FAVORITES_INDEX
import j5i.spatial8d.data.FAVORITES_NAME
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.MusicStore
import kotlin.math.abs

/** 底部播放列表：过滤、排序、自建列表和权限引导均操作 MusicStore 的同一份状态。 */
class PlaylistDialog(
    private val context: android.content.Context,
    private val store: MusicStore,
    private val dark: Boolean,
    private val currentId: Long,
    private val onPlay: (MusicItem) -> Unit,
    private val onChanged: (List<MusicItem>) -> Unit,
    private var source: List<MusicItem>
) {
    private var dialog: Dialog? = null
    private val p get() = ThemeUi.palette(dark)
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var tabs: LinearLayout
    private lateinit var count: TextView
    private lateinit var status: TextView
    private lateinit var sortRow: LinearLayout
    private lateinit var search: EditText
    private var songs = emptyList<MusicItem>()
    private var dragging = -1
    private var dragTarget = -1
    private var dragView: View? = null
    private var dragOffsetY = 0f
    private var dragOriginTop = 0f
    private var lastDragRawY = 0f
    private val rowTops = mutableListOf<Float>()
    private val autoScroll = object : Runnable {
        override fun run() {
            if (dragging < 0 || !::listScroll.isInitialized) return
            val localY = lastDragRawY - listLocationOnScreen()[1]
            val edge = dp(48).toFloat()
            val speed = dp(6)
            val distance = when {
                localY < edge -> -(speed * (1f - localY.coerceAtLeast(0f) / edge)).toInt()
                localY > listScroll.height - edge -> (speed * (1f - (listScroll.height - localY).coerceAtLeast(0f) / edge)).toInt()
                else -> 0
            }
            if (distance != 0) {
                listScroll.scrollBy(0, distance)
                moveDrag(lastDragRawY)
            }
            listScroll.postOnAnimation(this)
        }
    }
    private var saveOpen = false
    private var reorderMode = false
    private fun t(key: String) = AppLanguage.text(context, key)

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun dismiss() {
        resetDrag()
        dialog?.dismiss()
    }

    fun refreshContent() {
        refresh()
    }

    fun show() {
        dialog = createDialog().also {
            it.show()
            ThemeUi.layoutSheet(it)
        }
    }

    private fun close() {
        resetDrag()
        dialog?.dismiss()
    }

    private fun createDialog(): Dialog {
        val c = context
        root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(12))
            background = ThemeUi.bgDrawable(c, p.surface, 18f)
        }
        root.addView(FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(22))
            addView(View(c).apply {
                background = ThemeUi.bgDrawable(c, if (dark) 0xff4a4f57.toInt() else 0xffd5d9df.toInt(), 2f)
                layoutParams = FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER)
            })
            setOnClickListener { close() }
        })
        count = ThemeUi.text(c, "", 11f, p, role = ThemeUi.ROLE_SUB)
        root.addView(ThemeUi.row(c).apply {
            addView(ThemeUi.text(c, t("playlist"), 15f, p, bold = true))
            addView(count.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) } })
            addView(button(t("scan")) { (context as? PlaylistHost)?.scanMusic(); close() })
            addView(ThemeUi.text(c, t("close"), 11f, p, role = ThemeUi.ROLE_SUB).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { close() }
            })
        })
        tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(ThemeUi.row(c).apply {
            addView(HorizontalScrollView(c).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            })
            addView(button(if (saveOpen) t("playlist.collapse") else t("playlist.save")) { toggleSave() })
        })
        search = ThemeUi.input(c, p, t("playlist.filter")).apply {
            setText(store.query)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { store.query = s.toString(); refresh() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        root.addView(search)
        sortRow = ThemeUi.row(c)
        root.addView(sortRow)
        list = DropIndicatorList(c, this).apply { orientation = LinearLayout.VERTICAL }
        // 在子 View 上层画插入线；拖动时其他行让位，拖动的行随手移动。
        list.setWillNotDraw(false)
        val needsPermission = !hasAudioPermission()
        if (needsPermission) {
            // 无权限也保留手动添加入口；引导按钮与系统设置都可操作。
            list.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(36), dp(18), dp(24))
                addView(ThemeUi.text(c, "需要音频权限才能读取本机歌曲", 13f, p, bold = true))
                addView(ThemeUi.text(c, "首次使用请允许；若已拒绝，请到系统设置开启「音乐和音频」。也可以直接添加单个音频文件。", 11f, p, role = ThemeUi.ROLE_SUB))
                addView(ThemeUi.row(c).apply {
                    gravity = Gravity.CENTER
                    addView(button("重新请求授权") { (context as? PlaylistHost)?.scanMusic(); dismiss() })
                    addView(button("去设置开启") {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}")))
                    })
                })
                addView(ThemeUi.text(c, "或点「＋ 添加」选择单个音频", 11f, p, role = ThemeUi.ROLE_ACCENT))
            })
        }
        listScroll = ScrollView(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            addView(list)
        }
        root.addView(listScroll)
        status = ThemeUi.text(c, "点按播放 · 长按歌曲拖动排序 · 移除不会删除源文件", 11f, p, role = ThemeUi.ROLE_SUB)
        root.addView(status)
        refresh()
        return Dialog(c).apply {
            setContentView(root, ViewGroup.LayoutParams(-1, -1))
            window?.setBackgroundDrawable(null)
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0.45f)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun drawInsertionLine(canvas: Canvas) {
        if (dragging < 0 || dragTarget !in rowTops.indices) return
        val gap = when {
            dragTarget < dragging -> rowTops[dragTarget] - dp(3)
            dragTarget > dragging -> rowTops[dragTarget] + rowHeight()
            else -> return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent; strokeWidth = dp(2).toFloat() }
        canvas.drawLine(dp(24).toFloat(), gap, (list.width - dp(8)).toFloat(), gap, paint)
        canvas.drawCircle(dp(24).toFloat(), gap, dp(3).toFloat(), paint)
    }


    private fun refresh() {
        if (!::list.isInitialized) return
        songs = store.view(source)
        count.text = if (songs.size == source.size) "${source.size} 首" else "${songs.size} / ${source.size} 首"
        tabs.removeAllViews()
        tabs.addView(chip(t("playlist.all"), store.playlistIndex == -1) { store.playlistIndex = -1; refresh() })
        tabs.addView(chip("♥ ${t("playlist.favorites")}", store.playlistIndex == FAVORITES_INDEX) { store.playlistIndex = FAVORITES_INDEX; refresh() })
        if (store.hidden.isNotEmpty()) tabs.addView(ThemeUi.text(context, "恢复 ${store.hidden.size}", 11f, p, role = ThemeUi.ROLE_ACCENT).apply {
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { store.restoreHidden(); refresh() }
        })
        store.playlists.forEachIndexed { index, playlist ->
            tabs.addView(chip(playlist.name, store.playlistIndex == index) { store.playlistIndex = index; refresh() }.apply {
                // 内置收藏列表不在 playlists 集合内，只有用户自建列表可删除。
                setOnLongClickListener {
                    lateinit var sheet: Dialog
                    sheet = ThemeUi.showSheet(context, p, t("playlist.delete.title"), t("playlist.delete.confirm").format(playlist.name)) {
                        addView(sheetButton(t("delete"), ThemeUi.ROLE_ACCENT_FILL) {
                            sheet.dismiss(); store.deletePlaylist(index); refresh()
                        })
                    }
                    sheet.show()
                    true
                }
            })
        }
        tabs.addView(chip(t("playlist.add"), false) { (context as? PlaylistHost)?.addAudio() })
        sortRow.removeAllViews()
        val actions = ThemeUi.row(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        sortRow.addView(ThemeUi.text(context, t("playlist.sort"), 11f, p, role = ThemeUi.ROLE_SUB))
        listOf("name" to t("sort.name"), "artist" to t("sort.artist"), "album" to t("sort.album"), "time" to t("sort.time"), "manual" to t("sort.manual")).forEach { (key, label) ->
            sortRow.addView(chip(label, store.sort == key) { store.setSort(key); refresh() })
        }
        // The trailing action pair remains fixed after Custom in the UTS order row.
        if (store.sort != "manual") actions.addView(chip(if (store.ascending) "↑" else "↓", false) { store.toggleAscending(); refresh() })
        else actions.addView(chip(if (reorderMode) "完成排序" else "整理顺序", false) {
            reorderMode = !reorderMode
            status.text = if (reorderMode) "整理模式：上下拖动歌曲，点「完成排序」退出" else "排序已完成"
            refresh()
        })
        list.removeAllViews()
        rowTops.clear()
        songs.forEachIndexed { index, song -> list.addView(row(song, index)) }
        val active = songs.indexOfFirst { it.id == currentId }
        // 等底部弹层完成布局后再滚动，提前 scrollTo 拿不到有效高度。
        if (active >= 0) scrollToCurrent(active)
        if (songs.isEmpty() && hasAudioPermission()) list.addView(ThemeUi.text(context,
            if (store.playlistIndex == FAVORITES_INDEX) "收藏为空，点播放控制处的空心爱心添加"
            else if (source.isEmpty()) "请先添加或扫描音频" else "没有匹配的音频", 12f, p, role = ThemeUi.ROLE_SUB).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(40), dp(16), dp(40))
        })
        sortRow.addView(actions)
        actions.addView(locateButton((context as? PlaylistHost)?.isLocalMusicPlaying() == true))
        onChanged(songs)
    }

    private fun scrollToCurrent(active: Int = songs.indexOfFirst { it.id == currentId }) {
        if (active < 0) {
            Toast.makeText(context, "当前歌曲不在此列表或筛选结果中", Toast.LENGTH_SHORT).show()
            return
        }
        listScroll.post {
            val target = list.getChildAt(active) ?: return@post
            val center = target.top + target.height / 2
            listScroll.smoothScrollTo(0, (center - listScroll.height / 2).coerceAtLeast(0))
        }
    }

    private fun locateButton(localPlaying: Boolean): TextView =
        ThemeUi.text(context, "◎", 20f, p, role = ThemeUi.ROLE_ACCENT).apply {
            contentDescription = "定位当前歌曲"
            gravity = Gravity.CENTER
            alpha = if (localPlaying) 1f else 0.4f
            setPadding(dp(10), dp(3), dp(4), dp(3))
            setOnClickListener {
                if (localPlaying) scrollToCurrent()
                else Toast.makeText(context, "仅本地音乐播放时可定位", Toast.LENGTH_SHORT).show()
            }
        }

    private fun row(song: MusicItem, index: Int): View {
        val c = context
        val active = song.id == currentId
        val row = ThemeUi.row(c).apply {
            setPadding(dp(2), 0, 0, 0)
            background = ThemeUi.bgDrawable(c, if (active) p.surfaceAlt else p.surface, 8f)
            layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(4) }
        }
        row.addView(ThemeUi.text(c, "☰", 17f, p, role = ThemeUi.ROLE_FAINT).apply {
            tag = "dragHandle"
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(54))
        })
        row.addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f)
            // 列表中去掉 .mp3 / .flac 等扩展名，保持标题干净；原始 song.name 仍用于播放与检索。
            val displayName = song.name.replace(Regex("\\.[A-Za-z0-9]{1,8}$"), "")
            addView(ThemeUi.text(c, (if (active) "▶ " else "") + displayName, 13f, p, bold = active, role = if (active) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT).apply { ThemeUi.hideOverflow(this) })
            addView(ThemeUi.text(c, "${song.artist} · ${song.album} · ${clock(song.duration)}", 10f, p, role = ThemeUi.ROLE_SUB).apply { ThemeUi.hideOverflow(this) })
        })
        row.addView(ThemeUi.text(c, "⋯", 18f, p, role = ThemeUi.ROLE_ACCENT).apply {
            contentDescription = t("playlist.actions")
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showRowActions(song) }
        })
        row.setOnClickListener { if (!reorderMode && dragging < 0) { onPlay(song); dismiss() } }
        row.findViewWithTag<TextView>("dragHandle")?.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginDrag(index, row, event.rawY)
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> { moveDrag(event.rawY); true }
                MotionEvent.ACTION_UP -> { finishDrag(cancel = false); true }
                MotionEvent.ACTION_CANCEL -> { finishDrag(cancel = true); true }
                else -> dragging >= 0
            }
        }
        return row
    }

    private fun showRowActions(song: MusicItem) {
        lateinit var sheet: Dialog
        sheet = ThemeUi.showSheet(context, p, song.name.take(28), "${song.artist} · ${song.album}") {
            addView(sheetButton(t("remove"), ThemeUi.ROLE_CONTROL) {
                sheet.dismiss()
                confirmRemove(song)
            })
            addView(sheetButton(t("metadata.edit"), ThemeUi.ROLE_CONTROL) {
                sheet.dismiss()
                (context as? PlaylistMetadataHost)?.editMetadata(song)
            })
        }
        sheet.show()
    }

    private fun confirmRemove(song: MusicItem) {
        lateinit var sheet: Dialog
        sheet = ThemeUi.showSheet(context, p, t("remove"), t("playlist.remove.confirm").format(song.name)) {
            addView(sheetButton(t("remove"), ThemeUi.ROLE_ACCENT_FILL) {
                sheet.dismiss()
                val playlist = store.playlistIndex
                if (playlist == FAVORITES_INDEX) store.toggleFavorite(song.id)
                else if (playlist >= 0) store.removeFromPlaylist(playlist, song) else store.hide(song)
                status.text = t("playlist.removed")
                refresh()
            })
        }
        sheet.show()
    }

    private fun sheetButton(label: String, role: String, action: () -> Unit) =
        ThemeUi.button(context, p, label, role) { action() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
                .apply { topMargin = dp(8) }
        }

    private fun beginDrag(index: Int, row: View, rawY: Float) {
        if (dragging >= 0) return
        // 以用户当前看见的顺序为基准，不套用可能已过期的手动缓存。
        store.adoptManualOrder(songs.map { it.id })
        store.commitManualOrder()
        reorderMode = true
        dragging = index
        dragTarget = index
        dragView = row
        dragOriginTop = row.top.toFloat()
        dragOffsetY = rawY - listLocationOnScreen()[1] + listScroll.scrollY - dragOriginTop
        lastDragRawY = rawY
        rowTops.clear()
        for (i in 0 until list.childCount) rowTops.add(list.getChildAt(i).top.toFloat())
        row.elevation = dp(8).toFloat()
        row.alpha = 0.96f
        list.invalidate()
        listScroll.postOnAnimation(autoScroll)
        status.text = "拖动中：亮线显示插入位置"
    }

    private fun moveDrag(rawY: Float) {
        val row = dragView ?: return
        lastDragRawY = rawY
        val desired = rawY - listLocationOnScreen()[1] + listScroll.scrollY - dragOffsetY
        row.translationY = desired - dragOriginTop
        val center = desired + row.height / 2f
        // 只越过相邻行中心才切换目标，消除按原始位置取最近值产生的反向死区。
        val hysteresis = dp(4)
        while (dragTarget < rowTops.lastIndex && center > rowTops[dragTarget + 1] + row.height / 2f + hysteresis) {
            dragTarget++
        }
        while (dragTarget > 0 && center < rowTops[dragTarget - 1] + row.height / 2f - hysteresis) {
            dragTarget--
        }
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child === row) continue
            child.translationY = when {
                dragging < dragTarget && i in (dragging + 1)..dragTarget -> -(row.height + dp(4)).toFloat()
                dragging > dragTarget && i in dragTarget until dragging -> (row.height + dp(4)).toFloat()
                else -> 0f
            }
            child.alpha = if (i == dragTarget) 0.72f else 1f
        }
        status.text = "插入第 ${dragTarget + 1} 位 · 松手保存"
        list.invalidate()
    }

    private fun finishDrag(cancel: Boolean) {
        if (dragView == null) return
        if (!cancel && dragTarget != dragging) store.move(dragging, dragTarget, songs)
        store.commitManualOrder()
        dragging = -1
        dragTarget = -1
        dragView = null
        rowTops.clear()
        status.text = if (cancel) "已取消排序" else "顺序已保存"
        refresh()
    }

    private fun resetDrag() {
        if (::listScroll.isInitialized) listScroll.removeCallbacks(autoScroll)
        dragging = -1
        dragTarget = -1
        dragView = null
        rowTops.clear()
        reorderMode = false
    }

    private fun listLocationOnScreen() = IntArray(2).also(listScroll::getLocationOnScreen)

    private fun rowHeight(): Float = (dragView?.layoutParams as? LinearLayout.LayoutParams)?.let {
        (dragView!!.height + it.bottomMargin).toFloat()
    } ?: dp(58).toFloat()

    private class DropIndicatorList(context: android.content.Context, private val owner: PlaylistDialog) :
        LinearLayout(context) {
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            owner.drawInsertionLine(canvas)
        }
    }

    private fun toggleSave() {
        saveOpen = !saveOpen
        val index = root.indexOfChild(search)
        if (saveOpen) {
            val row = ThemeUi.row(context).apply { tag = "save" }
            val input = ThemeUi.input(context, p, t("playlist.new"))
            row.addView(input.apply { layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f) })
            row.addView(ThemeUi.text(context, t("save"), 12f, p, role = ThemeUi.ROLE_ACCENT).apply {
                setPadding(dp(10), dp(8), dp(4), dp(8))
                setOnClickListener {
                    if (store.addPlaylist(input.text.toString(), store.view(source))) { saveOpen = false; root.removeView(row); refresh() }
                    else Toast.makeText(context, "名称为空、列表为空或已达到 10 个上限", Toast.LENGTH_SHORT).show()
                }
            })
            root.addView(row, index)
        } else root.findViewWithTag<View>("save")?.let(root::removeView)
    }

    private fun button(label: String, action: () -> Unit) = ThemeUi.button(context, p, label) { action() }.apply {
        textSize = 11f; layoutParams = LinearLayout.LayoutParams(-2, dp(32)).apply { leftMargin = dp(6) }
    }
    private fun chip(label: String, selected: Boolean, action: () -> Unit) = ThemeUi.button(context, p, label, if (selected) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_CONTROL) { action() }.apply {
        textSize = 11f; setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(-2, dp(30)).apply { leftMargin = dp(6); topMargin = dp(4); bottomMargin = dp(4) }
    }
    private fun dp(value: Int) = ThemeUi.dp(context, value.toFloat())
    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    private fun clock(ms: Long) = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
}
interface PlaylistHost {
    fun songs(): List<MusicItem>
    fun scanMusic()
    fun addAudio()
    fun isLocalMusicPlaying(): Boolean = false
}
interface PlaylistMetadataHost { fun editMetadata(item: MusicItem) }
