package com.j5i.spatial8d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QqCookiesTest {
    @Test
    fun normalizesPastedCookieAndDropsSetCookieAttributes() {
        val cookie = QqCookies.normalize(
            """
            Cookie: qqmusic_key=secret; wxuin=10001;
            Path=/; Domain=.y.qq.com; Secure; HttpOnly
            """.trimIndent()
        )

        assertEquals("qqmusic_key=secret; wxuin=10001", cookie)
        assertTrue(QqCookies.valid(cookie))
    }

    @Test
    fun rejectsCookieWithoutSessionAndAccountKeys() {
        assertFalse(QqCookies.valid(QqCookies.normalize("other=1; xwxuin=10001")))
        assertFalse(QqCookies.valid(QqCookies.normalize("qqmusic_key=secret; other=1")))
    }
}
