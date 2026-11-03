package com.j5i.spatial8d;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
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
    private static final String COOKIE_FILE = "qq_cookie.txt";
    private static final String SESSION_PREFS = "online_provider_sessions";
    private static final int PICK_TREE = 8142;
    private static String cookie = "";
    private static final Map<String, String> providerCookies = new HashMap<>();
    private static WebView loginView;
    private static Activity pendingActivity;
    private static Callback pendingCallback;
    private static Uri persistentTree;

    private MusicSourceApi() {}

    public static void request(Activity activity, String url, String method, String body,
                               boolean qq, Callback callback) {
        request(activity, url, method, body, qq ? "qq" : "", callback);
    }

    /** Provider-scoped sessions: no provider can accidentally consume another site's cookies. */
    public static void request(Activity activity, String url, String method, String body,
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

    private static String providerCookie(Activity activity, String provider) {
        if (provider == null || provider.isEmpty()) return "";
        if ("qq".equals(provider)) { restore(activity); synchronized (LOCK) { return cookie; } }
        synchronized (LOCK) {
            String value = providerCookies.get(provider);
            if (value == null) {
                value = activity.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).getString(provider, "");
                providerCookies.put(provider, value);
            }
            return value;
        }
    }

    private static void saveResponseCookies(Activity activity, String provider, Map<String, java.util.List<String>> headers) {
        if (!"netease".equals(provider)) return;
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
        saveProviderCookie(activity, provider, result.toString());
    }

    private static void saveProviderCookie(Activity activity, String provider, String value) {
        synchronized (LOCK) { providerCookies.put(provider, value); }
        activity.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit().putString(provider, value).apply();
    }

    /** Only session state is returned; cookies and verification codes never reach UI or logs. */
    public static String sessionInfo(Activity activity, String provider) {
        if ("qq".equals(provider)) return sessionInfo();
        String value = providerCookie(activity, provider);
        boolean loggedIn = "netease".equals(provider) && value.contains("MUSIC_U=");
        try { return new JSONObject().put("loggedIn", loggedIn).put("provider", provider).toString(); }
        catch (Exception e) { return "{\"loggedIn\":false}"; }
    }

    public static void logout(Activity activity, String provider) {
        if ("qq".equals(provider)) { logout(activity); return; }
        saveProviderCookie(activity, provider, "");
    }

    /** WeChat authorizes on its official page; QQ Music then sets its own session cookies. */
    public static void login(Activity activity, Callback callback) {
        MAIN.post(() -> {
            try {
                if (persistentTree == null && treeUri(activity) == null) {
                    pendingActivity = activity;
                    pendingCallback = callback;
                    activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_TREE);
                    return;
                }
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
                        String value = CookieManager.getInstance().getCookie("https://y.qq.com/");
                        if (value != null && value.contains("qqmusic_key=")
                                && (value.contains("wxuin=") || value.contains("uin="))) {
                            synchronized (LOCK) { cookie = value; }
                            saveCookie(activity, value);
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

    public static void logout(Activity activity) {
        synchronized (LOCK) { cookie = ""; }
        try { new File(activity.getFilesDir(), COOKIE_FILE).delete(); } catch (Exception ignored) {}
        try {
            Uri tree = persistentTree != null ? persistentTree : treeUri(activity);
            if (tree != null) {
                Uri file = persistentDocument(activity);
                if (file != null) DocumentsContract.deleteDocument(activity.getContentResolver(), file);
            }
        } catch (Exception ignored) {}
        MAIN.post(() -> CookieManager.getInstance().removeAllCookies(null));
    }

    public static void restore(Activity activity) {
        try {
            if (persistentTree == null) persistentTree = treeUri(activity);
            File f = new File(activity.getFilesDir(), COOKIE_FILE);
            if (!f.exists()) {
                String saved = readPersistent(activity);
                if (saved.length() > 0) saveCookie(activity, saved);
            }
            if (!f.exists()) return;
            String value = new String(readBytes(new FileInputStream(f)), StandardCharsets.UTF_8);
            if (value.contains("qqmusic_key=")) synchronized (LOCK) { cookie = value; }
        } catch (Exception ignored) {}
    }

    /** System picker grants a durable URI which survives uninstall/reinstall. */
    public static boolean onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
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

    private static Uri treeUri(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String saved = prefs.getString(TREE, "");
            return saved.length() == 0 ? null : Uri.parse(saved);
        } catch (Exception e) { return null; }
    }

    private static String readPersistent(Context context) {
        if (persistentTree == null) return "";
        try {
            Uri file = persistentDocument(context);
            if (file == null) return "";
            InputStream in = context.getContentResolver().openInputStream(file);
            return in == null ? "" : new String(readBytes(in), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return "";
    }

    private static Uri persistentDocument(Context context) {
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
        if (persistentTree == null) return;
        try {
            Uri file = persistentDocument(context);
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
        writePersistent(context, value);
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
