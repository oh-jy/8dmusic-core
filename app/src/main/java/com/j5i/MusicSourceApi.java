package com.j5i.spatial8d;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Map;

/** Android HTTP and WeChat-authorized QQ Music session for online tracks. */
public final class MusicSourceApi {
    public interface Callback { void done(String result); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final String PREFS = "qq_music_session";
    private static final String TREE = "persistent_tree";
    private static final String COOKIE_DOCUMENT = "persistent_cookie_document";
    private static final String COOKIE_FILE = "qq_cookie.txt";
    private static final String SESSION_PREFS = "online_provider_sessions";
    private static final String LOGIN_RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/8d/login/";
    private static final String[] LOGIN_PROVIDERS = {"qq", "netease", "kugou", "kuwo", "bilibili"};
    private static final int PICK_TREE = 8142;
    private static final int PICK_COOKIE_FILE = 8143;
    private static String cookie = "";
    private static final Map<String, String> providerCookies = new HashMap<>();
    private static WebView loginView;
    private static Activity pendingActivity;
    private static Callback pendingCallback;
    private static Callback pendingImportCallback;
    private static Uri persistentTree;
    private static Uri persistentCookieDocument;
    private static String importProvider = "qq";
    private static volatile boolean bootstrapped = false;

    private MusicSourceApi() {}

    public static void request(Context activity, String url, String method, String body,
                               boolean qq, Callback callback) {
        request(activity, url, method, body, qq ? "qq" : "", callback);
    }

    /** Provider-scoped sessions: no provider can accidentally consume another site's cookies. */
    public static void request(Context activity, String url, String method, String body,
                               String provider, Callback callback) {
        new Thread(() -> {
            String result;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                c.setRequestMethod(method);
                if ("netease".equals(provider)) c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36");
                c.setRequestProperty("Accept", "application/json,text/plain,*/*");
                String sessionCookie = providerCookie(activity, provider);
                if ("qq".equals(provider)) {
                    c.setRequestProperty("Referer", "https://y.qq.com/");
                    c.setRequestProperty("Origin", "https://y.qq.com");
                }
                if ("netease".equals(provider)) c.setRequestProperty("Referer", "https://music.163.com/");
                if ("kuwo".equals(provider)) c.setRequestProperty("Referer", "https://www.kuwo.cn/");
                if ("kugou".equals(provider)) c.setRequestProperty("Referer", "https://www.kugou.com/");
                if ("bilibili".equals(provider)) c.setRequestProperty("Referer", "https://www.bilibili.com/");
                if (!sessionCookie.isEmpty()) c.setRequestProperty("Cookie", sessionCookie);
                if (body != null && !body.isEmpty()) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "qq".equals(provider)
                        ? "application/json;charset=UTF-8" : "application/x-www-form-urlencoded;charset=UTF-8");
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = in == null ? "" : readAll(in);
                saveResponseCookies(activity, provider, c.getHeaderFields());
                JSONObject r = new JSONObject();
                r.put("status", status);
                r.put("body", text);
                result = r.toString();
            } catch (Exception e) {
                result = error("HTTP", e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
            String out = result;
            MAIN.post(() -> callback.done(out));
        }).start();
    }

    private static String providerCookie(Context activity, String provider) {
        if (provider == null || provider.isEmpty()) return "";
        if ("qq".equals(provider)) { if (activity instanceof Activity) restore((Activity) activity); synchronized (LOCK) { return cookie; } }
        synchronized (LOCK) {
            String value = providerCookies.get(provider);
            if (value == null) {
                value = activity.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).getString(provider, "");
                providerCookies.put(provider, value);
            }
            return value;
        }
    }

    private static void saveResponseCookies(Context activity, String provider, Map<String, java.util.List<String>> headers) {
        if (!"netease".equals(provider) && !"bilibili".equals(provider)) return;
        java.util.List<String> values = headers.get("Set-Cookie");
        if (values == null) values = headers.get("set-cookie");
        if (values == null) return;
        Map<String, String> fields = new HashMap<>();
        for (String item : providerCookie(activity, provider).split(";")) {
            int at = item.indexOf('='); if (at > 0) fields.put(item.substring(0, at).trim(), item.substring(at + 1));
        }
        for (String value : values) {
            String pair = value.split(";", 2)[0]; int at = pair.indexOf('=');
            if (at > 0) fields.put(pair.substring(0, at).trim(), pair.substring(at + 1));
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        String merged = result.toString();
        saveProviderCookie(activity, provider, merged);
        // 短信登录等非 WebView 流程拿到完整会话后，同样落盘到公共登录目录。
        if (LoginCookies.valid(provider, merged) && activity instanceof Activity) writeLoginFile((Activity) activity, provider, merged);
    }

    private static void saveProviderCookie(Context activity, String provider, String value) {
        synchronized (LOCK) { providerCookies.put(provider, value); }
        activity.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit().putString(provider, value).apply();
    }

    /** Only session state is returned; cookies and verification codes never reach UI or logs. */
    public static String sessionInfo(Context activity, String provider) {
        if ("qq".equals(provider)) return sessionInfo();
        String value = providerCookie(activity, provider);
        boolean loggedIn = LoginCookies.valid(provider, value);
        try { return new JSONObject().put("loggedIn", loggedIn).put("provider", provider).toString(); }
        catch (Exception e) { return "{\"loggedIn\":false}"; }
    }

    public static void logout(Activity activity, String provider) {
        saveProviderCookie(activity, provider, "");
        deleteLoginFile(activity, provider);
        if ("qq".equals(provider)) {
            synchronized (LOCK) { cookie = ""; }
            try { new File(activity.getFilesDir(), COOKIE_FILE).delete(); } catch (Exception ignored) {}
        }
    }

    /** Raw provider cookie for binary downloads (e.g. DASH audio CDN checks). */
    public static String sessionCookie(Context activity, String provider) {
        return providerCookie(activity, provider);
    }

    /** Persists cookies captured after an official WebView login for a provider. */
    public static void saveSession(Context activity, String provider, String cookie) {
        saveProviderCookie(activity, provider, cookie == null ? "" : cookie);
    }

    /**
     * Generic official-page login: scan-code, SMS and password flows all stay inside
     * the site's own WebView, including its risk-control challenges. Success is
     * detected solely by the marker cookie appearing; cookie values never reach UI.
     */
    public static void loginWeb(Activity activity, String provider, String startUrl,
                                String cookieDomain, String cookieMarker, Callback callback) {
        MAIN.post(() -> {
            try {
                closeLogin();
                CookieManager cookies = CookieManager.getInstance();
                cookies.setAcceptCookie(true);
                WebView view = new WebView(activity);
                WebSettings s = view.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setDatabaseEnabled(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
                final boolean[] done = {false};
                view.setWebChromeClient(new WebChromeClient());
                view.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView web, String url) {
                        String value = CookieManager.getInstance().getCookie(cookieDomain);
                        if (!done[0] && value != null && value.contains(cookieMarker)) {
                            String normalized = LoginCookies.normalize(value);
                            if (!LoginCookies.valid(provider, normalized)) return;
                            // 酷我的 kw_token 可能在未登录时就存在，需要再向服务端确认。
                            if ("kuwo".equals(provider) && Boolean.FALSE.equals(validateSession(activity, provider, normalized))) {
                                return;
                            }
                            done[0] = true;
                            saveProviderCookie(activity, provider, normalized);
                            writeLoginFile(activity, provider, normalized);
                            closeLogin();
                            callback.done("{\"ok\":true}");
                        }
                    }
                });
                view.setOnKeyListener((v, keyCode, event) -> {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                        if (view.canGoBack()) { view.goBack(); return true; }
                        if (!done[0]) {
                            done[0] = true;
                            closeLogin();
                            callback.done(error("LOGIN", "已取消登录"));
                        }
                        return true;
                    }
                    return false;
                });
                loginView = view;
                activity.addContentView(view, new android.view.ViewGroup.LayoutParams(-1, -1));
                view.loadUrl(startUrl);
            } catch (Exception e) {
                closeLogin();
                callback.done(error("LOGIN", e.getMessage()));
            }
        });
    }

    /** 按 LoginCookies 内置的官方登录页配置打开 WebView 登录。 */
    public static void loginWithWeb(Activity activity, String provider, Callback callback) {
        loginWeb(activity, provider, LoginCookies.loginUrl(provider),
            LoginCookies.cookieDomain(provider), LoginCookies.marker(provider), callback);
    }

    public static String cookieMarkerHint(String provider) {
        return LoginCookies.markerHint(provider);
    }

    /** WeChat authorizes on its official page; QQ Music then sets its own session cookies. */
    public static void login(Activity activity, Callback callback) {
        MAIN.post(() -> {
            try {
                closeLogin();
                CookieManager cookies = CookieManager.getInstance();
                cookies.setAcceptCookie(true);
                cookies.removeAllCookies(null);
                loginView = new WebView(activity);
                WebSettings s = loginView.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36");
                CookieManager.getInstance().setAcceptThirdPartyCookies(loginView, true);
                loginView.setWebChromeClient(new WebChromeClient());
                loginView.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        String value = QqCookies.normalize(CookieManager.getInstance().getCookie("https://y.qq.com/"));
                        if (QqCookies.valid(value)) {
                            synchronized (LOCK) { cookie = value; }
                            saveProviderCookie(activity, "qq", value);
                            writeLoginFile(activity, "qq", value);
                            try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), COOKIE_FILE))) {
                                out.write(value.getBytes(StandardCharsets.UTF_8));
                            } catch (Exception ignored) {}
                            closeLogin();
                            callback.done("{\"ok\":true}");
                        }
                    }
                });
                activity.addContentView(loginView, new android.view.ViewGroup.LayoutParams(-1, -1));
                String returnUrl = "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl="
                        + URLEncoder.encode("https://y.qq.com/", "UTF-8");
                String url = "https://open.weixin.qq.com/connect/qrconnect?appid=wx48db31d50e334801"
                        + "&redirect_uri=" + URLEncoder.encode(returnUrl, "UTF-8")
                        + "&response_type=code&scope=snsapi_login&state=8dmusic";
                loginView.loadUrl(url);
            } catch (Exception e) {
                closeLogin();
                callback.done(error("LOGIN", e.getMessage()));
            }
        });
    }

    /** Restores the plain-text Cookie backup created by a previous install. */
    public static void pickCookieFile(Activity activity, String provider, Callback callback) {
        MAIN.post(() -> {
            pendingImportCallback = callback;
            importProvider = provider == null || provider.isEmpty() ? "qq" : provider;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            activity.startActivityForResult(intent, PICK_COOKIE_FILE);
        });
    }

    /** Allows restoring a session when the user only has the Cookie string. */
    public static void saveCookieText(Activity activity, String provider, String raw, Callback callback) {
        new Thread(() -> {
            try {
                String key = provider == null || provider.isEmpty() ? "qq" : provider;
                String value = LoginCookies.normalize(raw);
                if (!LoginCookies.valid(key, value)) {
                    throw new IllegalArgumentException("Cookie 无效：" + LoginCookies.markerHint(key));
                }
                saveProviderCookie(activity, key, value);
                writeLoginFile(activity, key, value);
                if ("qq".equals(key)) {
                    synchronized (LOCK) { cookie = value; }
                    try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), COOKIE_FILE))) {
                        out.write(value.getBytes(StandardCharsets.UTF_8));
                    } catch (Exception ignored) {}
                }
                MAIN.post(() -> callback.done("{\"ok\":true}"));
            } catch (Exception e) {
                MAIN.post(() -> callback.done(error("LOGIN", e.getMessage())));
            }
        }).start();
    }

    public static void logout(Activity activity) {
        synchronized (LOCK) { cookie = ""; }
        try { new File(activity.getFilesDir(), COOKIE_FILE).delete(); } catch (Exception ignored) {}
        deleteLoginFile(activity, "qq");
        try {
            Uri document = persistentCookieDocument != null ? persistentCookieDocument : cookieDocumentUri(activity);
            if (document != null) {
                try {
                    DocumentsContract.deleteDocument(activity.getContentResolver(), document);
                } finally {
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(COOKIE_DOCUMENT).apply();
                }
                persistentCookieDocument = null;
            }
        } catch (Exception ignored) {}
        try {
            Uri tree = persistentTree != null ? persistentTree : treeUri(activity);
            if (tree != null) {
                Uri file = treeDocument(activity);
                if (file != null) DocumentsContract.deleteDocument(activity.getContentResolver(), file);
            }
        } catch (Exception ignored) {}
        MAIN.post(() -> CookieManager.getInstance().removeAllCookies(null));
    }

    public static void restore(Activity activity) {
        try {
            // 新机制：公共下载目录 Download/8d/login 为权威来源。
            String published = LoginCookies.normalize(readLoginFile(activity, "qq"));
            if (QqCookies.valid(published)) {
                synchronized (LOCK) { cookie = published; }
                saveProviderCookie(activity, "qq", published);
                try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), COOKIE_FILE))) {
                    out.write(published.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
                return;
            }
            if (persistentTree == null) persistentTree = treeUri(activity);
            if (persistentCookieDocument == null) persistentCookieDocument = cookieDocumentUri(activity);
            File f = new File(activity.getFilesDir(), COOKIE_FILE);
            if (!f.exists()) {
                String saved = readPersistent(activity);
                saved = QqCookies.normalize(saved);
                if (QqCookies.valid(saved)) {
                    synchronized (LOCK) { cookie = saved; }
                    saveCookie(activity, saved);
                }
            }
            if (!f.exists()) return;
            String value = QqCookies.normalize(readAll(new FileInputStream(f)));
            if (QqCookies.valid(value)) synchronized (LOCK) { cookie = value; }
        } catch (Exception ignored) {}
    }

    /** System picker grants a durable URI which survives uninstall/reinstall. */
    // The picker flags are masked to the only two grant flags accepted by the resolver.
    @SuppressLint("WrongConstant")
    public static boolean onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_COOKIE_FILE) return onCookieFileResult(activity, resultCode, data);
        if (requestCode != PICK_TREE) return false;
        try {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                activity.getContentResolver().takePersistableUriPermission(tree, flags);
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TREE, tree.toString()).apply();
                persistentTree = tree;
                synchronized (LOCK) {
                    if (!cookie.isEmpty()) writePersistent(activity, cookie);
                }
                if (pendingActivity != null && pendingCallback != null) {
                    Activity next = pendingActivity;
                    Callback callback = pendingCallback;
                    pendingActivity = null;
                    pendingCallback = null;
                    login(next, callback);
                }
            } else if (pendingCallback != null) {
                pendingCallback.done(error("LOGIN", "未选择登录文件保存目录"));
                pendingActivity = null;
                pendingCallback = null;
            }
        } catch (Exception e) {
            if (pendingCallback != null) pendingCallback.done(error("LOGIN", "保存目录授权失败"));
            pendingActivity = null;
            pendingCallback = null;
        }
        return true;
    }

    // Preserve exactly the read/write grants returned by the document picker.
    @SuppressLint("WrongConstant")
    private static boolean onCookieFileResult(Activity activity, int resultCode, Intent data) {
        try {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri document = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try { activity.getContentResolver().takePersistableUriPermission(document, flags); }
                catch (Exception ignored) {}
                String value = LoginCookies.normalize(readAll(activity.getContentResolver().openInputStream(document)));
                if (!LoginCookies.valid(importProvider, value)) {
                    throw new IllegalArgumentException("登录文件无效：" + LoginCookies.markerHint(importProvider));
                }
                saveProviderCookie(activity, importProvider, value);
                writeLoginFile(activity, importProvider, value);
                if ("qq".equals(importProvider)) {
                    synchronized (LOCK) { cookie = value; }
                    try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), COOKIE_FILE))) {
                        out.write(value.getBytes(StandardCharsets.UTF_8));
                    } catch (Exception ignored) {}
                }
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(COOKIE_DOCUMENT, document.toString()).apply();
                persistentCookieDocument = document;
                if (pendingImportCallback != null) pendingImportCallback.done("{\"ok\":true}");
            } else if (pendingImportCallback != null) {
                pendingImportCallback.done(error("LOGIN", "未选择登录文件"));
            }
        } catch (Exception e) {
            if (pendingImportCallback != null) {
                pendingImportCallback.done(error("LOGIN", e.getMessage() == null ? "登录文件读取失败" : e.getMessage()));
            }
        }
        pendingImportCallback = null;
        return true;
    }

    private static Uri treeUri(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String saved = prefs.getString(TREE, "");
            return saved.length() == 0 ? null : Uri.parse(saved);
        } catch (Exception e) { return null; }
    }

    private static Uri cookieDocumentUri(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String saved = prefs.getString(COOKIE_DOCUMENT, "");
            return saved.length() == 0 ? null : Uri.parse(saved);
        } catch (Exception e) { return null; }
    }

    private static String readPersistent(Context context) {
        if (persistentCookieDocument != null) {
            try {
                return readAll(context.getContentResolver().openInputStream(persistentCookieDocument));
            } catch (Exception ignored) {}
        }
        if (persistentTree == null) return "";
        try {
            Uri file = treeDocument(context);
            if (file == null) return "";
            return readAll(context.getContentResolver().openInputStream(file));
        } catch (Exception ignored) {}
        return "";
    }

    private static Uri treeDocument(Context context) {
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(persistentTree,
                DocumentsContract.getTreeDocumentId(persistentTree));
            android.database.Cursor cursor = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null);
            if (cursor == null) return null;
            try (cursor) {
                while (cursor.moveToNext()) {
                    if (COOKIE_FILE.equals(cursor.getString(1))) {
                        return DocumentsContract.buildDocumentUriUsingTree(persistentTree, cursor.getString(0));
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void writePersistent(Context context, String value) {
        if (persistentCookieDocument != null) {
            try (OutputStream out = context.getContentResolver().openOutputStream(persistentCookieDocument, "wt")) {
                if (out != null) out.write(value.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
            return;
        }
        if (persistentTree == null) return;
        try {
            Uri file = treeDocument(context);
            if (file == null) {
                Uri tree = DocumentsContract.buildDocumentUriUsingTree(persistentTree,
                    DocumentsContract.getTreeDocumentId(persistentTree));
                file = DocumentsContract.createDocument(context.getContentResolver(), tree, "text/plain", COOKIE_FILE);
            }
            if (file == null) return;
            try (OutputStream out = context.getContentResolver().openOutputStream(file, "wt")) {
                if (out != null) out.write(value.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public static String sessionInfo() {
        try {
            JSONObject r = new JSONObject();
            synchronized (LOCK) {
                Map<String, Boolean> fields = new HashMap<>();
                for (String item : cookie.split(";")) {
                    int separator = item.indexOf('=');
                    if (separator > 0 && separator + 1 < item.length()) {
                        fields.put(item.substring(0, separator).trim(), true);
                    }
                }
                boolean loggedIn = fields.containsKey("qqmusic_key")
                        && (fields.containsKey("wxuin") || fields.containsKey("uin"));
                r.put("loggedIn", loggedIn);
                r.put("loginType", fields.containsKey("wxuin") ? "wechat" : "unknown");
                // Diagnostic names only; never expose session values to logs or the UI.
                r.put("fields", new JSONObject(fields));
                r.put("cookie", cookie);
            }
            return r.toString();
        } catch (Exception e) { return "{\"loggedIn\":false}"; }
    }

    private static void closeLogin() {
        if (loginView == null) return;
        try {
            android.view.ViewParent p = loginView.getParent();
            if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(loginView);
            loginView.destroy();
        } catch (Exception ignored) {}
        loginView = null;
    }

    private static void saveCookie(Context context, String value) {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), COOKIE_FILE))) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        if (persistentTree == null) persistentTree = treeUri(context);
        if (persistentCookieDocument == null) persistentCookieDocument = cookieDocumentUri(context);
        writePersistent(context, value);
    }

    // ------------------------------------------------------------------
    // 公共下载目录 Download/8d/login 登录文件（Android 10+ 走 MediaStore）
    // ------------------------------------------------------------------

    /** 启动时自动恢复公共登录目录中的会话；失效文件只在服务端明确拒绝时删除。 */
    public static void bootstrap(Activity activity) {
        if (bootstrapped) return;
        bootstrapped = true;
        new Thread(() -> {
            for (String provider : LOGIN_PROVIDERS) {
                try {
                    String value = LoginCookies.normalize(readLoginFile(activity, provider));
                    if (value.isEmpty()) continue;
                    if (!LoginCookies.valid(provider, value)) {
                        deleteLoginFile(activity, provider);
                        continue;
                    }
                    saveProviderCookie(activity, provider, value);
                    if ("qq".equals(provider)) {
                        synchronized (LOCK) { cookie = value; }
                        try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), COOKIE_FILE))) {
                            out.write(value.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {}
                    }
                    Boolean alive = validateSession(activity, provider, value);
                    if (Boolean.FALSE.equals(alive)) {
                        saveProviderCookie(activity, provider, "");
                        if ("qq".equals(provider)) synchronized (LOCK) { cookie = ""; }
                        deleteLoginFile(activity, provider);
                    }
                } catch (Exception ignored) {
                    // 单平台恢复失败不影响其它平台；网络异常时保留文件等下次判定。
                }
            }
        }, "login-bootstrap").start();
    }

    /**
     * 服务端校验登录态。返回 null 表示网络/风控原因无法判定，调用方必须保留会话；
     * 只有明确未登录才返回 false 并删除登录文件。
     */
    private static Boolean validateSession(Activity activity, String provider, String cookieValue) {
        try {
            switch (provider) {
                case "netease": {
                    JSONObject r = new JSONObject(httpGet(
                        "https://music.163.com/api/nuser/account/get", cookieValue,
                        "https://music.163.com/"));
                    if (r.optInt("code") != 200) return null;
                    return r.optJSONObject("profile") != null;
                }
                case "bilibili": {
                    JSONObject r = new JSONObject(httpGet(
                        "https://api.bilibili.com/x/web-interface/nav", cookieValue,
                        "https://www.bilibili.com/"));
                    if (r.optInt("code") != 0) return null;
                    JSONObject data = r.optJSONObject("data");
                    return data != null && data.optBoolean("isLogin");
                }
                case "kuwo": {
                    String token = LoginCookies.fieldValue(cookieValue, "kw_token");
                    HttpURLConnection c = openJsonConnection(
                        "https://www.kuwo.cn/api/www/kuwo/user/getUserInfo", cookieValue,
                        "https://www.kuwo.cn/");
                    c.setRequestProperty("csrf", token);
                    String body = execute(c);
                    if (body.contains("illegal") || body.contains("非法")) return null;
                    JSONObject r = new JSONObject(body);
                    return r.optBoolean("success");
                }
                case "kugou":
                case "qq":
                default:
                    // 酷狗 WAF 无法稳定区分过期与拦截，QQ 以本地标记为准，均 fail-open。
                    return Boolean.TRUE;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String httpGet(String url, String cookieValue, String referer) throws Exception {
        return execute(openJsonConnection(url, cookieValue, referer));
    }

    private static HttpURLConnection openJsonConnection(String url, String cookieValue, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Referer", referer);
        if (cookieValue != null && !cookieValue.isEmpty()) c.setRequestProperty("Cookie", cookieValue);
        return c;
    }

    private static String execute(HttpURLConnection c) throws Exception {
        try {
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return in == null ? "" : readAll(in);
        } finally {
            c.disconnect();
        }
    }

    public static void writeLoginFile(Activity activity, String provider, String value) {
        if (value == null || value.isEmpty() || !LoginCookies.valid(provider, value)) return;
        String name = LoginCookies.fileName(provider);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Uri existing = findLoginUri(activity, name);
                if (existing != null) {
                    try (OutputStream out = activity.getContentResolver().openOutputStream(existing, "wt")) {
                        if (out != null) out.write(bytes);
                    }
                    return;
                }
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, LOGIN_RELATIVE_PATH);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = activity.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("无法创建登录文件");
                try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("无法写入登录文件");
                    out.write(bytes);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                activity.getContentResolver().update(uri, values, null, null);
            } catch (Exception e) {
                writeLegacyLoginFile(activity, name, bytes);
            }
        } else {
            writeLegacyLoginFile(activity, name, bytes);
        }
    }

    public static String readLoginFile(Activity activity, String provider) {
        String name = LoginCookies.fileName(provider);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = findLoginUri(activity, name);
            if (uri != null) {
                try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                    if (in != null) return readAll(in);
                } catch (Exception ignored) {}
            }
        }
        File legacy = new File(new File(activity.getFilesDir(), "login"), name);
        if (legacy.exists()) {
            try (FileInputStream in = new FileInputStream(legacy)) {
                return readAll(in);
            } catch (Exception ignored) {}
        }
        return "";
    }

    public static void deleteLoginFile(Activity activity, String provider) {
        String name = LoginCookies.fileName(provider);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = findLoginUri(activity, name);
            if (uri != null) {
                try { activity.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            }
        }
        File legacy = new File(new File(activity.getFilesDir(), "login"), name);
        if (legacy.exists()) legacy.delete();
    }

    private static Uri findLoginUri(Activity activity, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
            + MediaStore.Downloads.RELATIVE_PATH + "=?";
        try (android.database.Cursor cursor = activity.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads._ID},
                selection, new String[]{name, LOGIN_RELATIVE_PATH}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void writeLegacyLoginFile(Activity activity, String name, byte[] bytes) {
        try {
            File dir = new File(activity.getFilesDir(), "login");
            if (!dir.exists() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(bytes);
            }
        } catch (Exception ignored) {}
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream input = in) { return new String(readBytes(input), StandardCharsets.UTF_8); }
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String error(String kind, String message) {
        try {
            return new JSONObject().put("error", kind).put("message", message == null ? "unknown" : message).toString();
        } catch (Exception e) { return "{\"error\":\"HTTP\"}"; }
    }
}
