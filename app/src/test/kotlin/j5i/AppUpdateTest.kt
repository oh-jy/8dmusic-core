package j5i.spatial8d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.security.MessageDigest

class AppUpdateTest {
    private val digest = "a".repeat(64)
    private fun release(url: String = "https://github.com/oh-jy/8dmusic-core/releases/download/v2.19.0/app-release-no-asr.apk", hash: String = "sha256:$digest") =
        """{"tag_name":"mainv2-v2.19.0","body":"更新说明","assets":[{"name":"app-release-no-asr.apk","browser_download_url":"$url","digest":"$hash"}]}"""

    @Test fun comparesNumericVersions() {
        assertTrue(isNewerRelease("2.19.0", "2.9.9"))
        assertFalse(isNewerRelease("2.18.7", "2.18.7"))
        assertFalse(isNewerRelease("2.17.9", "2.18.0"))
    }

    @Test fun matchesVariantAndRequiresTrustedAsset() {
        val result = parseAppRelease(release(), false)
        assertEquals("2.19.0", result.version)
        assertEquals(digest, result.sha256)
        assertNull(parseAppRelease(release(), true).apkUrl)
        assertFailsWith<IllegalArgumentException> { parseAppRelease(release(url = "https://example.com/app.apk"), false) }
        assertFailsWith<IllegalArgumentException> { parseAppRelease(release(hash = ""), false) }
    }

    @Test fun betaUsesFixedTagAndReleaseTitle() {
        val beta = release().replace("\"tag_name\":\"mainv2-v2.19.0\"", "\"tag_name\":\"beta-latest\",\"name\":\"测试版 v2.19.0\",\"prerelease\":true")
        assertEquals("2.19.0", parseAppRelease(beta, false).version)
        assertFailsWith<IllegalArgumentException> { parseAppRelease(beta.replace("\"prerelease\":true", "\"prerelease\":false"), false) }
    }

    @Test fun cacheRequiresMatchingCompleteContent() {
        val file = File.createTempFile("update-test", ".apk")
        try {
            file.writeText("cached apk")
            val hash = MessageDigest.getInstance("SHA-256").digest("cached apk".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertTrue(validUpdateCache(file, hash))
            file.appendText("modified")
            assertFalse(validUpdateCache(file, hash))
            assertFalse(validUpdateCache(file, digest))
        } finally {
            file.delete()
        }
    }
}
