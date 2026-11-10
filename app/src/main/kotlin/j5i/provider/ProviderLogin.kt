package j5i.spatial8d.provider

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/** Official-site login only; the plugin receives the resulting cookie, never a WebView. */
internal object ProviderLogin {
    data class Site(val url: String, val cookieUrl: String, val required: Set<String>)

    fun site(id: String): Site? = when {
        id == "platform.qq" -> Site(qqUrl(), "https://y.qq.com/", setOf("qqmusic_key"))
        id == "platform.bilibili" -> Site("https://passport.bilibili.com/login", "https://www.bilibili.com/", setOf("SESSDATA"))
        id == "platform.netease" -> Site("https://music.163.com/#/login", "https://music.163.com/", setOf("MUSIC_U"))
        id == "platform.kugou" -> Site("https://www.kugou.com/", "https://www.kugou.com/", setOf("KugooID"))
        else -> null
    }

    fun open(context: Context, id: String, done: (String?) -> Unit): Dialog? {
        val site = site(id) ?: run { done("此插件未提供受支持的官方网页登录"); return null }
        val manager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val view = WebView(context)
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        var completed = false
        var reportedSaveFailure = false
        var openedAt = 0L
        val content = FrameLayout(context)
        val close = TextView(context).apply {
            text = "×"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription = "关闭登录页面"; setBackgroundColor(0x66000000)
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(view, FrameLayout.LayoutParams(-1, -1))
        content.addView(close, FrameLayout.LayoutParams(56, 56, Gravity.TOP or Gravity.END))
        val finish = TextView(context).apply {
            text = "完成登录"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription = "已在网页完成登录，手动保存凭据"
            setBackgroundColor(0x66000000)
            setPadding(28, 0, 28, 0)
            setOnClickListener {
                val cookie = ProviderCookie.normalize(manager.getCookie(site.cookieUrl).orEmpty())
                if (cookie.isBlank()) { text = "未检测到凭据"; return@setOnClickListener }
                val result = runCatching { ProviderCredentials(context).save(id, cookie); ProviderLoginBackup.save(context, id, cookie) }
                if (result.isSuccess) {
                    completed = true
                    done(null)
                    dialog.dismiss()
                } else {
                    close.text = "!"
                    done("登录成功，但凭据加密保存或备份失败，请重试")
                }
            }
        }
        content.addView(finish, FrameLayout.LayoutParams(-2, 56, Gravity.TOP or Gravity.START))
        val checkCookies = object : Runnable {
            override fun run() {
                if (completed || !dialog.isShowing) return
                val cookie = ProviderCookie.normalize(manager.getCookie(site.cookieUrl).orEmpty())
                val baseValid = site.required.all { ProviderCookie.field(cookie, it).isNotEmpty() }
                val valid = baseValid &&
                    (id != "platform.qq" || ProviderCookie.field(cookie, "wxuin").isNotEmpty() || ProviderCookie.field(cookie, "uin").isNotEmpty())
                if (valid && System.currentTimeMillis() - openedAt >= 1500) {
                    val result = runCatching { ProviderCredentials(context).save(id, cookie); ProviderLoginBackup.save(context, id, cookie) }
                    if (result.isSuccess) {
                        completed = true
                        done(null)
                        dialog.dismiss()
                    } else {
                        close.text = "!"
                        if (!reportedSaveFailure) {
                            reportedSaveFailure = true
                            done("登录成功，但凭据加密保存或备份失败，请重试")
                        }
                    }
                } else view.postDelayed(this, 1000)
            }
        }
        dialog.setContentView(content, ViewGroup.LayoutParams(-1, -1))
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            android.util.Log.i("8DProviderLogin", "dialog dismissed provider=$id completed=$completed")
            view.removeCallbacks(checkCookies); view.destroy()
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP && view.canGoBack()) {
                view.goBack(); true
            } else false
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            if (id == "platform.qq" || id == "platform.kugou")
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36"
        }
        manager.setAcceptThirdPartyCookies(view, true)
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(web: WebView, url: String) {
                view.removeCallbacks(checkCookies)
                view.post(checkCookies)
            }
        }
        dialog.show()
        android.util.Log.i("8DProviderLogin", "dialog shown provider=$id")
        openedAt = System.currentTimeMillis()
        view.loadUrl(site.url)
        return dialog
    }

    private fun qqUrl(): String {
        val returnUrl = "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=" +
            java.net.URLEncoder.encode("https://y.qq.com/", "UTF-8")
        return "https://open.weixin.qq.com/connect/qrconnect?appid=wx48db31d50e334801&redirect_uri=" +
            java.net.URLEncoder.encode(returnUrl, "UTF-8") + "&response_type=code&scope=snsapi_login&state=8dmusic"
    }
}

internal object ProviderCookie {
    private val attributes = setOf("path", "domain", "expires", "max-age", "secure", "httponly", "samesite")

    fun normalize(raw: String): String {
        val trimmed = raw.trim().removePrefix("\uFEFF").trim()
        val value = if (trimmed.startsWith("Cookie:", ignoreCase = true)) trimmed.substring(7).trim() else trimmed
        val fields = linkedMapOf<String, String>()
        value.split(';', '\n', '\r').forEach { part ->
            val at = part.indexOf('=')
            if (at <= 0) return@forEach
            val key = part.substring(0, at).trim()
            if (key.isNotEmpty() && key.lowercase() !in attributes) fields[key] = part.substring(at + 1).trim()
        }
        return fields.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun field(cookie: String, name: String): String = cookie.split(';').firstNotNullOfOrNull {
        val at = it.indexOf('=')
        if (at > 0 && it.substring(0, at).trim() == name) it.substring(at + 1).trim() else null
    } ?: ""
}
