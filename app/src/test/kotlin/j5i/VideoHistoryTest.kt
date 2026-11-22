package j5i.spatial8d

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoHistoryTest {
    @Test fun recordsRecentVideoWithoutLosingRenamedTitle() {
        val entries = listOf(VideoHistoryEntry("https://example.com/a.m3u8", "自定义标题"), VideoHistoryEntry("content://video/2", "第二个"))
        val updated = recordVideoHistory(entries, "https://example.com/a.m3u8", "新标题")
        assertEquals(entries, readVideoHistory(writeVideoHistory(updated)))
        assertEquals(emptyList(), readVideoHistory("invalid"))
    }
}
