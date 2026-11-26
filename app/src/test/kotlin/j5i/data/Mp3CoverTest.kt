package j5i.spatial8d.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class Mp3CoverTest {
    private val image = byteArrayOf(-1, -40, -1, 12, 34)

    @Test fun addsTagToUntaggedAudio() {
        val file = File.createTempFile("cover-test", ".mp3")
        try {
            val audio = byteArrayOf(-1, -5, 1, 2, 3)
            file.writeBytes(audio)
            Mp3Cover.write(file, image)
            val result = file.readBytes()
            assertTrue(result.indexOfSlice("APIC".toByteArray()) >= 0)
            assertContentEquals(audio, result.takeLast(audio.size).toByteArray())
        } finally { file.delete() }
    }

    @Test fun writesCoverWithoutChangingAudioOrOtherFrames() {
        val file = File.createTempFile("cover-test", ".mp3")
        try {
            val audio = byteArrayOf(-1, -5, 1, 2, 3)
            val title = "TIT2".toByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 65)
            file.writeBytes("ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, title.size.toByte()) + title + audio)
            Mp3Cover.write(file, image)
            val result = file.readBytes()
            assertTrue(result.indexOfSlice(title) >= 0)
            assertTrue(result.indexOfSlice("APIC".toByteArray()) >= 0)
            assertContentEquals(audio, result.takeLast(audio.size).toByteArray())
            Mp3Cover.write(file, image)
            val again = file.readBytes()
            assertTrue(again.indexOfSlice(title) >= 0)
            assertContentEquals(result, again)
        } finally { file.delete() }
    }

    @Test fun rejectsUnsupportedTagWithoutChangingOriginal() {
        val file = File.createTempFile("cover-test", ".mp3")
        try {
            val original = "ID3".toByteArray() + byteArrayOf(2, 0, 0, 0, 0, 0, 0) + byteArrayOf(1, 2, 3)
            file.writeBytes(original)
            assertFails { Mp3Cover.write(file, image) }
            assertContentEquals(original, file.readBytes())
        } finally { file.delete() }
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int =
        indices.firstOrNull { index -> index + needle.size <= size && needle.indices.all { this[index + it] == needle[it] } } ?: -1
}
