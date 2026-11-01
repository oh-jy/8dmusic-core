package j5i.spatial8d.ui

import android.app.Activity
import android.content.Context
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.SeekBar
import android.widget.SpinnerAdapter
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.appcompat.widget.SwitchCompat

object ThemeUi {
    const val ROLE_TEXT = "role_text"
    const val ROLE_SUB = "role_sub"
    const val ROLE_FAINT = "role_faint"
    const val ROLE_ACCENT = "role_accent"
    const val ROLE_DARK_INK = "role_dark_ink"
    const val ROLE_CONTROL = "role_control"
    const val ROLE_ACCENT_FILL = "role_accent_fill"
    const val ROLE_SURFACE_ALT = "role_surface_alt"
    const val ROLE_SURFACE = "role_surface"

    data class Palette(
        val background: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val divider: Int,
        val text: Int,
        val sub: Int,
        val faint: Int,
        val control: Int,
        val controlText: Int,
        val accent: Int,
        val darkInk: Int
    )

    fun palette(dark: Boolean): Palette = if (dark) Palette(
        background = 0xff1b1d21.toInt(),
        surface = 0xff1e2126.toInt(),
        surfaceAlt = 0xff262a30.toInt(),
        divider = 0xff2c2f34.toInt(),
        text = 0xffeef1f5.toInt(),
        sub = 0xff8a9099.toInt(),
        faint = 0xff70767e.toInt(),
        control = 0xff2c3037.toInt(),
        controlText = 0xffdfe3e7.toInt(),
        accent = 0xff4ea1ff.toInt(),
        darkInk = 0xff0b0c0e.toInt()
    ) else Palette(
        background = 0xfff2f3f5.toInt(),
        surface = 0xffffffff.toInt(),
        surfaceAlt = 0xfff4f6f9.toInt(),
        divider = 0xffe8eaee.toInt(),
        text = 0xff1f2429.toInt(),
        sub = 0xff8a93a3.toInt(),
        faint = 0xffa0a7b1.toInt(),
        control = 0xffeef0f3.toInt(),
        controlText = 0xff3a3f45.toInt(),
        accent = 0xff2e7fe6.toInt(),
        darkInk = 0xffffffff.toInt()
    )

    fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    fun bg(view: View, color: Int, radiusDp: Float) {
        val gd = GradientDrawable()
        gd.cornerRadius = dp(view.context, radiusDp).toFloat()
        gd.setColor(color)
        view.background = gd
    }

    fun text(context: Context, value: String, sp: Float, p: Palette, bold: Boolean = false, role: String = ROLE_TEXT): TextView =
        TextView(context).apply {
            text = value
            textSize = sp
            setPadding(0, dp(context, 3f), 0, dp(context, 3f))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            tag = role
            setTextColor(when (role) {
                ROLE_SUB -> p.sub
                ROLE_FAINT -> p.faint
                ROLE_ACCENT -> p.accent
                ROLE_DARK_INK -> p.darkInk
                else -> p.text
            })
        }

    fun button(context: Context, p: Palette, label: String, role: String = ROLE_CONTROL, minHeightDp: Float = 38f): Button =
        Button(context).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(context, minHeightDp)
            minimumHeight = dp(context, minHeightDp)
            stateListAnimator = null
            setPadding(dp(context, 12f), 0, dp(context, 12f), 0)
            tag = role
            applyButton(this, p)
        }

    fun button(context: Context, p: Palette, label: String, onClick: (View) -> Unit): Button =
        button(context, p, label).apply { setOnClickListener(onClick) }

    fun button(context: Context, p: Palette, label: String, role: String, onClick: (View) -> Unit): Button =
        button(context, p, label, role).apply { setOnClickListener(onClick) }

    fun input(context: Context, p: Palette, hintText: String): EditText =
        EditText(context).apply {
            hint = hintText
            textSize = 12f
            setSingleLine()
            setTextColor(p.text)
            setHintTextColor(p.faint)
            setPadding(dp(context, 10f), 0, dp(context, 10f), 0)
            background = bgDrawable(context, p.control, 8f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 36f)).apply {
                bottomMargin = dp(context, 6f)
            }
        }

    fun applyTheme(view: View, p: Palette) {
        when (view) {
            is EditText -> {
                view.setTextColor(p.text)
                view.setHintTextColor(p.faint)
                view.background = bgDrawable(view.context, p.control, 8f)
            }
            is Button -> applyButton(view, p)
            is TextView -> {
                val role = view.tag as? String ?: ROLE_TEXT
                view.setTextColor(when (role) {
                    ROLE_SUB -> p.sub
                    ROLE_FAINT -> p.faint
                    ROLE_ACCENT -> p.accent
                    ROLE_DARK_INK -> p.darkInk
                    ROLE_CONTROL -> p.controlText
                    else -> p.text
                })
            }
            is SwitchCompat -> view.setTextColor(p.text)
        }
        if (view.tag == "card") {
            bg(view, p.surface, 14f)
        } else if (view !is Button) {
            // Only recolor known surfaces; accent fills and other semantic colors stay intact.
            val background = view.background as? GradientDrawable
            val color = background?.color?.defaultColor
            listOf(palette(true), palette(false)).forEach { old ->
                when (color) {
                    old.surface -> background.setColor(p.surface)
                    old.control -> background.setColor(p.control)
                    old.surfaceAlt -> background.setColor(p.surfaceAlt)
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTheme(view.getChildAt(i), p)
        }
    }

    fun sheetBody(dialog: Dialog): LinearLayout? =
        dialog.findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag("sheetBody")

    fun applyButton(v: Button, p: Palette) {
        val role = v.tag as? String ?: ROLE_CONTROL
        val textColor = when (role) {
            ROLE_ACCENT_FILL -> p.darkInk
            ROLE_ACCENT -> p.accent
            else -> p.controlText
        }
        v.setTextColor(textColor)
        val bg = when (role) {
            ROLE_ACCENT_FILL -> p.accent
            ROLE_SURFACE_ALT -> p.surfaceAlt
            ROLE_SURFACE -> p.surface
            ROLE_TEXT -> null
            else -> p.control
        }
        if (bg == null) v.background = null else bg(v, bg, 8f)
    }

    fun picker(view: TextView, p: Palette) {
        view.setTextColor(p.controlText)
        view.background = bgDrawable(view.context, p.control, 8f)
    }

    fun divider(context: Context, p: Palette, heightDp: Float = 1f): View =
        View(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, heightDp)
            )
            setBackgroundColor(p.divider)
        }

    fun card(context: Context, p: Palette, block: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            bg(this, p.surface, 14f)
            setPadding(dp(context, 12f), dp(context, 9f), dp(context, 12f), dp(context, 9f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(context, 10f), dp(context, 8f), dp(context, 10f), 0)
            layoutParams = lp
            tag = "card"
            block()
        }

    fun row(context: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
        }

    fun showSheet(
        context: Context,
        p: Palette,
        title: String,
        subtitle: String? = null,
        cancelLabel: String? = "取消",
        onCancel: (() -> Unit)? = null,
        fullHeight: Boolean = false,
        scrollable: Boolean = true,
        compactPicker: Boolean = false,
        block: LinearLayout.() -> Unit
    ): Dialog {
        var dlg: Dialog? = null
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16f), 0, dp(context, 16f), dp(context, if (compactPicker) 8f else 12f))
            bg(this, p.surface, 18f)
            gravity = Gravity.TOP
        }
        root.addView(FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(context, 22f))
            addView(View(context).apply {
                background = bgDrawable(context, if (p.control == 0xff2c3037.toInt())
                    0xff4a4f57.toInt() else 0xffd5d9df.toInt(), 2f)
                layoutParams = FrameLayout.LayoutParams(dp(context, 36f), dp(context, 4f), Gravity.CENTER)
            })
            setOnClickListener {
                onCancel?.invoke()
                dlg?.dismiss()
            }
        })
        root.addView(FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            addView(text(context, title, 15f, p, bold = true))
        })
        if (subtitle != null) {
            root.addView(text(context, subtitle, 11f, p, role = ROLE_SUB))
        }
        if (!fullHeight) root.addView(divider(context, p, heightDp = 1f).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 1f)
            ).apply { setMargins(0, dp(context, if (compactPicker) 2f else 8f), 0, dp(context, if (compactPicker) 2f else 8f)) }
        })
        val cancelText = cancelLabel?.takeIf { it != "取消" } ?: AppLanguage.text(context, "cancel")
        // Full-height content may own its scrolling regions (station list).
        val body = if (scrollable) SheetScrollView(context) else FrameLayout(context)
        body.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (fullHeight) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (fullHeight) 1f else 0f
        )
        if (!fullHeight && body is SheetScrollView)
            body.maxHeight = (context.resources.displayMetrics.heightPixels * 0.55).toInt()
        if (body is ScrollView) body.isFillViewport = true
        val bodyRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "sheetBody"
            block()
        }
        body.addView(bodyRoot)
        root.addView(body)
        if (cancelText != null) {
            if (fullHeight) {
                // 满屏页与列表一致使用右上角轻量关闭，不把底部留白换成大按钮。
                val close = text(context, cancelText, 12f, p, role = ROLE_ACCENT).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 12f), dp(context, 8f), dp(context, 12f), dp(context, 8f))
                }
                close.setOnClickListener {
                    onCancel?.invoke()
                    dlg?.dismiss()
                }
                (root.getChildAt(1) as? ViewGroup)?.addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.END))
            }
            root.addView(button(context, p, cancelText, role = ROLE_SURFACE_ALT).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, if (compactPicker) 34f else 42f)).apply {
                    topMargin = dp(context, if (compactPicker) 4f else 12f)
                }
                visibility = if (fullHeight) View.GONE else View.VISIBLE
            })
        }
        return Dialog(context).apply {
            dlg = this
            setContentView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (fullHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            window?.setBackgroundDrawable(null)
            window?.setDimAmount(0.45f)
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            layoutSheet(this, fullHeight)
            setOnShowListener { layoutSheet(this, fullHeight) }
            if (cancelText != null) {
                root.getChildAt(root.childCount - 1)?.setOnClickListener {
                    onCancel?.invoke()
                    dlg?.dismiss()
                }
            }
        }
    }

    fun replaceSheetBody(dialog: Dialog, block: LinearLayout.() -> Unit) {
        val body = sheetBody(dialog) ?: return
        body.removeAllViews()
        body.block()
    }

    fun layoutSheet(dialog: Dialog, fullHeight: Boolean = true) {
        val window = dialog.window ?: return
        val context = dialog.context
        val top = if (fullHeight) statusBarHeight(context) + dp(context, 64f) else 0
        val height = if (fullHeight)
            (context.resources.displayMetrics.heightPixels - top - dp(context, 12f)).coerceAtLeast(0)
        else ViewGroup.LayoutParams.WRAP_CONTENT
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
        val bottom = if (fullHeight) 0 else navigationBarHeight(context)
        window.attributes = window.attributes.apply {
            x = 0
            y = 0
            verticalMargin = 0f
        }
        window.setGravity(Gravity.BOTTOM)
        dialog.findViewById<ViewGroup>(android.R.id.content)?.setPadding(0, 0, 0, bottom)
    }

    @Suppress("DEPRECATION")
    private fun statusBarHeight(context: Context): Int {
        val insets = activityInsets(context) ?: return 0
        return if (android.os.Build.VERSION.SDK_INT >= 30)
            insets.getInsets(WindowInsets.Type.statusBars()).top
        else insets.systemWindowInsetTop
    }

    @Suppress("DEPRECATION")
    private fun navigationBarHeight(context: Context): Int {
        val insets = activityInsets(context) ?: return 0
        return if (android.os.Build.VERSION.SDK_INT >= 30)
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        else insets.systemWindowInsetBottom
    }

    private fun activityInsets(context: Context): WindowInsets? =
        (context as? Activity)?.window?.decorView?.rootWindowInsets

    fun hideOverflow(textView: TextView) {
        textView.ellipsize = TextUtils.TruncateAt.END
        textView.maxLines = 1
    }

    fun configureSeekBar(bar: SeekBar, p: Palette, playback: Boolean = false) {
        val dark = p.darkInk == 0xff0b0c0e.toInt()
        bar.progressTintList = ColorStateList.valueOf(p.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(if (playback) {
            if (dark) 0xff4a505a.toInt() else 0xffd4dae3.toInt()
        } else {
            if (dark) p.control else 0xffe6e9ee.toInt()
        })
        val size = dp(bar.context, if (playback) 12f else 16f)
        val inset = dp(bar.context, 2f)
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (dark) 0xffaab4c2.toInt() else 0xff6d7887.toInt())
            setSize(size, size)
        }
        bar.thumb = LayerDrawable(arrayOf(thumb)).apply {
            setLayerInset(0, inset, inset, inset, inset)
        }
        // SeekBar clips thumbs at its content bounds; preserve room at both ends.
        val horizontalInset = size / 2 + inset
        bar.setPadding(horizontalInset, bar.paddingTop, horizontalInset, bar.paddingBottom)
        bar.thumbOffset = 0
    }

    fun configureSpinner(spinner: Spinner, p: Palette) {
        spinner.background = bgDrawable(spinner.context, p.control, 8f)
        spinner.setPopupBackgroundDrawable(bgDrawable(spinner.context, p.surfaceAlt, 8f))
        spinner.adapter?.let { if (it !is StyledAdapter) spinner.adapter = StyledAdapter(spinner.context, it, p) }
    }

    fun bgDrawable(context: Context, color: Int, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.cornerRadius = dp(context, radiusDp).toFloat()
        drawable.setColor(color)
        return drawable
    }

    private class SheetScrollView(context: Context) : ScrollView(context) {
        var maxHeight = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val boundedHeight = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, boundedHeight)
        }
    }

    class StyledAdapter(
        context: Context,
        private val original: SpinnerAdapter,
        private val p: Palette
    ) : android.widget.BaseAdapter() {
        override fun getCount(): Int = original.count
        override fun getItem(position: Int): Any? = original.getItem(position)
        override fun getItemId(position: Int): Long = original.getItemId(position)
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            original.getView(position, convertView, parent).apply {
                (this as? TextView)?.setTextColor(p.text)
                (this as? TextView)?.apply {
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 10f), dp(context, 8f), dp(context, 24f), dp(context, 8f))
                }
                setBackgroundColor(Color.TRANSPARENT)
            }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            (original as? android.widget.BaseAdapter)?.getDropDownView(position, convertView, parent)?.apply {
                (this as? TextView)?.setTextColor(p.text)
                (this as? TextView)?.textSize = 12f
                setPadding(dp(context, 12f), dp(context, 10f), dp(context, 12f), dp(context, 10f))
            } ?: getView(position, convertView, parent)
    }
}
