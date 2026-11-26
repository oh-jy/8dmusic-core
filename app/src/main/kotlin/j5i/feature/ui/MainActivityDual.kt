package j5i.spatial8d.feature.ui
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*
import j5i.spatial8d.MainActivity
import android.text.InputType
import android.widget.CheckBox
import android.widget.LinearLayout
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.isOnlineAudio
import j5i.spatial8d.ui.ThemeUi

internal fun MainActivity.dualText(key: String) = t("dual.$key")

internal fun MainActivity.showDualMode() {
    val player = service?.player ?: return
    val available = songs.filterNot { it.isOnlineAudio() }
    if (available.isEmpty()) { toast(dualText("empty")); return }
    val active = service?.dualTracks
    var left: MusicItem? = (if (player.dualSwapped) active?.second else active?.first)
        ?: currentItem?.takeIf { !it.isOnlineAudio() && it in available }
    var right: MusicItem? = if (player.dualSwapped) active?.first else active?.second
    lateinit var dialog: android.app.Dialog
    var updateTracks: (() -> Unit)? = null
    dialog = ThemeUi.showSheet(this, palette, dualText("title")) {
        addView(ThemeUi.text(this@showDualMode, dualText("hint"), 12f, palette, role = ThemeUi.ROLE_SUB))
        fun trackButton(isLeft: Boolean) = ThemeUi.button(this@showDualMode, palette, "", ThemeUi.ROLE_CONTROL).apply {
            setOnClickListener {
                pickOption(dualText(if (isLeft) "left" else "right"), available.map { it.name },
                    available.indexOf(if (isLeft) left else right)) { index ->
                    if (isLeft) left = available[index] else right = available[index]
                    updateTracks?.invoke()
                }
            }
        }
        val leftButton = trackButton(true)
        val rightButton = trackButton(false)
        updateTracks = {
            leftButton.text = "${dualText("left")} · ${left?.name ?: dualText("choose")}"
            rightButton.text = "${dualText("right")} · ${right?.name ?: dualText("choose")}"
        }
        updateTracks?.invoke()
        addView(leftButton, LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showDualMode, 44f)))
        addView(rightButton, LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showDualMode, 44f)))
        if (active != null && player.isDual) addView(ThemeUi.button(this@showDualMode, palette, dualText("swap"), ThemeUi.ROLE_CONTROL) {
            player.swapDual()
            refreshDualLyrics()
            refreshPlayback()
        })
        val random = CheckBox(this@showDualMode).apply {
            text = dualText("random")
            setTextColor(palette.text)
            isChecked = player.dualRandomMaxSeconds > 0
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
        }
        addView(random)
        val row = ThemeUi.row(this@showDualMode)
        val min = ThemeUi.input(this@showDualMode, palette, dualText("min")).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(player.dualRandomMinSeconds.coerceIn(1, 60).toString())
        }
        val max = ThemeUi.input(this@showDualMode, palette, dualText("max")).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(player.dualRandomMaxSeconds.takeIf { it > 0 }?.coerceIn(1, 60)?.toString() ?: "10")
        }
        row.addView(min, LinearLayout.LayoutParams(0, ThemeUi.dp(this@showDualMode, 42f), 1f))
        row.addView(max, LinearLayout.LayoutParams(0, ThemeUi.dp(this@showDualMode, 42f), 1f).apply {
            leftMargin = ThemeUi.dp(this@showDualMode, 8f)
        })
        addView(row)
        addView(ThemeUi.button(this@showDualMode, palette, dualText("start"), ThemeUi.ROLE_ACCENT_FILL) {
            val leftItem = left
            val rightItem = right
            if (leftItem == null || rightItem == null) { toast(dualText("selected")); return@button }
            val minSeconds = min.text.toString().toIntOrNull()
            val maxSeconds = max.text.toString().toIntOrNull()
            if (random.isChecked && (minSeconds == null || maxSeconds == null || minSeconds !in 1..60 || maxSeconds !in 1..60 || minSeconds > maxSeconds)) {
                toast(dualText("invalid")); return@button
            }
            if (recordingKind.isNotEmpty()) { toast(t("plugin.stop_recording")); return@button }
            player.dualRandomMinSeconds = minSeconds ?: 1
            player.dualRandomMaxSeconds = if (random.isChecked) maxSeconds!! else 0
            ++loadGeneration
            ++radioGeneration
            radioProbes.forEach { it.stop() }
            radioProbes = emptyList()
            service?.radio?.stop()
            isLive = false
            offlineAsrEnabled = false
            asrTranscript = ""
            if (offlineAsrLazy.isInitialized()) offlineAsr.stop()
            stationIndex = -1
            currentStationId = ""
            radioSources = emptyList()
            radioSourceIndex = -1
            currentItem = leftItem
            currentUri = leftItem.uri
            playbackStatus = t("status.preparing")
            service?.loadDual(leftItem, rightItem)
            dialog.dismiss()
            refreshPlayback()
        })
        if (active != null) addView(ThemeUi.button(this@showDualMode, palette, dualText("stop"), ThemeUi.ROLE_CONTROL) {
            service?.stopLocal()
            dialog.dismiss()
            refreshPlayback()
            load(active.first, autoplay = false)
        })
    }
    if (active != null) {
        var displayedSwap = player.dualSwapped
        player.onDualSwapChanged = { swapped ->
            if (swapped != displayedSwap) {
                val previousLeft = left
                left = right
                right = previousLeft
                displayedSwap = swapped
                updateTracks?.invoke()
            }
        }
        dialog.setOnDismissListener { player.onDualSwapChanged = null }
    }
    dialog.show()
}
