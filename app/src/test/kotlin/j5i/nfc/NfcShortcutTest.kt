package j5i.spatial8d.nfc

import j5i.spatial8d.data.MusicItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class NfcShortcutTest {
    @Test fun repeatedTargetIsSuppressedButOtherTargetsAndLaterScansWork() {
        val guard = NfcRepeatGuard()
        val first = NfcShortcut.LocalSong(1)
        val second = NfcShortcut.LocalSong(2)
        assertTrue(guard.accept(first, 1000))
        assertFalse(guard.accept(first, 2000))
        assertFalse(guard.accept(first, 4000))
        assertTrue(guard.accept(second, 4001))
        assertTrue(guard.accept(first, 4002))
        assertTrue(guard.accept(first, 7002))
    }
    @Test fun fmShortcutKeepsStableProviderAndStationIds() {
        val uri = NfcShortcut.create(NfcShortcut.Fm("qingting.fm", "hit-fm"))

        assertTrue(uri.startsWith("spatial8d://v1/fm?"))
        assertEquals(NfcShortcut.Fm("qingting.fm", "hit-fm"), NfcShortcut.parse(uri))
    }

    @Test fun pluginShortcutRoundTripsComplexTrackId() {
        val target = NfcShortcut.PluginSong("platform.bilibili", "BV1abc:123")

        assertEquals(target, NfcShortcut.parse(NfcShortcut.create(target, "复杂歌曲")))
    }

    @Test fun providerShortcutKeepsAuthorizedAudioUrl() {
        val target = NfcShortcut.ProviderSong(
            "music.demo",
            "song-42",
            "https://audio.example.com/song?id=42&format=mp3"
        )
        val uri = NfcShortcut.create(target, "授权歌曲")

        assertTrue(uri.contains("audio="))
        assertEquals(target, NfcShortcut.parse(uri))
    }

    @Test fun localShortcutUsesMediaStoreId() {
        val target = NfcShortcut.LocalSong(123456789L)

        assertEquals(target, NfcShortcut.parse(NfcShortcut.create(target, "本地歌曲")))
    }

    @Test fun songShortcutsPreservePlaylistAndRequireNames() {
        listOf(
            NfcShortcut.LocalSong(123, "favorites"),
            NfcShortcut.PluginSong("music.demo", "42", "playlist:我的歌单"),
            NfcShortcut.ProviderSong("music.demo", "42", "https://example.com/a.mp3", "all")
        ).forEach { target ->
            val uri = NfcShortcut.create(target, "歌曲")
            assertEquals(target, NfcShortcut.parse(uri))
            val parsed = NfcShortcut.parseShortcut(uri)
            assertEquals(target, NfcShortcut.parse(NfcShortcut.create(parsed.target, parsed.name)))
        }
        assertFailsWith<IllegalArgumentException> { NfcShortcut.parse("spatial8d://v1/song?k=local&id=123") }
        assertFailsWith<IllegalArgumentException> { NfcShortcut.create(NfcShortcut.LocalSong(123)) }
        assertFailsWith<IllegalArgumentException> { NfcShortcut.create(NfcShortcut.LocalSong(123), "  ") }
        assertFailsWith<IllegalArgumentException> { NfcShortcut.create(NfcShortcut.LocalSong(123), "歌".repeat(300)) }
        assertFailsWith<IllegalArgumentException> { NfcShortcut.parse("spatial8d://v1/fm?src=a&sid=b&list=favorites") }
    }

    @Test fun duplicateLocalNamesSelectTheFirstMatchingSong() {
        val first = MusicItem(1, "夜曲.mp3", "content://media/external/audio/media/1")
        val second = MusicItem(2, "夜曲.flac", "content://media/external/audio/media/2")

        assertEquals(first, firstLocalSongByName(listOf(first, second), "夜曲"))
    }

    @Test fun fmNameIsOptionalWhileSongNamesArePreserved() {
        val target = NfcShortcut.Fm("radio", "station")
        val named = NfcShortcut.create(target, "重庆 FM & 音乐")
        assertTrue(named.contains("&name="))
        assertEquals(target, NfcShortcut.parse(named))
        assertEquals(NfcShortcut.create(target), NfcShortcut.create(target, "歌".repeat(100)))
        assertEquals(target, NfcShortcut.parse(NfcShortcut.create(target)))

        val song = NfcShortcut.LocalSong(123)
        val songUri = NfcShortcut.create(song, "重庆 & 夜曲")
        assertEquals("重庆 & 夜曲", NfcShortcut.parseShortcut(songUri).name)
    }

    @Test fun rejectsUnknownPathsAndParameters() {
        listOf(
            "https://example.com/v1/fm?src=a&sid=b",
            "spatial8d://v2/fm?src=a&sid=b",
            "spatial8d://v1/fm?src=a&sid=b&token=x",
            "spatial8d://v1/song?k=plugin&src=a&id=b&extra=x",
            "spatial8d://v1/song?k=remote&src=a&id=b"
        ).forEach { uri ->
            assertFailsWith<IllegalArgumentException> { NfcShortcut.parse(uri) }
        }
    }

    @Test fun rejectsAudioUrlsWithCredentialsOrSensitiveParameters() {
        listOf(
            "https://user:secret@audio.example.com/song",
            "https://audio.example.com/song?access_token=secret",
            "https://audio.example.com/song?api-key=secret",
            "ftp://audio.example.com/song"
        ).forEach { audioUrl ->
            val target = NfcShortcut.ProviderSong("music.demo", "song-42", audioUrl)
            assertFailsWith<IllegalArgumentException> { NfcShortcut.create(target) }
        }
    }
}
