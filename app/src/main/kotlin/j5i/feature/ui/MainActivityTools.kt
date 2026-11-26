package j5i.spatial8d.feature.ui
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*
import j5i.spatial8d.MainActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import j5i.spatial8d.ui.ThemeUi

internal fun MainActivity.showToolsSheet() {
    val activity = this
    val lookup = ThemeUi.button(this, palette, t("tools.lookup"), ThemeUi.ROLE_ACCENT_FILL)
    val replace = ThemeUi.button(this, palette, t("tools.replace"))
    val panels = FrameLayout(this)
    val editor = replaceTextPanel().apply { visibility = View.GONE }
    panels.addView(editor, FrameLayout.LayoutParams(-1, -1))
    val web = ipLookupPanel()
    panels.addView(web, FrameLayout.LayoutParams(-1, -1))
    fun select(showLookup: Boolean) {
        web.visibility = if (showLookup) View.VISIBLE else View.GONE
        editor.visibility = if (showLookup) View.GONE else View.VISIBLE
        lookup.tag = if (showLookup) ThemeUi.ROLE_ACCENT_FILL else ThemeUi.ROLE_CONTROL
        replace.tag = if (showLookup) ThemeUi.ROLE_CONTROL else ThemeUi.ROLE_ACCENT_FILL
        ThemeUi.applyButton(lookup, palette)
        ThemeUi.applyButton(replace, palette)
    }
    lookup.setOnClickListener { select(true) }
    replace.setOnClickListener { select(false) }
    val dialog = ThemeUi.showSheet(this, palette, t("tools"), cancelLabel = t("close"), fullHeight = true, scrollable = false) {
        addView(ThemeUi.row(activity).apply {
            addView(lookup, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ThemeUi.dp(activity, 6f) })
            addView(replace, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(panels, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ThemeUi.dp(activity, 8f) })
    }
    dialog.setOnShowListener { dialog.window?.setLayout(-1, -1) }
    dialog.setOnDismissListener {
        panels.removeView(web)
        web.stopLoading()
        web.destroy()
    }
    dialog.show()
    web.loadUrl("https://site.ip138.com/")
}

private fun MainActivity.ipLookupPanel(): WebView {
    val theme = if (dark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light
    return WebView(ContextThemeWrapper(this, theme)).apply {
        setBackgroundColor(palette.surface)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                shouldOverrideUrlLoading(view, request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                Uri.parse(url).let { it.scheme != "https" || it.host != "site.ip138.com" }
        }
    }
}

private fun MainActivity.replaceTextPanel(): ScrollView {
    val activity = this
    val source = ThemeUi.input(this, palette, t("tools.text")).apply {
        setSingleLine(false)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START
        minLines = 4
        setPadding(paddingLeft, ThemeUi.dp(activity, 8f), paddingRight, ThemeUi.dp(activity, 8f))
        layoutParams.height = -2
    }
    val rules = mutableListOf<Pair<EditText, EditText>>()
    val ruleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun addRule() {
        val find = ThemeUi.input(activity, palette, t("tools.find"))
        val replacement = ThemeUi.input(activity, palette, t("tools.replacement"))
        val rule = find to replacement
        val row = ThemeUi.row(activity)
        row.addView(find, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ThemeUi.dp(activity, 6f) })
        row.addView(replacement, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ThemeUi.dp(activity, 6f) })
        row.addView(ThemeUi.button(activity, palette, "×") {
            rules.remove(rule)
            ruleList.removeView(row)
            if (rules.isEmpty()) addRule()
        }.apply { contentDescription = t("remove") })
        rules.add(rule)
        ruleList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ThemeUi.dp(activity, 6f) })
    }
    addRule()
    val output = ThemeUi.text(this, "", 14f, palette).apply { setTextIsSelectable(true) }
    val copy = ThemeUi.button(this, palette, t("tools.copy")) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(t("tools.result"), output.text))
        if (Build.VERSION.SDK_INT < 33) toast(t("tools.copied"))
    }.apply { isEnabled = false }
    val actions = ThemeUi.row(this).apply {
        addView(ThemeUi.button(activity, palette, t("tools.rule.add")) { addRule() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ThemeUi.dp(activity, 6f) })
        addView(ThemeUi.button(activity, palette, t("tools.replace.all"), ThemeUi.ROLE_ACCENT_FILL) {
            val values = rules.map { (find, replacement) -> find.text.toString() to replacement.text.toString() }
                .filter { (find, _) -> find.isNotEmpty() }
            if (values.isEmpty()) {
                toast(t("tools.rule.empty"))
            } else {
                output.text = values.fold(source.text.toString()) { text, (find, replacement) -> text.replace(find, replacement) }
                copy.isEnabled = true
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    return ScrollView(this).apply {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            addView(source)
            addView(ThemeUi.text(activity, t("tools.replace.order"), 12f, palette, role = ThemeUi.ROLE_SUB))
            addView(ruleList)
            addView(actions)
            addView(ThemeUi.text(activity, t("tools.result"), 14f, palette, bold = true))
            addView(output)
            addView(copy)
        })
    }
}
