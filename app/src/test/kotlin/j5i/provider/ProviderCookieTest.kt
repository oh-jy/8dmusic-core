package j5i.spatial8d.provider

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderCookieTest {
    @Test fun acceptsCaseInsensitiveCookieHeader() {
        assertEquals("SESSDATA=value", ProviderCookie.normalize("cookie: SESSDATA=value; Path=/"))
    }
    @Test fun restoresOldCookieFilesWithoutPersistingAttributes() {
        val raw = "\uFEFFCookie: qqmusic_key=secret; wxuin=123\nPath=/; Domain=.qq.com; expires=never"
        val cookie = ProviderCookie.normalize(raw)
        assertEquals("secret", ProviderCookie.field(cookie, "qqmusic_key"))
        assertEquals("123", ProviderCookie.field(cookie, "wxuin"))
        assertEquals("qqmusic_key=secret; wxuin=123", cookie)
    }
}
