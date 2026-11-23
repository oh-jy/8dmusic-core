package j5i.spatial8d.provider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderMvCacheTest {
    @Test fun copiesMediaOnceAndRejectsTruncatedOrCancelledDownloads() {
        val bytes = ByteArray(150000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyMvMedia(ByteArrayInputStream(bytes), output, bytes.size.toLong(), { true }) { _, _ -> })
        assertContentEquals(bytes, output.toByteArray())
        assertFailsWith<IllegalStateException> {
            copyMvMedia(ByteArrayInputStream(bytes), ByteArrayOutputStream(), bytes.size + 1L, { true }) { _, _ -> }
        }
        val cancelled = ByteArrayOutputStream()
        assertFailsWith<IllegalStateException> {
            copyMvMedia(ByteArrayInputStream(bytes), cancelled, bytes.size.toLong(), { false }) { _, _ -> }
        }
        assertEquals(0, cancelled.size())
    }
}
