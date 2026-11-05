package j5i.spatial8d

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioDetailsTest {
    @Test fun formatsOnlyKnownValues() {
        assertEquals("AAC · 44100 Hz · 192 kbps", audioDetails("audio/mp4a-latm", 44100, 192000))
        assertEquals("FLAC · 48000 Hz", audioDetails("audio/flac", 48000, 0))
        assertEquals("", audioDetails("", 0, 0))
    }

    @Test fun appendsAvailableInfoToExistingStatus() {
        assertEquals("正在播放 · AAC · 44100 Hz · 专辑", playbackStatusWithInfo("正在播放", "AAC · 44100 Hz", "专辑"))
        assertEquals("当前歌词", playbackStatusWithInfo("当前歌词", "", ""))
    }
}
