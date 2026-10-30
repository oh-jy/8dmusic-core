package jiangyi.spatial8d.ui

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import jiangyi.spatial8d.data.MusicItem
import jiangyi.spatial8d.data.MusicStore

class PlaylistDialog(
    private val store: MusicStore,
    private val dark: Boolean,
    private val onPlay: (MusicItem) -> Unit,
    private val onChanged: (List<MusicItem>) -> Unit,
    private var source: List<MusicItem>
) : DialogFragment() {
    private lateinit var list: LinearLayout
    private lateinit var tabs: LinearLayout
    private var dragging: Int? = null
    private var dragStartY = 0f
    private var dragTarget = -1

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 16)
            setBackgroundColor(if (dark) 0xff1e2126.toInt() else Color.WHITE)
        }
        root.addView(TextView(context).apply { text = "播放列表"; textSize = 20f })
        root.addView(LinearLayout(context).apply {
            addView(button("扫描") { (activity as? PlaylistHost)?.scanMusic(); dismiss() })
            addView(button("另存列表") { savePlaylist() })
            if (store.hidden.isNotEmpty()) addView(button("恢复 ${store.hidden.size}") {
                store.restoreHidden(); refresh()
            })
        })
        tabs = LinearLayout(context)
        root.addView(tabs)
        val input = EditText(context).apply {
            hint = "过滤：歌名 / 歌手 / 专辑"
            setText(store.query)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(value: Editable?) { store.query = value.toString(); refresh() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        root.addView(input)
        root.addView(LinearLayout(context).apply {
            listOf("name" to "文件名", "artist" to "歌手", "album" to "专辑", "manual" to "自定义").forEach { (key, label) ->
                addView(button(label) { store.query = input.text.toString(); store.setSort(key); refresh() })
            }
            addView(button(if (store.ascending) "↑" else "↓") { store.toggleAscending(); refresh() })
        })
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(list)
        })
        root.addView(TextView(context).apply {
            text = "点按播放 · 长按歌曲后上下拖动排序 · 长按页签删除"
            textSize = 12f
        })
        refresh(input)
        return Dialog(context).apply {
            setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.78).toInt())
        dialog?.window?.setGravity(Gravity.BOTTOM)
    }

    private fun refresh(input: EditText? = null) {
        input?.let { store.query = it.text.toString() }
        val songs = store.view(source)
        tabs.removeAllViews()
        tabs.addView(button("全部") { store.playlistIndex = -1; refresh() })
        store.playlists.forEachIndexed { index, playlist ->
            tabs.addView(button(playlist.name) { store.playlistIndex = index; refresh() }.apply {
                setOnLongClickListener {
                    AlertDialog.Builder(requireContext()).setMessage("删除列表“${playlist.name}”？")
                        .setPositiveButton("删除") { _, _ -> store.deletePlaylist(index); refresh() }
                        .setNegativeButton("取消", null).show()
                    true
                }
            })
        }
        list.removeAllViews()
        songs.forEachIndexed { index, song -> list.addView(row(song, index, songs)) }
        if (songs.isEmpty()) list.addView(TextView(requireContext()).apply { text = "没有匹配的音频"; setPadding(12, 40, 12, 40) })
        onChanged(songs)
    }

    private fun row(song: MusicItem, index: Int, songs: List<MusicItem>): View {
        val context = requireContext()
        val text = TextView(context).apply {
            text = "${song.name}\n${song.artist} · ${song.album} · ${song.duration / 60000}:${"%02d".format(song.duration / 1000 % 60)}"
            setPadding(12, 20, 12, 20)
            textSize = 14f
        }
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(button("移除") {
                AlertDialog.Builder(context).setMessage("从列表移除“${song.name}”？不删除源文件")
                    .setPositiveButton("移除") { _, _ -> store.hide(song); refresh() }
                    .setNegativeButton("取消", null).show()
            })
            setOnClickListener { onPlay(song); dismiss() }
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_DOWN) dragStartY = event.rawY
                val from = dragging ?: return@setOnTouchListener false
                parent.requestDisallowInterceptTouchEvent(true)
                if (event.action == MotionEvent.ACTION_MOVE) {
                    val to = (from + ((event.rawY - dragStartY) / view.height).toInt()).coerceIn(songs.indices)
                    if (to != from) {
                        dragTarget = to
                        text.text = "移动到 ${to + 1} / ${songs.size}\n${song.name}"
                    }
                    true
                } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    if (dragTarget >= 0) store.move(from, dragTarget, songs)
                    dragging = null
                    dragTarget = -1
                    refresh()
                    true
                } else false
            }
            setOnLongClickListener {
                dragging = index
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                Toast.makeText(context, "上下拖动后松手完成排序", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun savePlaylist() {
        val input = EditText(requireContext()).apply { hint = "新播放列表名称" }
        AlertDialog.Builder(requireContext()).setTitle("另存当前列表").setView(input)
            .setPositiveButton("保存") { _, _ ->
                val songs = store.view(source)
                if (store.addPlaylist(input.text.toString(), songs)) {
                    dismiss()
                    PlaylistDialog(store, dark, onPlay, onChanged, source).show(parentFragmentManager, "playlist")
                } else Toast.makeText(context, "名称为空、列表为空或已达到 10 个上限", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun button(label: String, action: () -> Unit) = Button(requireContext()).apply {
        text = label
        setOnClickListener { action() }
    }
}

interface PlaylistHost {
    fun songs(): List<MusicItem>
    fun scanMusic()
}
