package j5i.spatial8d

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
import j5i.spatial8d.core.SLIDERS
import j5i.spatial8d.core.SPACE_PRESETS
import j5i.spatial8d.core.PitchPreset
import j5i.spatial8d.core.PITCH_PRESETS
import j5i.spatial8d.core.clampSlider
import j5i.spatial8d.core.formatValue
import j5i.spatial8d.ui.CurveView
import j5i.spatial8d.ui.ThemeUi
import j5i.spatial8d.ui.AppLanguage
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.pow

internal fun MainActivity.card(block: LinearLayout.() -> Unit): LinearLayout = ThemeUi.card(this, palette, block)

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
                    val pinned = listOfNotNull(SPACE_PRESETS.firstOrNull { it.raw },
                        SPACE_PRESETS.firstOrNull { !it.raw })
                    val all = pinned + (SPACE_PRESETS + customPresets).filter { it !in pinned }
                        .sortedWith(compareBy({ it.room }, { it.name }))
                    pickOption(t("space.choose"), all.map { item ->
                        val index = SPACE_PRESETS.indexOfFirst { it.name == item.name }
                        val name = if (index >= 0) AppLanguage.preset(this@spaceSettingsCard, index) else item.name.replaceFirst("标准绕头 · ", "标准模式 · ")
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
                hint = "自定义风格名称"
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
                text = formatValue(definition.key, initialValue)
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
                            sliderValues[definition.key]?.text = formatValue(definition.key, value)
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
            pitchPicker = ThemeUi.text(this@transformCard, "原声", 12f, palette, role = ThemeUi.ROLE_CONTROL).apply {
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
            transformBody.addView(pitchSlider)
            val tickRow = ThemeUi.row(this@transformCard)
            val pitchTickButtons = mutableListOf<Button>()
            listOf(-12.0, -8.0, -4.0, 0.0, 4.0, 8.0, 12.0).forEach { semi ->
                tickRow.addView(LinearLayout(this@transformCard).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(View(this@transformCard).apply {
                        setBackgroundColor(palette.faint)
                        layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@transformCard, 2f), ThemeUi.dp(this@transformCard, 3f))
                    })
                    addView(ThemeUi.button(this@transformCard, palette, semiName(semi), role = ThemeUi.ROLE_TEXT) {
                        transform = transform.copy(semi = semi); applyTransform(); refreshAll()
                    }.apply {
                        textSize = 9f
                        minHeight = 0; minimumHeight = 0
                        setPadding(0, 0, 0, 0)
                        pitchTickButtons.add(this)
                    })
                    layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@transformCard, 28f), 1f)
                })
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
                max = 30
                progress = ((transform.speed - 0.5) / 0.05).roundToInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val speed = 0.5 + progress * 0.05
                            transform = transform.copy(speed = speed.toDouble())
                            applyTransform(syncControls = false)
                            speedLabel.text = String.format(Locale.US, "%.2f×", speed)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) { draggingControl = true }
                    override fun onStopTrackingTouch(seekBar: SeekBar) { draggingControl = false }
                })
            }
            speedSlider.tag = "localOnly"
            transformBody.addView(speedSlider)
            val chips = ThemeUi.row(this@transformCard)
            val speedButtons = mutableListOf<Button>()
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { value ->
                chips.addView(LinearLayout(this@transformCard).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(View(this@transformCard).apply {
                        setBackgroundColor(palette.faint)
                        layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@transformCard, 2f), ThemeUi.dp(this@transformCard, 3f))
                    })
                    addView(ThemeUi.button(this@transformCard, palette, "${formatSpeedShort(value)}×", role = ThemeUi.ROLE_TEXT) {
                        transform = transform.copy(speed = value)
                        applyTransform()
                        refreshAll()
                    }.apply {
                        textSize = 9f
                        minHeight = 0; minimumHeight = 0
                        setPadding(0, 0, 0, 0)
                        speedButtons.add(this)
                    })
                    layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@transformCard, 28f), 1f)
                })
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
            curveTabs = tabRow(listOf(t("curve"), t("visualizer"), t("lyrics")), curvePanel, compact = true) {
                selectCurvePanel(it)
            }
            addView(curveTabs)
            curveLegend = ThemeUi.row(this@curveCard).apply {
                visibility = if (curvePanel == 0) View.VISIBLE else View.GONE
                listOf(
                    t("left.ear") to 0xff4ea1ff.toInt(),
                    t("right.ear") to 0xffff7a59.toInt(),
                    t("elevation") to 0xff4fd396.toInt(),
                    t("distance") to 0xffa98ce8.toInt()
                ).forEach { (label, color) ->
                    addView(ThemeUi.row(this@curveCard).apply {
                        addView(View(this@curveCard).apply {
                            background = ThemeUi.bgDrawable(this@curveCard, color, 3f)
                            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@curveCard, 6f), ThemeUi.dp(this@curveCard, 6f))
                        })
                        addView(ThemeUi.text(this@curveCard, label, 10f, palette, role = ThemeUi.ROLE_SUB).apply {
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                leftMargin = ThemeUi.dp(this@curveCard, 4f)
                            }
                        })
                    }.apply {
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            leftMargin = ThemeUi.dp(this@curveCard, 10f)
                        }
                    })
                }
            }
            addView(curveLegend)
            // 用 FrameLayout 叠放四个面板，同一时间只有一个 VISIBLE，MATCH_PARENT 才能占满整个高度。
            // 之前 LinearLayout 中多个 height=-1 的子 view 会被平分/压缩，造成音频图谱/可视化上下留白。
            curveContent = FrameLayout(this@curveCard).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@curveCard, if (curvePanel == 0) 140f else 162f))
            }
            curve = CurveView(this@curveCard).apply { visibility = if (curvePanel == 0) View.VISIBLE else View.GONE }
            curveContent.addView(curve, FrameLayout.LayoutParams(-1, -1))
            lyricsView = LyricsView(this@curveCard).apply {
                dark = this@curveCard.dark
                onSearch = { query -> currentItem?.let { loadLyrics(it, query) } }
                onManual = { currentItem?.let { showLyricsCandidates(it) } }
                visibility = if (curvePanel == 2) View.VISIBLE else View.GONE
            }
            curveContent.addView(lyricsView, FrameLayout.LayoutParams(-1, -1))
            visualizerView = VisualizerView(this@curveCard).apply {
                dark = this@curveCard.dark
                visibility = if (curvePanel == 1) View.VISIBLE else View.GONE
                onExpand = { showVisualizerSheet() }
            }
            curveContent.addView(visualizerView, FrameLayout.LayoutParams(-1, -1))
            addView(curveContent)
        }
        return card
    }

    /** 服务可能先于页面重建；每次恢复可视化都重新挂上输出回调，避免永久失去 PCM 输入。 */

internal fun MainActivity.selectCurvePanel(index: Int) {
    curvePanel = index
    for (tab in 0 until curveTabs.childCount) {
        (curveTabs.getChildAt(tab) as? android.widget.Button)?.let { button ->
            button.tag = if (tab == index) ThemeUi.ROLE_SURFACE else ThemeUi.ROLE_TEXT
            ThemeUi.applyButton(button, palette)
            button.setTextColor(if (tab == index) palette.accent else palette.sub)
        }
    }
    if (index == 1) installPcmRenderer()
    curve.visibility = if (index == 0) View.VISIBLE else View.GONE
    visualizerView.visibility = if (index == 1) View.VISIBLE else View.GONE
    lyricsView.visibility = if (index == 2) View.VISIBLE else View.GONE
    // 非"空间曲线" tab 隐藏图例并把它的高度转移给 curveContent，
    // 避免图例行占位造成波形/频谱顶部一片空白。
    val legendHeight = curveLegend.height.takeIf { it > 0 } ?: ThemeUi.dp(this, 22f)
    val showLegend = index == 0
    val wasVisible = curveLegend.visibility == View.VISIBLE
    curveLegend.visibility = if (showLegend) View.VISIBLE else View.GONE
    if (isViewReady(this::curveContent) && wasVisible != showLegend) {
        val current = curveContent.layoutParams.height
        val next = if (showLegend) (current - legendHeight).coerceAtLeast(ThemeUi.dp(this, 80f))
        else current + legendHeight
        curveContent.layoutParams = curveContent.layoutParams.apply { height = next }
    }
}

/** 点按播放栏当前歌词行时，直接切到歌词 tab 并滚动到卡片位置。 */
internal fun MainActivity.openLyricsTab() {
    if (isLive || currentLyrics == null) {
        toast("当前没有可查看的歌词")
        return
    }
    selectCurvePanel(2)
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
            val visible = curvePanel == 1 || drivingDialog?.isShowing == true || popupVisualizer != null
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
