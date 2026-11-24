package j5i.spatial8d.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoScannedMusicTest {
    @Test fun filtersShortAudioAndCallRecordingFolders() {
        assertFalse(isAutoScannedMusic(29_999, "Music/Albums"))
        assertTrue(isAutoScannedMusic(30_000, "Music/Albums"))
        assertFalse(isAutoScannedMusic(90_000, "Music/Recordings/Call Recordings/"))
        assertFalse(isAutoScannedMusic(90_000, "MIUI/sound_recorder/call_rec/"))
        assertFalse(isAutoScannedMusic(90_000, "录音/通话录音/"))
        assertTrue(isAutoScannedMusic(90_000, "Music/Recordings/"))
    }
}
