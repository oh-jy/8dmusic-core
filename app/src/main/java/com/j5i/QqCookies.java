package com.j5i.spatial8d;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Normalizes user-supplied QQ Music Cookie headers before saving them locally. */
final class QqCookies {
    private static final String[] ATTRIBUTES = {
        "path", "domain", "expires", "max-age", "secure", "httponly", "samesite"
    };

    private QqCookies() {}

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

    static boolean valid(String cookie) {
        boolean sessionKey = false;
        boolean account = false;
        for (String pair : cookie.split(";")) {
            int at = pair.indexOf('=');
            if (at <= 0) continue;
            String key = pair.substring(0, at).trim();
            sessionKey |= "qqmusic_key".equals(key);
            account |= "wxuin".equals(key) || "uin".equals(key);
        }
        return sessionKey && account;
    }

    private static boolean isAttribute(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String attribute : ATTRIBUTES) {
            if (attribute.equals(lower)) return true;
        }
        return false;
    }
}
