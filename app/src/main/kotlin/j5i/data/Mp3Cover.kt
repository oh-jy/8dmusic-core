package j5i.spatial8d.data

import java.io.File
import java.io.RandomAccessFile

internal object Mp3Cover {
    private fun syncSize(bytes: ByteArray, offset: Int): Int {
        require((offset until offset + 4).all { bytes[it].toInt() and 0x80 == 0 })
        return (offset until offset + 4).fold(0) { value, index -> (value shl 7) or (bytes[index].toInt() and 127) }
    }

    private fun syncBytes(size: Int) = byteArrayOf((size shr 21).toByte(), ((size shr 14) and 127).toByte(),
        ((size shr 7) and 127).toByte(), (size and 127).toByte())

    fun write(file: File, image: ByteArray) {
        require(file.isFile && file.canWrite() && image.isNotEmpty())
        val mime = when {
            image.size >= 8 && image.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
            image.size >= 3 && image[0] == 0xff.toByte() && image[1] == 0xd8.toByte() && image[2] == 0xff.toByte() -> "image/jpeg"
            else -> error("不支持的封面格式")
        }
        val temporary = File.createTempFile(".cover-", ".mp3", file.parentFile)
        try {
            RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(10)
                val hasTag = input.length() >= 10 && run { input.readFully(header); header.copyOfRange(0, 3).contentEquals("ID3".toByteArray()) }
                val version = if (hasTag) header[3].toInt() else 4
                require(!hasTag || (version in 3..4 && header[4].toInt() == 0 && header[5].toInt() == 0))
                val size = if (hasTag) syncSize(header, 6) else 0
                require(size <= 16 * 1024 * 1024 && 10L + size <= input.length() || !hasTag)
                val original = ByteArray(size)
                if (hasTag) input.readFully(original) else input.seek(0)
                val frames = java.io.ByteArrayOutputStream()
                var position = 0
                while (position < original.size && original[position].toInt() != 0) {
                    require(position + 10 <= original.size)
                    val id = original.copyOfRange(position, position + 4).toString(Charsets.US_ASCII)
                    require(id.length == 4 && id.all { it in 'A'..'Z' || it in '0'..'9' })
                    val frameSize = if (version == 4) syncSize(original, position + 4) else
                        java.nio.ByteBuffer.wrap(original, position + 4, 4).int
                    require(frameSize >= 0 && frameSize <= original.size - position - 10)
                    val end = position + 10 + frameSize
                    if (id != "APIC") frames.write(original, position, end - position)
                    position = end
                }
                require((position until original.size).all { original[it].toInt() == 0 })
                val payload = byteArrayOf(0) + mime.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 3, 0) + image
                frames.write("APIC".toByteArray(Charsets.US_ASCII))
                frames.write(if (version == 4) syncBytes(payload.size) else java.nio.ByteBuffer.allocate(4).putInt(payload.size).array())
                frames.write(byteArrayOf(0, 0))
                frames.write(payload)
                val tag = frames.toByteArray()
                require(tag.size < 0x10000000)
                temporary.outputStream().use { output ->
                    output.write("ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(version.toByte(), 0, 0) + syncBytes(tag.size))
                    output.write(tag)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            try {
                java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                check(temporary.renameTo(file)) { "无法安全替换原文件" }
            } catch (unsupported: LinkageError) {
                check(temporary.renameTo(file)) { "无法安全替换原文件" }
            }
        } finally {
            temporary.delete()
        }
    }
}
