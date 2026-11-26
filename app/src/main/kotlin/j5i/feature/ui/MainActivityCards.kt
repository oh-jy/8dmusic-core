package j5i.spatial8d.feature.ui
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*
import j5i.spatial8d.MainActivity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import j5i.spatial8d.visual.PerfTrace
import j5i.spatial8d.ui.LyricsView
import j5i.spatial8d.ui.VisualizerView
import j5i.spatial8d.ui.VisualCreative
import j5i.spatial8d.core.SLIDERS
import j5i.spatial8d.core.SPACE_PRESETS
import j5i.spatial8d.core.SELECTABLE_SPACE_PRESETS
import j5i.spatial8d.core.PitchPreset
import j5i.spatial8d.core.PITCH_PRESETS
import j5i.spatial8d.core.clampSlider
import j5i.spatial8d.core.formatValue
import j5i.spatial8d.ui.ThemeUi
import j5i.spatial8d.ui.AppLanguage
import j5i.spatial8d.ui.exclusiveVisualModes
import j5i.spatial8d.ui.visualModeGroups
import j5i.spatial8d.ui.SPATIAL_CURVE_MODE
import j5i.spatial8d.ui.visualizerPanel
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.pow

internal fun MainActivity.card(block: LinearLayout.() -> Unit): LinearLayout = ThemeUi.card(this, palette, block)

internal fun sliderTickCenter(width: Int, leftPadding: Int, rightPadding: Int, thumbWidth: Int, thumbOffset: Int, fraction: Float): Float =
    leftPadding - thumbOffset + thumbWidth / 2f +
        (width - leftPadding - rightPadding - thumbWidth + 2 * thumbOffset) * fraction

internal val speedPresets = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
internal const val SPEED_SLIDER_MAX = 100

internal fun speedForProgress(progress: Int): Double {
    val position = progress.coerceIn(0, SPEED_SLIDER_MAX) * (speedPresets.lastIndex.toDouble() / SPEED_SLIDER_MAX)
    val index = position.toInt().coerceAtMost(speedPresets.lastIndex - 1)
    val speed = speedPresets[index] + (speedPresets[index + 1] - speedPresets[index]) * (position - index)
    return (speed * 20).roundToInt() / 20.0
}

internal fun progressForSpeed(speed: Double): Int {
    val value = speed.coerceIn(speedPresets.first(), speedPresets.last())
    val index = speedPresets.indexOfLast { it <= value }.coerceAtMost(speedPresets.lastIndex - 1)
    val fraction = (value - speedPresets[index]) / (speedPresets[index + 1] - speedPresets[index])
    return ((index + fraction) * SPEED_SLIDER_MAX / speedPresets.lastIndex).roundToInt()
}

internal fun MainActivity.sliderTicks(slider: SeekBar, fractions: List<Float>, labels: List<String>, onClick: (Int) -> Unit): Pair<FrameLayout, List<Button>> {
    val row = FrameLayout(this)
    val buttons = labels.mapIndexed { index, label ->
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(View(this@sliderTicks).apply {
                setBackgroundColor(palette.faint)
                layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@sliderTicks, 2f), ThemeUi.dp(this@sliderTicks, 3f))
            })
        }
        val button = ThemeUi.button(this, palette, label, role = ThemeUi.ROLE_TEXT) { onClick(index) }.apply {
            textSize = 9f
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
        }
        cell.addView(button)
        row.addView(cell, FrameLayout.LayoutParams(ThemeUi.dp(this, 36f), ThemeUi.dp(this, 28f), Gravity.LEFT))
        button
    }
    val positionTicks = {
        buttons.forEachIndexed { index, button ->
            val cell = button.parent as View
            val fraction = if (slider.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f - fractions[index] else fractions[index]
            val center = sliderTickCenter(slider.width, slider.paddingLeft, slider.paddingRight,
                slider.thumb.intrinsicWidth, slider.thumbOffset, fraction)
            cell.translationX = center - cell.width / 2f
        }
    }
    row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionTicks() }
    slider.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionTicks() }
    row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this, 28f)).apply {
        topMargin = -ThemeUi.dp(this@sliderTicks, 8f)
    }
    return row to buttons
}

internal fun MainActivity.spaceSettingsCard(): LinearLayout {
        val card = card {
            val header = FrameLayout(this@spaceSettingsCard).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@spaceSettingsCard, 40f))
            }
            presetPicker = ThemeUi.text(this@spaceSettingsCard, preset.name, 12f, palette, role = ThemeUi.ROLE_CONTROL).apply {
                ThemeUi.hideOverflow(this)
                gravity = Gravity.CENTER
                setPadding(ThemeUi.dp(this@spaceSettingsCard, 24f), 0, ThemeUi.dp(this@spaceSettingsCard, 24f), 0)
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                setOnClickListener {
                    // 快速选择只调整展示顺序，保持内置和自定义预设原值不变。
                    val pinned = listOfNotNull(SELECTABLE_SPACE_PRESETS.firstOrNull { it.raw },
                        SELECTABLE_SPACE_PRESETS.firstOrNull { !it.raw })
                    val all = pinned + (SELECTABLE_SPACE_PRESETS + customPresets).filter { it !in pinned }
                        .sortedWith(compareBy({ it.room }, { it.name }))
                    pickOption(t("space.choose"), all.map { item ->
                        val index = SPACE_PRESETS.indexOfFirst { it.name == item.name }
                        val name = if (index >= 0) AppLanguage.preset(this@spaceSettingsCard, index) else displayPresetName(item.name)
                        val path = if (item.raw) "" else " · " + t("trajectory." + listOf("orbit", "figure8", "vertical", "spiral", "wander")[item.trajectory.coerceIn(0, 4)])
                        "$name$path · ${item.room.toInt()}${t("unit.meters")}" },
                        all.indexOf(preset), twoColumns = true) {
                        preset = all[it]; applyPreset()
                    }
                }
            }
            header.addView(presetPicker)
            header.addView(ThemeUi.text(this@spaceSettingsCard, "▾", 10f, palette, role = ThemeUi.ROLE_SUB).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ThemeUi.dp(this@spaceSettingsCard, 32f), -1, Gravity.END))
            addView(header)
            presetNote = ThemeUi.text(this@spaceSettingsCard, preset.note, 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                setPadding(0, ThemeUi.dp(this@spaceSettingsCard, 5f), 0, 0)
            }
            parameterPanel = LinearLayout(this@spaceSettingsCard).apply { orientation = LinearLayout.VERTICAL }
            addView(parameterPanel)
            parameterPanel.addView(presetNote)
            parameterPanel.addView(ThemeUi.divider(this@spaceSettingsCard, palette).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@spaceSettingsCard, 1f)).apply {
                    topMargin = ThemeUi.dp(this@spaceSettingsCard, 9f); bottomMargin = ThemeUi.dp(this@spaceSettingsCard, 9f)
                }
            })
            parameterPanel.addView(ThemeUi.row(this@spaceSettingsCard).apply {
                addView(ThemeUi.text(this@spaceSettingsCard, t("direction"), 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(dirSegmented(), LinearLayout.LayoutParams(0, ThemeUi.dp(this@spaceSettingsCard, 32f), 2.2f))
            })
            parameterPanel.addView(ThemeUi.text(this@spaceSettingsCard, t("direction.hint"), 10f, palette, role = ThemeUi.ROLE_FAINT))
            parameterPanel.addView(ThemeUi.row(this@spaceSettingsCard).apply {
                addView(ThemeUi.text(this@spaceSettingsCard, t("trajectory"), 12f, palette, bold = true))
                addView(ThemeUi.text(this@spaceSettingsCard, t("trajectory.hint"), 10f, palette, role = ThemeUi.ROLE_FAINT))
            }.apply {
                setPadding(0, ThemeUi.dp(this@spaceSettingsCard, 10f), 0, ThemeUi.dp(this@spaceSettingsCard, 4f))
            })
            parameterPanel.addView(ThemeUi.button(this@spaceSettingsCard, palette, t("trajectory.orbit")).apply {
                tag = "trajectoryPicker"
                setOnClickListener {
                    if (preset.raw) {
                        android.widget.Toast.makeText(this@spaceSettingsCard, t("trajectory.raw"), android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val keys = listOf("orbit", "figure8", "vertical", "spiral", "wander")
                        pickOption(t("trajectory"), keys.map { t("trajectory.$it") }, preset.trajectory) { index ->
                            updateParameter("trajectory", index.toDouble())
                        }
                    }
                }
            })
            parameterPanel.addView(sliderGroup(SLIDERS.filter { it.key in setOf("period", "elev", "sourceDistance", "depth", "phase") }))
            parameterPanel.addView(ThemeUi.divider(this@spaceSettingsCard, palette).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@spaceSettingsCard, 1f)).apply {
                    topMargin = ThemeUi.dp(this@spaceSettingsCard, 12f); bottomMargin = ThemeUi.dp(this@spaceSettingsCard, 9f)
                }
            })
            parameterPanel.addView(ThemeUi.row(this@spaceSettingsCard).apply {
                addView(ThemeUi.text(this@spaceSettingsCard, t("room.acoustics"), 12f, palette, bold = true))
                addView(ThemeUi.text(this@spaceSettingsCard, t("room.hint"), 10f, palette, role = ThemeUi.ROLE_FAINT))
            }.apply {
                setPadding(0, ThemeUi.dp(this@spaceSettingsCard, 4f), 0, ThemeUi.dp(this@spaceSettingsCard, 4f))
            })
            parameterPanel.addView(sliderGroup(SLIDERS.filter { it.key in setOf("room", "wet", "rt60", "width") }))
            val saveRow = ThemeUi.row(this@spaceSettingsCard).apply { tag = "savePresetRow" }
            val saveInput = EditText(this@spaceSettingsCard).apply {
                hint = t("ui.preset.custom_name")
                setTextColor(palette.text)
                setHintTextColor(palette.faint)
                setSingleLine()
                setPadding(ThemeUi.dp(this@spaceSettingsCard, 10f), 0, ThemeUi.dp(this@spaceSettingsCard, 10f), 0)
                background = ThemeUi.bgDrawable(this@spaceSettingsCard, palette.control, 8f)
                hint = t("preset.name.hint")
                textSize = 11f
            }
            saveRow.addView(saveInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@spaceSettingsCard, 36f), 1f).apply {
                    rightMargin = ThemeUi.dp(this@spaceSettingsCard, 8f)
                }
            })
            saveRow.addView(ThemeUi.button(this@spaceSettingsCard, palette, t("preset.save"), role = ThemeUi.ROLE_ACCENT_FILL) {
                saveCustomPreset(saveInput.text.toString())
            })
            parameterPanel.addView(saveRow)
            parameterPanel.visibility = View.GONE
        }
        card.setPadding(ThemeUi.dp(this, 6f), ThemeUi.dp(this, 4f), ThemeUi.dp(this, 6f), ThemeUi.dp(this, 4f))
        (card.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        return card
    }

internal fun MainActivity.sliderGroup(definitions: List<j5i.spatial8d.core.SliderDef>): LinearLayout {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        definitions.forEach { definition ->
            val row = ThemeUi.row(this)
            row.addView(ThemeUi.text(this, t("slider.${definition.key}"), 12f, palette).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val initialValue = parameter(definition.key)
            sliderValues[definition.key] = ThemeUi.text(this, "", 12f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                text = localizedParameterValue(definition.key, initialValue)
            }
            row.addView(sliderValues[definition.key])
            group.addView(row)
            val slider = SeekBar(this).apply {
                ThemeUi.configureSeekBar(this, palette)
                contentDescription = t("slider.${definition.key}")
                max = ((definition.max - definition.min) * 10.0.pow(definition.digits)).roundToInt()
                progress = ((initialValue - definition.min) * 10.0.pow(definition.digits)).roundToInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && !updatingControls) {
                            val value = definition.min + progress / 10.0.pow(definition.digits)
                            updateParameter(definition.key, clampSlider(definition.key, value))
                            sliderValues[definition.key]?.text = localizedParameterValue(definition.key, value)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) { draggingControl = true }
                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        draggingControl = false
                        refreshPresetSummary()
                    }
                })
            }
            sliderControls[definition.key] = slider
            group.addView(slider)
        }
        return group
    }

internal fun MainActivity.dirSegmented(): LinearLayout {
        val row = ThemeUi.row(this)
        val options = listOf(t("direction.cw") to 1.0, t("direction.ccw") to -1.0, t("direction.random") to 0.0)
        options.forEach { (label, value) ->
            val btn = ThemeUi.button(this, palette, label).apply {
                layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@dirSegmented, 32f), 1f)
                textSize = 11f
                maxLines = 1
                setPadding(ThemeUi.dp(this@dirSegmented, 12f), 0, ThemeUi.dp(this@dirSegmented, 12f), 0)
                setOnClickListener {
                    updateParameter("direction", value)
                    refreshAll()
                }
            }
            row.addView(btn)
        }
        dirButtons = (0 until row.childCount).map { row.getChildAt(it) as Button }
        return row
    }

internal fun MainActivity.transformCard(): LinearLayout {
        val card = card {
            val header = FrameLayout(this@transformCard).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@transformCard, 40f))
            }
            pitchPicker = ThemeUi.text(this@transformCard, t("original"), 12f, palette, role = ThemeUi.ROLE_CONTROL).apply {
                gravity = Gravity.CENTER
                ThemeUi.hideOverflow(this)
                setPadding(ThemeUi.dp(this@transformCard, 24f), 0, ThemeUi.dp(this@transformCard, 24f), 0)
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                setOnClickListener {
                    val options = interleavedPitchOptions(PITCH_PRESETS)
                    pickOption(t("pitch.choose"), options.map { formatPitchLabel(it.semi) },
                        options.indexOfFirst { it.semi == transform.semi }, twoColumns = true) {
                        transform = transform.copy(semi = options[it].semi); applyTransform(); refreshAll()
                    }
                }
            }
            header.addView(pitchPicker)
            header.addView(ThemeUi.text(this@transformCard, "▾", 10f, palette, role = ThemeUi.ROLE_SUB).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ThemeUi.dp(this@transformCard, 32f), -1, Gravity.END))
            addView(header)
            transformBody = LinearLayout(this@transformCard).apply { orientation = LinearLayout.VERTICAL }
            transformBody.addView(ThemeUi.divider(this@transformCard, palette).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@transformCard, 1f)).apply {
                    topMargin = ThemeUi.dp(this@transformCard, 8f); bottomMargin = ThemeUi.dp(this@transformCard, 8f)
                }
            })
            val pitchRow = ThemeUi.row(this@transformCard)
            pitchRow.addView(ThemeUi.text(this@transformCard, t("pitch"), 12f, palette).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            pitchLabel = ThemeUi.text(this@transformCard, t("original"), 12f, palette, role = ThemeUi.ROLE_ACCENT)
            pitchRow.addView(pitchLabel)
            transformBody.addView(pitchRow)
            pitchSlider = SeekBar(this@transformCard).apply {
                ThemeUi.configureSeekBar(this, palette)
                contentDescription = t("pitch")
                max = 24
                progress = (transform.semi.roundToInt() + 12).coerceIn(0, 24)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val semi = progress - 12
                            if (semi != transform.semi.toInt()) {
                                transform = transform.copy(semi = semi.toDouble())
                                applyTransform(syncControls = false)
                                pitchLabel.text = if (semi == 0) t("original") else "$semi ${t("semitone")}"
                            }
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) { draggingControl = true }
                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        draggingControl = false
                        if (isViewReady(this@transformCard::pitchPicker)) pitchPicker.text = pitchPickerLabel()
                        refreshHomePickers()
                    }
                })
            }
            transformBody.addView(pitchSlider, LinearLayout.LayoutParams(-1, ThemeUi.dp(this@transformCard, 48f)))
            val pitchValues = listOf(-12.0, -8.0, -4.0, 0.0, 4.0, 8.0, 12.0)
            val (tickRow, pitchTickButtons) = sliderTicks(pitchSlider, pitchValues.map { ((it + 12.0) / 24.0).toFloat() }, pitchValues.map { semiName(it) }) { index ->
                transform = transform.copy(semi = pitchValues[index]); applyTransform(); refreshAll()
            }
            transformBody.addView(tickRow)
            pitchTicks = pitchTickButtons
            transformBody.addView(switchRow(t("reverse"), t("reverse.hint")) { reverse = it; service?.player?.reverse = it; service?.controlsReverse = reverse }.also {
                reverseSwitch = it.second
            }.first.apply { tag = "localOnly" })
            speedLabel = ThemeUi.text(this@transformCard, "1.00×", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            transformBody.addView(ThemeUi.row(this@transformCard).apply {
                tag = "localOnly"
                addView(ThemeUi.text(this@transformCard, t("speed"), 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(speedLabel)
            })
            speedSlider = SeekBar(this@transformCard).apply {
                ThemeUi.configureSeekBar(this, palette)
                contentDescription = t("speed")
                max = SPEED_SLIDER_MAX
                progress = progressForSpeed(transform.speed)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val speed = speedForProgress(progress)
                            transform = transform.copy(speed = speed)
                            applyTransform(syncControls = false)
                            speedLabel.text = String.format(Locale.US, "%.2f×", speed)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) { draggingControl = true }
                    override fun onStopTrackingTouch(seekBar: SeekBar) { draggingControl = false }
                })
            }
            speedSlider.tag = "localOnly"
            transformBody.addView(speedSlider, LinearLayout.LayoutParams(-1, ThemeUi.dp(this@transformCard, 48f)))
            val (chips, speedButtons) = sliderTicks(speedSlider, speedPresets.indices.map { it.toFloat() / speedPresets.lastIndex }, speedPresets.map { "${formatSpeedShort(it)}×" }) { index ->
                transform = transform.copy(speed = speedPresets[index])
                applyTransform()
                refreshAll()
            }
            speedChipButtons = speedButtons
            chips.tag = "localOnly"
            transformBody.addView(chips)
            denoiseLabel = ThemeUi.text(this@transformCard, "65%", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            transformBody.addView(ThemeUi.row(this@transformCard).apply {
                addView(ThemeUi.text(this@transformCard, t("denoise"), 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(denoiseLabel)
            })
            denoiseSlider = SeekBar(this@transformCard).apply {
                ThemeUi.configureSeekBar(this, palette)
                contentDescription = t("denoise")
                max = 100
                progress = transform.denoiseLevel
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && progress != transform.denoiseLevel) {
                            transform = transform.copy(denoise = progress)
                            applyTransform(syncControls = false)
                            denoiseLabel.text = if (progress == 0) t("off") else "$progress%"
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) { draggingControl = true }
                    override fun onStopTrackingTouch(seekBar: SeekBar) { draggingControl = false }
                })
            }
            transformBody.addView(denoiseSlider)
            clarityLabel = ThemeUi.text(this@transformCard, "50%", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            transformBody.addView(ThemeUi.row(this@transformCard).apply {
                tag = "localOnly"
                addView(ThemeUi.text(this@transformCard, t("sound.clarity"), 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(clarityLabel)
            })
            claritySlider = SeekBar(this@transformCard).apply {
                tag = "localOnly"
                ThemeUi.configureSeekBar(this, palette)
                contentDescription = t("sound.clarity")
                max = 100
                progress = transform.clarityLevel
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && progress != transform.clarityLevel) {
                            transform = transform.copy(clarity = progress)
                            preferences.edit().putInt("soundClarity", progress).apply()
                            applyTransform(syncControls = false)
                            clarityLabel.text = "$progress%"
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) { draggingControl = true }
                    override fun onStopTrackingTouch(seekBar: SeekBar) { draggingControl = false }
                })
            }
            transformBody.addView(claritySlider)
            transformBody.addView(switchRow(t("tape"), t("tape.hint")) { transform = transform.copy(tape = it); applyTransform() }.also {
                tapeSwitch = it.second
            }.first.apply { tag = "localOnly" })
            durationHint = ThemeUi.text(this@transformCard, "${t("new.duration")} --:--", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            transformBody.addView(ThemeUi.row(this@transformCard).apply {
                tag = "localOnly"
                background = ThemeUi.bgDrawable(this@transformCard, palette.surfaceAlt, 8f)
                setPadding(ThemeUi.dp(this@transformCard, 10f), ThemeUi.dp(this@transformCard, 6f),
                    ThemeUi.dp(this@transformCard, 10f), ThemeUi.dp(this@transformCard, 6f))
                addView(ThemeUi.text(this@transformCard, t("new.duration"), 10f, palette, role = ThemeUi.ROLE_FAINT).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(durationHint)
            })
            addView(transformBody)
            transformBody.visibility = View.GONE
        }
        card.setPadding(ThemeUi.dp(this, 6f), ThemeUi.dp(this, 4f), ThemeUi.dp(this, 6f), ThemeUi.dp(this, 4f))
        (card.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        transformCardRoot = card
        return card
    }

internal fun MainActivity.curveCard(): LinearLayout {
        val card = card {
            curveTabs = tabRow(listOf(t("visualizer"), t("lyrics")), if (curvePanel == 4) 1 else 0, compact = true) {
                if (it == 0) {
                    if (curvePanel != 4) showVisualEffectPicker() else selectCurvePanel(visualizerPanel(visualizerView.visualState), restoreMode = false)
                } else selectCurvePanel(4)
            }
            addView(curveTabs)
            // 用 FrameLayout 叠放曲线、歌词和可视化面板，同一时间只有一个 VISIBLE。
            // 之前 LinearLayout 中多个 height=-1 的子 view 会被平分/压缩，造成音频图谱/可视化上下留白。
            curveContent = FrameLayout(this@curveCard).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@curveCard, 162f))
            }
            lyricsView = LyricsView(this@curveCard).apply {
                dark = this@curveCard.dark
                onSearch = { query -> currentItem?.let { loadLyrics(it, query) } }
                onManual = { currentItem?.let { showLyricsActions(it, currentLyrics) } }
                onSeek = { target ->
                    val player = service?.player
                    if (!isLive && player?.isPrepared == true) {
                        val wasPlaying = player.isPlaying
                        android.util.Log.i("8DLyrics", "seek targetMs=$target position=${player.position} playing=$wasPlaying")
                        player.seek(target / 1000.0, userInitiated = true)
                        if (!wasPlaying) player.play()
                        savePlaybackState()
                        refreshPlayback()
                    }
                }
                onExpand = { showLyricsFullScreen() }
                visibility = if (curvePanel == 4) View.VISIBLE else View.GONE
            }
            curveContent.addView(lyricsView, FrameLayout.LayoutParams(-1, -1))
            visualizerView = VisualizerView(this@curveCard).apply {
                dark = this@curveCard.dark
                spatialPalette = palette
                visibility = if (curvePanel != 4) View.VISIBLE else View.GONE
                restoreVisualState(when (curvePanel) {
                    1 -> true to SPATIAL_CURVE_MODE
                    2 -> true to 4
                    3 -> true to 1
                    else -> false to 0
                })
                onExpand = { showVisualizerSheet() }
                onChooseEffect = { showVisualEffectPicker() }
                onChooseSandImages = { openSandImages.launch("image/*") }
                onVisualStateChanged = { state ->
                    val panel = visualizerPanel(state)
                    if (curvePanel != 4 && curvePanel != panel) selectCurvePanel(panel, restoreMode = false)
                }
            }
            curve = visualizerView.spatialCurve
            curveContent.addView(visualizerView, FrameLayout.LayoutParams(-1, -1))
            addView(curveContent)
        }
        return card
    }

internal fun MainActivity.showVisualEffectPicker() {
    val state = visualizerView.visualState
    lateinit var dialog: android.app.Dialog
    dialog = ThemeUi.showSheet(this, palette, t("visual.choose"), compactPicker = true) {
        addView(ThemeUi.button(this@showVisualEffectPicker, palette, t("creative.config"), ThemeUi.ROLE_CONTROL) {
            dialog.dismiss()
            showVisualCreative()
        })
        val analysisItems = listOf(t("analysis") to -1, t("curve") to -2,
            t("visual.pcm") to 4, t("visual.spectral") to 1, t("visual.layers") to -3)
        visualModeGroups.forEachIndexed { groupIndex, (key, ids) ->
            addView(ThemeUi.text(this@showVisualEffectPicker, t(key), 12f, palette, bold = true, role = ThemeUi.ROLE_SUB).apply {
                setPadding(0, ThemeUi.dp(context, 8f), 0, ThemeUi.dp(context, 4f))
            })
            val modes = exclusiveVisualModes.filter { it.second in ids && it.second !in setOf(1, 4) }
            val entries = (if (groupIndex == 0) analysisItems else emptyList()) + modes.map { t(it.first) to it.second }
            entries.chunked(2).forEach { row ->
                addView(ThemeUi.row(this@showVisualEffectPicker).apply {
                    row.forEach { (name, id) ->
                        val selected = when (id) {
                            -1 -> !state.first && curvePanel == 0
                            -2 -> curvePanel == 1
                            -3 -> false
                            4 -> state.first && state.second == 4 && curvePanel == 2
                            1 -> state.first && state.second == 1 && curvePanel == 3
                            else -> state.first && state.second == id && curvePanel == 2
                        }
                        addView(ThemeUi.text(this@showVisualEffectPicker, name, 12f, palette,
                            bold = selected, role = if (selected) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(ThemeUi.dp(context, 10f), 0, ThemeUi.dp(context, 10f), 0)
                            background = ThemeUi.bgDrawable(context, if (selected) palette.surfaceAlt else palette.surface, 8f)
                            setOnClickListener {
                                dialog.dismiss()
                                when (id) {
                                    -1 -> { selectCurvePanel(0); visualizerView.restoreVisualState(false to 0) }
                                    -2 -> selectCurvePanel(1)
                                    -3 -> showVisualLayerPicker()
                                    4 -> { selectCurvePanel(2); visualizerView.restoreVisualState(true to id) }
                                    1 -> { selectCurvePanel(3); visualizerView.restoreVisualState(true to id) }
                                    else -> { selectCurvePanel(2); visualizerView.restoreVisualState(true to id) }
                                }
                                refreshPlayback()
                            }
                        }, LinearLayout.LayoutParams(0, ThemeUi.dp(this@showVisualEffectPicker, 38f), 1f))
                    }
                    if (row.size == 1) addView(View(this@showVisualEffectPicker), LinearLayout.LayoutParams(0, 1, 1f))
                })
            }
        }
    }
    dialog.show()
}

internal fun MainActivity.showVisualCreative(scrollY: Int = 0) {
    var current = visualizerView.creative
    val creativePrefs = getSharedPreferences("visual_creative", android.content.Context.MODE_PRIVATE)
    var lockHue = creativePrefs.getBoolean("lock_hue", false)
    var lockOverlay = creativePrefs.getBoolean("lock_overlay", false)
    lateinit var sheet: android.app.Dialog
    lateinit var creativeBody: LinearLayout
    sheet = ThemeUi.showSheet(this, palette, t("creative.title")) {
        creativeBody = this
        val gap = ThemeUi.dp(this@showVisualCreative, 6f)
        fun reopen() {
            val position = (creativeBody.parent as? android.widget.ScrollView)?.scrollY ?: 0
            sheet.dismiss()
            showVisualCreative(position)
        }
        fun section(label: String) {
            addView(ThemeUi.text(this@showVisualCreative, label, 14f, palette, bold = true, role = ThemeUi.ROLE_TEXT).apply {
                setPadding(0, gap * 2, 0, gap)
            })
        }
        fun addActions(vararg buttons: android.widget.Button) {
            addView(ThemeUi.row(this@showVisualCreative).apply {
                buttons.forEachIndexed { index, button ->
                    button.setPadding(gap, 0, gap, 0)
                    addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = gap })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        }
        addView(ThemeUi.button(this@showVisualCreative, palette, t("creative.reset"), ThemeUi.ROLE_CONTROL) {
            current = VisualCreative()
            visualizerView.setCreative(current)
            visualizerView.setCreativeBackground(null)
            lockHue = false
            lockOverlay = false
            creativePrefs.edit().remove("lock_hue").remove("lock_overlay").apply()
            toast(t("creative.reset.done"))
            reopen()
        })
        section(t("creative.random_visual"))
        addView(android.widget.CheckBox(this@showVisualCreative).apply {
            text = t("creative.random_visual.enable"); setTextColor(palette.text)
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            isChecked = current.randomVisual
            setOnCheckedChangeListener { _, checked -> current = current.copy(randomVisual = checked); visualizerView.setCreative(current) }
        })
        val range = ThemeUi.row(this@showVisualCreative)
        fun rangeButton(minimum: Boolean): android.widget.Button {
            val key = if (minimum) "creative.random_visual.minimum" else "creative.random_visual.maximum"
            fun label() = "${t(key)}: ${if (minimum) current.randomMinSeconds else current.randomMaxSeconds} ${t("creative.seconds")}"
            return ThemeUi.button(this@showVisualCreative, palette, label(), ThemeUi.ROLE_CONTROL) {
                val options = (1..60).toList()
                val selected = if (minimum) current.randomMinSeconds else current.randomMaxSeconds
                pickOption(t(key), options.map { "$it ${t("creative.seconds")}" }, selected - 1) { index ->
                    val value = options[index]
                    current = if (minimum) current.copy(randomMinSeconds = value, randomMaxSeconds = maxOf(value, current.randomMaxSeconds))
                        else current.copy(randomMaxSeconds = value, randomMinSeconds = minOf(value, current.randomMinSeconds))
                    visualizerView.setCreative(current)
                    reopen()
                }
            }
        }
        range.addView(rangeButton(true), LinearLayout.LayoutParams(0, -2, 1f))
        range.addView(rangeButton(false), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
        addView(range)
        addView(ThemeUi.button(this@showVisualCreative, palette, t("creative.random_visual.choose").format(current.randomModes.size), ThemeUi.ROLE_CONTROL) {
            val selected = current.randomModes.toMutableSet()
            val checks = mutableListOf<Pair<Int, android.widget.CheckBox>>()
            val picker = ThemeUi.showSheet(this@showVisualCreative, palette, t("creative.random_visual.title")) {
                val groups = listOf(t("analysis") to 0) + exclusiveVisualModes.map { t(it.first) to it.second }
                val actions = ThemeUi.row(this@showVisualCreative)
                actions.addView(ThemeUi.button(this@showVisualCreative, palette, t("creative.random_visual.all"), ThemeUi.ROLE_CONTROL) {
                    checks.forEach { (mode, check) -> check.isChecked = mode != 39 || visualizerView.hasSandImages }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(ThemeUi.button(this@showVisualCreative, palette, t("creative.random_visual.clear"), ThemeUi.ROLE_CONTROL) {
                    checks.forEach { (_, check) -> check.isChecked = false }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
                addView(actions)
                groups.forEach { (name, mode) ->
                    addView(android.widget.CheckBox(this@showVisualCreative).apply {
                        text = name; setTextColor(palette.text)
                        buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                        isChecked = mode in selected
                        setOnCheckedChangeListener { button, checked ->
                            if (checked && mode == 39 && !visualizerView.hasSandImages) {
                                button.isChecked = false; toast(t("creative.random_visual.sand"))
                            } else if (checked) selected.add(mode) else selected.remove(mode)
                        }
                        checks.add(mode to this)
                    })
                }
            }
            picker.setOnDismissListener {
                if (selected.isEmpty()) toast(t("creative.random_visual.required"))
                else { current = current.copy(randomModes = selected.toList()); visualizerView.setCreative(current) }
                reopen()
            }
            picker.show()
        })
        section(t("creative.image_color"))
        val colors = ThemeUi.row(this@showVisualCreative)
        lateinit var hueSlider: android.widget.SeekBar
        listOf("creative.color.original" to 0, "creative.color.cool" to 180, "creative.color.warm" to 45, "creative.color.dream" to 280).forEachIndexed { index, (label, hue) ->
            colors.addView(ThemeUi.button(this@showVisualCreative, palette, t(label), ThemeUi.ROLE_CONTROL) {
                current = current.copy(hue = hue); visualizerView.setCreative(current)
                hueSlider.progress = hue
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = gap })
        }
        addView(colors)
        addView(ThemeUi.text(this@showVisualCreative, t("creative.color"), 13f, palette))
        addView(android.widget.SeekBar(this@showVisualCreative).apply {
            hueSlider = this
            max = 359
            progress = current.hue
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { current = current.copy(hue = value); visualizerView.setCreative(current) }
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
            })
        })
        fun slider(label: String, minimum: Int, maximum: Int, value: Int, update: (Int) -> Unit) {
            val title = ThemeUi.text(this@showVisualCreative, "$label：$value%", 13f, palette)
            addView(title)
            addView(android.widget.SeekBar(this@showVisualCreative).apply {
                max = maximum - minimum
                progress = value - minimum
                progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: android.widget.SeekBar?, position: Int, fromUser: Boolean) {
                        if (fromUser) { val amount = position + minimum; title.text = "$label：$amount%"; update(amount); visualizerView.setCreative(current) }
                    }
                    override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
                })
            })
        }
        slider(t("creative.brightness"), 25, 150, current.brightness) { current = current.copy(brightness = it) }
        val activeMode = visualizerView.visualState.let { if (it.first) it.second else 0 }
        val densityLabel = t(when (activeMode) { 18 -> "creative.density.leaves"; 19 -> "creative.density.snow"; 30 -> "creative.density.notes"; else -> "creative.density.particles" })
        val intensityLabel = t(when (activeMode) { 17 -> "creative.intensity.mountain"; 8, 34 -> "creative.intensity.stars"; 6, 22 -> "creative.intensity.water"; else -> "creative.intensity.music" })
        slider(densityLabel, 25, 200, current.density) { current = current.copy(density = it) }
        slider(intensityLabel, 25, 200, current.intensity) { current = current.copy(intensity = it) }
        section(t("creative.background"))
        val chooseBackground = ThemeUi.button(this@showVisualCreative, palette, t("creative.background.choose"), ThemeUi.ROLE_CONTROL) {
            sheet.dismiss(); openVisualImage.launch(arrayOf("image/*"))
        }
        val removeBackground = ThemeUi.button(this@showVisualCreative, palette, t("creative.background.remove"), ThemeUi.ROLE_CONTROL) {
            visualizerView.setCreativeBackground(null)
        }
        addActions(chooseBackground, removeBackground)
        slider(t("creative.background.opacity"), 0, 100, current.backgroundAlpha) { current = current.copy(backgroundAlpha = it) }
        slider(t("creative.background.blur"), 0, 24, current.backgroundBlur) { current = current.copy(backgroundBlur = it) }
        addView(android.widget.CheckBox(this@showVisualCreative).apply {
            text = t("creative.background.mask"); setTextColor(palette.text)
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            isChecked = current.backgroundMask
            setOnCheckedChangeListener { _, checked -> current = current.copy(backgroundMask = checked); visualizerView.setCreative(current) }
        })
        section(t("creative.overlay"))
        val options = listOf(t("creative.none") to 0, t("creative.leaves") to 18, t("creative.snow") to 19, t("creative.notes") to 30)
        val overlayRow = ThemeUi.row(this@showVisualCreative)
        options.forEachIndexed { index, (label, id) ->
            overlayRow.addView(ThemeUi.button(this@showVisualCreative, palette, label, ThemeUi.ROLE_CONTROL) {
                current = current.copy(overlay = id); visualizerView.setCreative(current)
                for (child in 0 until overlayRow.childCount) overlayRow.getChildAt(child).alpha = if (child == index) 1f else .6f
            }.apply { alpha = if (current.overlay == id) 1f else .6f }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = gap })
        }
        addView(overlayRow)
        section(t("creative.watermark"))
        addView(android.widget.CheckBox(this@showVisualCreative).apply {
            text = t("creative.watermark.show")
            setTextColor(palette.text)
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            isChecked = current.watermark
            setOnCheckedChangeListener { _, checked -> current = current.copy(watermark = checked); visualizerView.setCreative(current) }
        })
        val positions = listOf(t("creative.position.top_left"), t("creative.position.top_right"), t("creative.position.bottom_left"), t("creative.position.bottom_right"))
        addView(ThemeUi.button(this@showVisualCreative, palette, "${t("creative.position")}: ${positions[current.watermarkPosition]}", ThemeUi.ROLE_CONTROL) {
            pickOption(t("creative.position"), positions, current.watermarkPosition) { index ->
                current = current.copy(watermarkPosition = index); visualizerView.setCreative(current)
                (it as android.widget.Button).text = "${t("creative.position")}: ${positions[index]}"
            }
        })
        val watermarkText = ThemeUi.input(this@showVisualCreative, palette, t("creative.watermark.text")).apply {
            setText(current.watermarkText)
            filters = arrayOf(android.text.InputFilter.LengthFilter(32))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    current = current.copy(watermarkText = s?.toString()?.trim().orEmpty().ifEmpty { "8D Music" })
                    visualizerView.setCreative(current)
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        addView(watermarkText)
        section(t("creative.outro"))
        addView(ThemeUi.text(this@showVisualCreative, t("creative.outro.description"), 12f, palette, role = ThemeUi.ROLE_SUB))
        val outroText = ThemeUi.input(this@showVisualCreative, palette, t("creative.outro.hint")).apply {
            setText(current.outroText)
            filters = arrayOf(android.text.InputFilter.LengthFilter(24))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    current = current.copy(outroText = s?.toString().orEmpty())
                    visualizerView.setCreative(current)
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        addView(outroText)
        addView(ThemeUi.text(this@showVisualCreative, t("creative.outro.particles"), 13f, palette))
        val motifs = listOf(t("creative.stars") to 4, t("creative.notes") to 8, t("creative.leaves") to 16, t("creative.drops") to 2, t("creative.snow") to 1)
        addView(ThemeUi.row(this@showVisualCreative).apply {
                motifs.forEachIndexed { index, (label, flag) ->
                    addView(android.widget.CheckBox(this@showVisualCreative).apply {
                        text = label; textSize = 10f; setTextColor(palette.text); setPadding(0, 0, 0, 0)
                        isSingleLine = true
                        setAutoSizeTextTypeUniformWithConfiguration(8, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                        buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                        isChecked = current.outroMotifs and flag != 0
                        setOnCheckedChangeListener { button, checked ->
                            val selected = if (checked) current.outroMotifs or flag else current.outroMotifs and flag.inv()
                            if (selected == 0) { button.isChecked = true; toast(t("creative.particle.required")) }
                            else {
                                current = current.copy(outroMotifs = selected, outroText = outroText.text.toString().trim().take(24))
                                visualizerView.setCreative(current)
                            }
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = ThemeUi.dp(context, 2f) })
                }
        })
        addView(ThemeUi.button(this@showVisualCreative, palette, "${t("creative.outro.duration")}: ${current.outroDuration} ${t("creative.seconds")}", ThemeUi.ROLE_CONTROL) {
            current = current.copy(outroText = outroText.text.toString().trim().take(24))
            visualizerView.setCreative(current)
            val durations = listOf(1, 2, 3, 4, 5)
            pickOption(t("creative.outro.duration"), durations.map { "$it ${t("creative.seconds")}" }, durations.indexOf(current.outroDuration)) { index ->
                current = current.copy(outroDuration = durations[index])
                visualizerView.setCreative(current)
                (it as android.widget.Button).text = "${t("creative.outro.duration")}: ${durations[index]} ${t("creative.seconds")}"
            }
        })
        section(t("creative.random_recipe"))
        addView(android.widget.CheckBox(this@showVisualCreative).apply {
            text = t("creative.lock_color"); setTextColor(palette.text)
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent); isChecked = lockHue
            setOnCheckedChangeListener { _, checked -> lockHue = checked; creativePrefs.edit().putBoolean("lock_hue", checked).apply() }
        })
        addView(android.widget.CheckBox(this@showVisualCreative).apply {
            text = t("creative.lock_overlay"); setTextColor(palette.text)
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent); isChecked = lockOverlay
            setOnCheckedChangeListener { _, checked -> lockOverlay = checked; creativePrefs.edit().putBoolean("lock_overlay", checked).apply() }
        })
        val randomize = ThemeUi.button(this@showVisualCreative, palette, t("creative.random"), ThemeUi.ROLE_CONTROL) {
            current = current.copy(hue = if (lockHue) current.hue else kotlin.random.Random.nextInt(360),
                overlay = if (lockOverlay) current.overlay else options.random().second)
            visualizerView.setCreative(current)
            hueSlider.progress = current.hue
            for (index in 0 until overlayRow.childCount) overlayRow.getChildAt(index).alpha = if (options[index].second == current.overlay) 1f else .6f
        }
        val shareRecipe = ThemeUi.button(this@showVisualCreative, palette, t("creative.share"), ThemeUi.ROLE_CONTROL) {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, current.copy(mode = visualizerView.visualState.let { if (it.first) it.second else 0 }).encode())
            startActivity(android.content.Intent.createChooser(send, t("creative.share.title")))
        }
        addActions(randomize, shareRecipe)
        val recipe = ThemeUi.input(this@showVisualCreative, palette, t("creative.recipe.hint"))
        addView(recipe)
        addView(ThemeUi.button(this@showVisualCreative, palette, t("creative.recipe.import"), ThemeUi.ROLE_CONTROL) {
            runCatching { j5i.spatial8d.ui.VisualCreative.decode(recipe.text.toString()) }
                .onSuccess {
                    current = it.copy(mode = null)
                    visualizerView.setCreative(current)
                    it.mode?.let { mode -> visualizerView.restoreVisualState((mode != 0) to mode) }
                    reopen()
                }
                .onFailure { toast(t("creative.recipe.invalid")) }
        })
        for (index in 1 until childCount) {
            val child = getChildAt(index)
            if (child is android.widget.Button) {
                (child.layoutParams as? LinearLayout.LayoutParams)?.let { it.topMargin = gap; child.layoutParams = it }
            }
        }
    }
    sheet.show()
    if (scrollY > 0) creativeBody.post { (creativeBody.parent as? android.widget.ScrollView)?.scrollTo(0, scrollY) }
}

internal fun MainActivity.showVisualLayerPicker() {
    val layers = visualizerView.analysisLayers
    val names = listOf("wave", "spectrum", "loudness", "phase").mapIndexed { index, key ->
        "${if (layers[index]) "✓" else "○"} ${t(key)}"
    }
    pickOption(t("visual.layers"), names, -1) { index ->
        visualizerView.toggleAnalysisLayer(index)
        showVisualLayerPicker()
    }
}

    /** 服务可能先于页面重建；每次恢复可视化都重新挂上输出回调，避免永久失去 PCM 输入。 */

internal fun MainActivity.selectCurvePanel(index: Int, restoreMode: Boolean = true) {
    curvePanel = index
    for (tab in 0 until curveTabs.childCount) {
        (curveTabs.getChildAt(tab) as? android.widget.Button)?.let { button ->
            val selected = (tab == 1) == (index == 4)
            button.tag = if (selected) ThemeUi.ROLE_SURFACE else ThemeUi.ROLE_TEXT
            ThemeUi.applyButton(button, palette)
            button.setTextColor(if (selected) palette.accent else palette.sub)
        }
    }
    if (index != 4) installPcmRenderer()
    visualizerView.visibility = if (index != 4) View.VISIBLE else View.GONE
    if (restoreMode) when (index) {
        0 -> visualizerView.restoreVisualState(false to 0)
        1 -> visualizerView.restoreVisualState(true to SPATIAL_CURVE_MODE)
        2 -> visualizerView.restoreVisualState(true to 4)
        3 -> visualizerView.restoreVisualState(true to 1)
    }
    lyricsView.visibility = if (index == 4) View.VISIBLE else View.GONE
}

/** 点按播放栏当前歌词行时，直接切到歌词 tab 并滚动到卡片位置。 */
internal fun MainActivity.openLyricsTab() {
    if (isLive || currentLyrics == null) {
        toast(t("ui.lyrics.empty"))
        return
    }
    selectCurvePanel(4)
    val scroll = root.parent as? android.widget.ScrollView ?: return
    val card = curveContent.parent as? View ?: return
    scroll.post { scroll.smoothScrollTo(0, card.top) }
}

internal fun MainActivity.installPcmRenderer() {
        var lastHistoryAt = 0L
        val render: (ByteArray, Int) -> Unit = { data, bytes ->
            val startedAt = System.nanoTime()
            visualizerView.pcmClock.accept(data, bytes)
            if (isViewReady(this@installPcmRenderer::visualization)) visualization.acceptPcm(data, bytes)
            val visible = curvePanel != 1 && curvePanel != 4 || drivingDialog?.isShowing == true || popupVisualizer != null
            if (isViewReady(this@installPcmRenderer::visualizerView) && (visible || startedAt - lastHistoryAt >= 100_000_000L)) {
                visualizerView.accept(data, bytes)
                lastHistoryAt = startedAt
            }
        PerfTrace.sample("visual.fanoutNs", System.nanoTime() - startedAt)
        }
        service?.player?.onPcm = render
        service?.radio?.onPcm = { data, bytes ->
            render(data, bytes)
            if (isLive && offlineAsrEnabled) offlineAsr.acceptPcm(data, bytes, service?.radio?.outputSampleRate ?: 44_100, 2)
        }
    }

    /** 文件名和时长共同参与匹配，切歌代号阻止旧请求覆盖新歌歌词。 */

internal fun MainActivity.toggleParameterOpen() {
        parameterOpen = !parameterOpen
        refreshCardState()
        if (parameterOpen) refreshAll()
    }

internal fun MainActivity.toggleTransformOpen() {
        transformOpen = !transformOpen
        refreshCardState()
        if (transformOpen) refreshAll()
    }

internal fun MainActivity.refreshCardState() {
        if (isViewReady(this@refreshCardState::parameterPanel)) {
            parameterPanel.visibility = if (parameterOpen) View.VISIBLE else View.GONE
        ((parameterPanel.parent as? ViewGroup)?.findViewWithTag<View>("toggleP") as? TextView)?.let {
                it.text = if (parameterOpen) t("collapse") else t("expand")
            }
        }
        if (isViewReady(this@refreshCardState::transformBody)) {
            transformBody.visibility = if (transformOpen) View.VISIBLE else View.GONE
            (transformCardRoot.findViewWithTag<View>("toggleT") as? TextView)?.let {
                it.text = if (transformOpen) t("collapse") else t("expand")
            }
        }
    }

internal fun MainActivity.switchRow(title: String, sub: String, action: (Boolean) -> Unit): Pair<View, SwitchCompat> {
        val row = ThemeUi.row(this)
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = ThemeUi.dp(this@switchRow, 10f)
            }
            addView(ThemeUi.text(this@switchRow, title, 12f, palette, bold = true))
            addView(ThemeUi.text(this@switchRow, sub, 10f, palette, role = ThemeUi.ROLE_FAINT))
        }
        row.addView(main)
        val sw = SwitchCompat(this).apply {
            isChecked = false
            setOnCheckedChangeListener { _, checked -> action(checked) }
        }
        row.addView(sw)
        return row to sw
    }

internal fun MainActivity.formatSpeedShort(v: Double): String = when {
        v == 1.0 -> "1"
        v == 2.0 -> "2"
        v == 0.5 -> "0.5"
        v == 0.75 -> "0.75"
        v == 1.25 -> "1.25"
        v == 1.5 -> "1.5"
        else -> v.toString()
    }

internal fun MainActivity.semiName(semi: Double): String = when (semi) {
        -12.0, -8.0, -4.0, 0.0, 4.0, 8.0, 12.0 -> AppLanguage.pitch(this, semi)
        else -> "${if (semi > 0) "+" else ""}${semi.toInt()}"
    }

internal fun MainActivity.formatPitchLabel(semi: Double): String =
        if (semi == 0.0) "${t("original")} · ${t("label.default")}" else
            "${AppLanguage.pitch(this, semi)} · ${if (semi > 0) "+" else ""}${semi.toInt()} ${t("semitone")}"

internal fun MainActivity.pitchPickerLabel(): String = PITCH_PRESETS.firstOrNull { it.semi == transform.semi }
        ?.let { formatPitchLabel(it.semi) }
        ?: "${t("preset.custom")} · ${if (transform.semi > 0) "+" else ""}${transform.semi.toInt()} ${t("semitone")}"

    /** 用户点击“退出”时彻底停掉音频链路，否则前台服务会让应用继续驻留后台。 */
