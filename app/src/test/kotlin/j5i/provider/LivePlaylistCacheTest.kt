package j5i.spatial8d.provider

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LivePlaylistCacheTest {
    @TempDir lateinit var directory: Path

    @Test fun savesOnceAndWorksOffline() {
        val file = directory.resolve("catalog.txt").toFile()
        var calls = 0
        assertEquals("央视,#genre#\nCCTV1,https://example.com/live", LivePlaylistCache.get(file) {
            calls++
            "央视,#genre#\nCCTV1,https://example.com/live"
        })
        assertEquals(file.readText(), LivePlaylistCache.get(file) { error("must use saved catalog") })
        assertEquals(1, calls)
    }

    @Test fun failedDownloadDoesNotCreateCache() {
        val file = directory.resolve("catalog.txt").toFile()
        assertFails { LivePlaylistCache.get(file) { "" } }
        assertEquals(false, file.exists())
    }

    @Test fun failedRefreshKeepsSavedCatalog() {
        val file = directory.resolve("catalog.txt").toFile()
        LivePlaylistCache.get(file) { "央视,#genre#\nCCTV1,https://example.com/live" }
        assertFails { LivePlaylistCache.refresh(file) { "server error" } }
        assertEquals("央视,#genre#\nCCTV1,https://example.com/live", LivePlaylistCache.get(file) { error("cache lost") })
        assertEquals("卫视,#genre#\n", LivePlaylistCache.refresh(file) { "卫视,#genre#\n" })
    }
}
