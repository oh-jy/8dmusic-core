package com.j5i.spatial8d;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 各音源登录 Cookie 的归一化、登录态标记与官方登录页配置。 */
final class LoginCookies {
    private static final String[] ATTRIBUTES = {
        "path", "domain", "expires", "max-age", "secure", "httponly", "samesite"
    };

    private LoginCookies() {}

    /** 去掉 Cookie 属性和换行，合并成请求可直接使用的 name=value; 串。 */
    static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.replace("\uFEFF", "").trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("cookie:")) {
            value = value.substring(7).trim();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : value.split(";|\\r?\\n")) {
            String item = pair.trim();
            int at = item.indexOf('=');
            if (at <= 0) continue;
            String key = item.substring(0, at).trim();
            String cookieValue = item.substring(at + 1).trim();
            if (key.isEmpty() || isAttribute(key)) continue;
            fields.put(key, cookieValue);
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    static boolean valid(String provider, String cookie) {
        if (cookie == null || cookie.isEmpty()) return false;
        switch (provider) {
            case "qq":
                return hasField(cookie, "qqmusic_key")
                    && (hasField(cookie, "wxuin") || hasField(cookie, "uin"));
            case "netease":
                return hasField(cookie, "MUSIC_U");
            case "bilibili":
                return hasField(cookie, "SESSDATA");
            case "kuwo":
                return hasField(cookie, "kw_token");
            case "kugou":
                return hasField(cookie, "KugooID") || hasField(cookie, "KugooSID");
            default:
                return false;
        }
    }

    static String fileName(String provider) {
        return provider + "_cookie.txt";
    }

    static String loginUrl(String provider) {
        switch (provider) {
            case "netease":
                return "https://music.163.com/#/login";
            case "bilibili":
                return "https://passport.bilibili.com/login?goto=https%3A%2F%2Fwww.bilibili.com%2F";
            case "kuwo":
                return "https://www.kuwo.cn/login";
            case "kugou":
                // 账号 SSO 页在部分网络不可达，官方首页内的登录浮层覆盖扫码与手机号登录。
                return "https://www.kugou.com/";
            default:
                return "";
        }
    }

    static String cookieDomain(String provider) {
        switch (provider) {
            case "netease": return "https://music.163.com";
            case "bilibili": return "https://www.bilibili.com";
            case "kuwo": return "https://www.kuwo.cn";
            case "kugou": return "https://www.kugou.com";
            default: return "";
        }
    }

    static String marker(String provider) {
        switch (provider) {
            case "netease": return "MUSIC_U=";
            case "bilibili": return "SESSDATA=";
            case "kuwo": return "kw_token=";
            case "kugou": return "KugooID=";
            default: return "";
        }
    }

    static String markerHint(String provider) {
        switch (provider) {
            case "netease": return "必须包含 MUSIC_U";
            case "bilibili": return "必须包含 SESSDATA";
            case "kuwo": return "必须包含 kw_token";
            case "kugou": return "必须包含 KugooID 或 KugooSID";
            case "qq": return "必须包含 qqmusic_key，以及 wxuin 或 uin";
            default: return "";
        }
    }

    static String fieldValue(String cookie, String field) {
        for (String pair : cookie.split(";")) {
            int at = pair.indexOf('=');
            if (at > 0 && pair.substring(0, at).trim().equals(field)) {
                return pair.substring(at + 1).trim();
            }
        }
        return "";
    }

    private static boolean hasField(String cookie, String field) {
        for (String pair : cookie.split(";")) {
            int at = pair.indexOf('=');
            if (at > 0 && pair.substring(0, at).trim().equalsIgnoreCase(field)) return true;
        }
        return false;
    }

    private static boolean isAttribute(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String attribute : ATTRIBUTES) {
            if (attribute.equals(lower)) return true;
        }
        return false;
    }
}
