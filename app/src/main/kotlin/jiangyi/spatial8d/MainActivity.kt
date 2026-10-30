package jiangyi.spatial8d

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jiangyi.spatial8d.audio.AudioDecoder
import jiangyi.spatial8d.audio.PlaybackService
import jiangyi.spatial8d.core.DEFAULT_SPACE_PRESET
import jiangyi.spatial8d.core.SLIDERS
import jiangyi.spatial8d.core.SPACE_PRESETS
import jiangyi.spatial8d.core.SpacePreset
import jiangyi.spatial8d.core.PARAM_INFO
import jiangyi.spatial8d.core.TransformParams
import jiangyi.spatial8d.core.clampSlider
import jiangyi.spatial8d.core.formatValue
import jiangyi.spatial8d.data.MusicItem
import jiangyi.spatial8d.data.MusicStore
import jiangyi.spatial8d.data.PlayMode
import jiangyi.spatial8d.data.PresetStore
import jiangyi.spatial8d.ui.CurveView
import jiangyi.spatial8d.ui.SpatialVisualization
import jiangyi.spatial8d.ui.PlaylistDialog
import jiangyi.spatial8d.ui.PlaylistHost
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), PlaylistHost {
    private lateinit var musicStore: MusicStore
    private lateinit var presetStore: PresetStore
    private var service: PlaybackService? = null
    private var bound = false
    private var songs = mutableListOf<MusicItem>()
    private var queue = listOf<MusicItem>()
    private var currentIndex = -1
    private var preset = DEFAULT_SPACE_PRESET
    private var customPresets = mutableListOf<SpacePreset>()
    private var loading = false
    private var loadGeneration = 0
    private var autoplayRequests = 0
    private var currentUri: String? = null
    private var exporting = false
    private var dark = true
    private var transform = TransformParams()
    private var reverse = false
    private val main = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("spatial8d", Context.MODE_PRIVATE) }

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var songTitle: TextView
    private lateinit var position: TextView
    private lateinit var seek: SeekBar
    private lateinit var visualization: SpatialVisualization
    private lateinit var curve: CurveView
    private lateinit var presetSpinner: Spinner
    private lateinit var directionSpinner: Spinner
    private lateinit var playButton: Button
    private lateinit var parameterPanel: LinearLayout
    private val sliderValues = mutableMapOf<String, TextView>()
    private val sliderControls = mutableMapOf<String, SeekBar>()
    private var updatingControls = false
    private lateinit var durationHint: TextView

    private val audioFocus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) main.post { service?.player?.pause(); refreshPlayback() }
            }
            .build()
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlaybackService.Binder).service
            service?.player?.onEnd = { main.post { next(auto = true) } }
            service?.player?.onError = { main.post { toast(it) } }
            bound = true
            refreshAll()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addAndPlay(uri)
    }
    private val saveWav = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) exportWav(uri)
    }
    private val saveMp3 = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        if (uri != null) exportMp3(uri)
    }
    private val requestAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scanMusic() else toast("未授予音乐库权限，仍可通过“添加音频”选择文件")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        musicStore = MusicStore(this)
        presetStore = PresetStore(this)
        customPresets = presetStore.load().toMutableList()
        songs = (musicStore.cached() + musicStore.library()).distinctBy { it.uri }.toMutableList()
        dark = preferences.getBoolean("dark", true)
        buildUi()
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(24, bars.top + 16, 24, bars.bottom + 16)
                insets
            }
        }
        startService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (hasAudioPermission()) scanMusic()
        restoreLast()
        main.post(ticker)
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 36, 24, 24)
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("8D 空间音频", 23f, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(button("主题") { dark = !dark; refreshTheme() })
            addView(button("说明") {
                AlertDialog.Builder(this@MainActivity).setTitle("参数说明")
                    .setMessage(PARAM_INFO.joinToString("\n\n") { "${it.label}\n${it.desc}" })
                    .setPositiveButton("关闭", null).show()
            })
            addView(button("导出") { chooseExport() })
        })
        visualization = SpatialVisualization(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 380)
        }
        root.addView(visualization)
        status = text("请选择音频文件", 14f)
        root.addView(status)

        root.addView(section("空间风格"))
        presetSpinner = Spinner(this)
        root.addView(presetSpinner)
        root.addView(text(preset.note, 12f))
        root.addView(LinearLayout(this).apply {
            directionSpinner = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("顺时针", "逆时针", "随机巡游"))
                onItemSelectedListener = selected { position ->
                    if (!updatingControls) updateParameter("direction", when (position) {
                        1 -> -1.0
                        2 -> 0.0
                        else -> 1.0
                    })
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(directionSpinner)
            addView(button("保存自定义") { saveCustomPreset() })
        })
        parameterPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        SLIDERS.forEach { definition ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(definition.label, 14f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                sliderValues[definition.key] = text("", 14f)
                addView(sliderValues[definition.key])
            }
            parameterPanel.addView(row)
            val slider = SeekBar(this).apply {
                max = 1000
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && !updatingControls) {
                            val value = definition.min + (definition.max - definition.min) * progress / 1000.0
                            updateParameter(definition.key, clampSlider(definition.key, value))
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            sliderControls[definition.key] = slider
            parameterPanel.addView(slider)
        }
        root.addView(parameterPanel)
        root.addView(section("音频变换"))
        root.addView(check("整轨倒放") {
            reverse = it
            service?.player?.reverse = it
        })
        val speed = addSlider("播放倍速", 0.5f, 2.0f, 1.0f) {
            transform = transform.copy(speed = it.toDouble())
            applyTransform()
        }
        root.addView(speed.first)
        root.addView(speed.second)
        root.addView(check("磁带模式（音高随倍速变化）") {
            transform = transform.copy(tape = it)
            applyTransform()
        })
        val pitch = addSlider("变声（半音）", -12f, 12f, 0f) {
            transform = transform.copy(semi = it.roundToInt().toDouble())
            applyTransform()
        }
        root.addView(pitch.first)
        root.addView(pitch.second)
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                jiangyi.spatial8d.core.PITCH_PRESETS.map { it.name })
            setSelection(12)
            onItemSelectedListener = selected { index ->
                transform = transform.copy(semi = jiangyi.spatial8d.core.PITCH_PRESETS[index].semi)
                applyTransform()
            }
        })
        root.addView(LinearLayout(this).apply {
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { value ->
                addView(button("${value}×") {
                    transform = transform.copy(speed = value); applyTransform()
                })
            }
        })
        durationHint = text("新时长 --:--", 13f)
        root.addView(durationHint)
        root.addView(section("空间曲线"))
        curve = CurveView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 280)
            onSlide = { key, value -> updateParameter(key, value) }
        }
        root.addView(curve)

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("添加音频") { openAudio.launch(arrayOf("audio/*")) })
            addView(button("扫描音乐") {
                if (hasAudioPermission()) scanMusic() else requestAudio.launch(audioPermission())
            })
            addView(button("播放列表") { showPlaylist() })
        })
        songTitle = text("未加载音频", 17f, bold = true)
        root.addView(songTitle)
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) service?.player?.seek(service?.player?.duration?.times(progress / 1000.0) ?: 0.0)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        root.addView(seek)
        position = text("00:00 / 00:00", 14f)
        root.addView(position)
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(button("上一首") { previous() })
            playButton = button("播放") { togglePlayback() }
            addView(playButton)
            addView(button("下一首") { next() })
            addView(button(modeText()) {
                val modes = PlayMode.entries
                val nextMode = modes[(modes.indexOf(currentMode()) + 1) % modes.size]
                preferences.edit().putString("mode", nextMode.name).apply()
                (it as Button).text = modeText()
            })
        })
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
        refreshTheme()
        refreshAll()
    }

    private fun refreshAll() {
        updatingControls = true
        presetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            (SPACE_PRESETS + customPresets).map { it.name })
        val presetIndex = (SPACE_PRESETS + customPresets).indexOfFirst { it == preset }
        if (presetIndex >= 0) presetSpinner.setSelection(presetIndex)
        presetSpinner.onItemSelectedListener = selected { index ->
            if (!updatingControls) {
                preset = (SPACE_PRESETS + customPresets)[index]
                applyPreset()
            }
        }
        directionSpinner.setSelection(when (preset.direction) {
            -1.0 -> 1
            0.0 -> 2
            else -> 0
        })
        sliderControls.forEach { (key, slider) ->
            val value = parameter(key)
            val definition = SLIDERS.first { it.key == key }
            slider.progress = ((value - definition.min) / (definition.max - definition.min) * 1000).roundToInt()
            sliderValues[key]?.text = formatValue(key, value)
        }
        visualization.preset = preset
        visualization.invalidate()
        curve.preset = preset
        curve.invalidate()
        updatingControls = false
        refreshPlayback()
    }

    private fun refreshTheme() {
        val bg = if (dark) 0xff10151d.toInt() else 0xfff4f6f8.toInt()
        val fg = if (dark) 0xffe7edf5.toInt() else 0xff182230.toInt()
        root.setBackgroundColor(bg)
        tint(root, fg)
        visualization.dark = dark
        curve.dark = dark
        preferences.edit().putBoolean("dark", dark).apply()
    }

    private fun tint(view: View, color: Int) {
        if (view is TextView) view.setTextColor(color)
        if (view is ViewGroup) for (i in 0 until view.childCount) tint(view.getChildAt(i), color)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
    }

    private fun refreshPlayback() {
        val player = service?.player ?: return
        playButton.text = if (player.isPlaying) "暂停" else "播放"
        songTitle.text = songs.getOrNull(currentIndex)?.name ?: "未加载音频"
        position.text = "${time(player.position)} / ${time(player.duration)}"
        if (!seek.isPressed) seek.progress = if (player.duration > 0)
            (player.position / player.duration * 1000).toInt() else 0
        durationHint.text = "新时长 ${time(player.duration / transform.speed)}"
    }

    private fun applyPreset() {
        service?.player?.preset = preset
        refreshAll()
    }

    private fun applyTransform() {
        service?.player?.transform = transform
        refreshPlayback()
    }

    private fun updateParameter(key: String, value: Double) {
        preset = when (key) {
            "period" -> preset.copy(period = value)
            "elev" -> preset.copy(elev = value)
            "depth" -> preset.copy(depth = value)
            "phase" -> preset.copy(phase = value)
            "room" -> preset.copy(room = value)
            "wet" -> preset.copy(wet = value)
            "rt60" -> preset.copy(rt60 = value)
            "direction" -> preset.copy(direction = value)
            "width" -> preset.copy(width = value)
            else -> return
        }
        service?.player?.preset = preset
        refreshAll()
    }

    private fun parameter(key: String): Double = when (key) {
        "period" -> preset.period
        "elev" -> preset.elev
        "depth" -> preset.depth
        "phase" -> preset.phase
        "room" -> preset.room
        "wet" -> preset.wet
        "rt60" -> preset.rt60
        "direction" -> preset.direction
        "width" -> preset.width
        else -> 0.0
    }

    private fun addAndPlay(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment ?: "音频"
        val item = MusicItem(System.nanoTime(), name, uri.toString())
        musicStore.remember(item)
        songs = musicStore.library().toMutableList()
        currentIndex = songs.indexOfFirst { it.uri == item.uri }
        load(item)
    }

    override fun scanMusic() {
        if (loading) return
        status.text = "正在扫描音乐库…"
        loading = true
        Thread {
            val result = musicStore.library()
            musicStore.saveLibrary(result)
            main.post {
                loading = false
                songs = result.toMutableList()
                queue = musicStore.view(songs)
                status.text = "音乐库 ${songs.size} 首；可通过播放列表选择"
            }
        }.start()
    }

    private fun load(item: MusicItem) {
        load(item, true)
    }

    private fun load(item: MusicItem, autoplay: Boolean) {
        val generation = ++loadGeneration
        if (autoplay) autoplayRequests++ else autoplayRequests = 0
        val resumePosition = if (autoplay) 0.0 else preferences.getInt("lastPosition", 0) / 1000.0
        service?.player?.pause()
        currentIndex = songs.indexOfFirst { it.id == item.id && it.uri == item.uri }
        currentUri = item.uri
        songTitle.text = item.name
        status.text = "正在解码 ${item.name}…"
        Thread {
            try {
                val decoded = AudioDecoder(this).decode(Uri.parse(item.uri)) { percent ->
                    main.post { if (generation == loadGeneration) status.text = "解码 $percent% · ${item.name}" }
                }
                val mono = decoded.mono()
                main.post {
                    if (generation != loadGeneration) return@post
                    service?.player?.prepare(mono, decoded.sampleRate, decoded)
                    service?.player?.preset = preset
                    service?.player?.transform = transform
                    service?.player?.reverse = reverse
                    service?.player?.seek(resumePosition)
                    status.text = "已加载 ${item.name} · ${decoded.sampleRate} Hz"
                    preferences.edit().putString("lastUri", item.uri).apply()
                    if (autoplayRequests > 0) {
                        getSystemService(AudioManager::class.java).requestAudioFocus(audioFocus)
                        service?.player?.play()
                    }
                    refreshPlayback()
                }
            } catch (e: Exception) {
                main.post { if (generation == loadGeneration) status.text = e.message ?: "加载失败" }
            }
        }.start()
    }

    private fun restoreLast() {
        val uri = preferences.getString("lastUri", null) ?: return
        songs.firstOrNull { it.uri == uri }?.let { load(it, false) }
    }

    override fun songs(): List<MusicItem> = songs

    override fun onPause() {
        service?.player?.let { preferences.edit().putInt("lastPosition", (it.position * 1000).toInt()).apply() }
        super.onPause()
    }

    override fun onStop() {
        service?.player?.let { preferences.edit().putInt("lastPosition", (it.position * 1000).toInt()).apply() }
        super.onStop()
    }

    private fun togglePlayback() {
        val player = service?.player ?: return
        if (player.duration == 0.0) {
            songs.firstOrNull { it.uri == currentUri }?.let { load(it) } ?: showPlaylist()
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            getSystemService(AudioManager::class.java).requestAudioFocus(audioFocus)
            if (!player.play()) {
                songs.getOrNull(currentIndex)?.let { load(it) }
            }
        }
        refreshPlayback()
    }

    private fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (auto && currentMode() == PlayMode.LOOP_ONE) {
            service?.player?.seek(0.0)
            service?.player?.play()
            return
        }
        val current = queue.indexOfFirst { it.uri == currentUri }
        val nextIndex = when (currentMode()) {
            PlayMode.SHUFFLE -> if (queue.size > 1) (queue.indices.filter { it != current }).random() else 0
            else -> (current + 1 + queue.size) % queue.size
        }
        load(queue[nextIndex])
    }

    private fun previous() {
        if (queue.isEmpty()) return
        val current = queue.indexOfFirst { it.uri == currentUri }
        val target = if (current < 0) queue.lastIndex else (current - 1 + queue.size) % queue.size
        load(queue[target])
    }

    private fun showPlaylist() {
        if (songs.isEmpty()) {
            toast("请先添加或扫描音频")
            return
        }
        PlaylistDialog(musicStore, dark, { item -> load(item) }, { queue = it }, songs)
            .show(supportFragmentManager, "playlist")
    }

    private fun saveCustomPreset() {
        val input = android.widget.EditText(this).apply { hint = "自定义风格名称" }
        AlertDialog.Builder(this)
            .setTitle("保存当前空间参数")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val raw = input.text.toString().ifBlank { "自定义 ${customPresets.size + 1}" }
                var name = if (" · " in raw) raw else "标准绕头 · $raw"
                val base = name
                var suffix = 2
                while (customPresets.any { it.name == name }) name = "$base ${suffix++}"
                customPresets.add(preset.copy(name = name, note = "用户保存的自定义空间风格"))
                presetStore.save(customPresets)
                refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportWav(uri: Uri, onComplete: () -> Unit = {}) {
        if (exporting) { toast("已有导出任务，请等待完成"); return }
        val player = service?.player ?: return
        exporting = true
        val stream = contentResolver.openOutputStream(uri) ?: run {
            exporting = false
            toast("无法创建导出文件")
            return
        }
        status.text = "正在导出 WAV…"
        var published = false
        player.export(stream, mp3 = false,
            onProgress = { percent -> main.post { status.text = "导出 $percent%" } },
            onDone = { ok, message ->
                exporting = false
                if (ok) { onComplete(); published = true }
                if (!published) discardPending(uri)
                status.text = message; toast(message)
            })
    }

    private fun chooseExport() {
        val name = songs.firstOrNull { it.uri == currentUri }?.name?.substringBeforeLast('.') ?: "8d_export"
        if (service?.player?.duration == 0.0) { toast("请先加载音频"); return }
        AlertDialog.Builder(this)
            .setTitle("导出音频")
            .setItems(arrayOf("WAV · 自选位置", "MP3 · 自选位置", "WAV · 公共 Music", "MP3 · 公共 Music")) { _, index ->
                if (index >= 2) exportPublic(name, index == 3)
                else if (index == 0) saveWav.launch("$name-8D.wav") else saveMp3.launch("$name-8D.mp3")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportMp3(uri: Uri, onComplete: () -> Unit = {}) {
        if (exporting) { toast("已有导出任务，请等待完成"); return }
        val player = service?.player ?: return
        exporting = true
        val wav = java.io.File(cacheDir, "8d-export.wav")
        val mp3 = java.io.File(cacheDir, "8d-export.mp3")
        status.text = "正在导出 MP3…"
        player.export(wav.outputStream(), mp3 = true,
            wavPath = wav.absolutePath, mp3Path = mp3.absolutePath,
            onProgress = { percent -> main.post { status.text = "导出 $percent%" } },
            onDone = { ok, message ->
                if (ok) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            mp3.inputStream().use { input -> input.copyTo(output) }
                        }
                        status.text = message
                        onComplete()
                    } catch (e: Exception) {
                        status.text = "MP3 保存失败: ${e.message}"
                        discardPending(uri)
                    }
                } else { status.text = message; discardPending(uri) }
                exporting = false
                toast(status.text.toString())
            })
    }

    private fun exportPublic(name: String, mp3: Boolean) {
        if (exporting) { toast("已有导出任务，请等待完成"); return }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name-8D.${if (mp3) "mp3" else "wav"}")
            put(MediaStore.Audio.Media.MIME_TYPE, if (mp3) "audio/mpeg" else "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/8D")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return
        val done = {
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            Unit
        }
        if (mp3) exportMp3(uri, done) else exportWav(uri, done)
    }

    private fun discardPending(uri: Uri) {
        if ("media" == uri.authority) contentResolver.delete(uri, "${MediaStore.Audio.Media.IS_PENDING}=1", null)
    }

    private fun section(label: String) = text(label, 16f, bold = true).apply {
        setPadding(0, 28, 0, 8)
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, 8, 0, 8)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, action: (View) -> Unit) = Button(this).apply {
        text = label
        setOnClickListener(action)
    }

    private fun check(label: String, action: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        setOnCheckedChangeListener { _, checked -> action(checked) }
    }

    private fun addSlider(
        label: String,
        min: Float,
        max: Float,
        initial: Float,
        action: (Float) -> Unit
    ): Pair<TextView, SeekBar> {
        val title = text("$label · ${String.format(Locale.US, "%.2f", initial)}", 14f)
        val slider = SeekBar(this).apply {
            this.max = 1000
            progress = ((initial - min) / (max - min) * 1000).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val value = min + (max - min) * progress / 1000f
                        title.text = "$label · ${String.format(Locale.US, "%.2f", value)}"
                        action(value)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        return title to slider
    }

    private fun selected(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) = action(position)
        override fun onNothingSelected(parent: AdapterView<*>) {}
    }

    private fun hasAudioPermission() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun currentMode() = try {
        PlayMode.valueOf(preferences.getString("mode", PlayMode.LOOP_LIST.name)!!)
    } catch (_: Exception) { PlayMode.LOOP_LIST }

    private fun modeText() = when (currentMode()) {
        PlayMode.LOOP_LIST -> "列表循环"
        PlayMode.LOOP_ONE -> "单曲循环"
        PlayMode.SHUFFLE -> "随机播放"
    }

    private fun time(seconds: Double): String {
        val value = seconds.toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", value / 60, value % 60)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private val ticker = object : Runnable {
        override fun run() {
            refreshPlayback()
            service?.player?.let {
                visualization.position = it.position
                curve.position = it.position
            }
            main.postDelayed(this, 100)
        }
    }
}
