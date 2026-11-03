package j5i.spatial8d

import com.j5i.spatial8d.HlsMediaDataSource
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.j5i.spatial8d.TsAdtsMediaDataSource

class HlsStreamingTest {
    @Test fun rawAdtsFramesCanStreamAcrossSocketBoundaries() {
        val executor = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val frames = Array(3) { index -> ByteArray(9) { i ->
            when (i) { 0 -> 0xff.toByte(); 1 -> 0xf1.toByte(); 2 -> 0x50.toByte()
                3 -> 0x80.toByte(); 4 -> 1.toByte(); 5 -> 0x20.toByte()
                6 -> 0.toByte(); else -> index.toByte() }
        } }
        executor.submit {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Length: ${frames.sumOf { it.size }}\r\nConnection: close\r\n\r\n".toByteArray())
                frames.forEach { output.write(it); output.flush(); Thread.sleep(20) }
            }
        }
        val source = TsAdtsMediaDataSource("http://127.0.0.1:${server.localPort}/live.aac")
        try {
            frames.forEach { frame -> assertContentEquals(frame, source.nextFrame()) }
        } finally {
            source.close(); server.close(); executor.shutdownNow()
        }
    }

    @Test fun deliversPartialSegmentBeforeServerFinishesAndKeepsItSeekable() {
        val release = CountDownLatch(1)
        val firstSent = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = ServerSocket(0)
        executor.submit {
            repeat(2) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val output = socket.getOutputStream()
                    if (request.contains("/live.m3u8")) {
                        val body = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nsegment.ts\n".toByteArray()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body)
                    } else {
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 376\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(ByteArray(188) { 0x47 })
                        output.flush()
                        firstSent.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        output.write(ByteArray(188) { 0x48 })
                    }
                }
            }
        }
        val source = HlsMediaDataSource("http://127.0.0.1:${server.localPort}/live.m3u8")
        try {
            val result = executor.submit<ByteArray> {
                val buffer = ByteArray(188)
                assertEquals(188, source.readAt(0, buffer, 0, buffer.size))
                buffer
            }
            if (!firstSent.await(3, TimeUnit.SECONDS)) result.get(1, TimeUnit.SECONDS)
            assertTrue(firstSent.count == 0L)
            val first = result.get(2, TimeUnit.SECONDS)
            assertContentEquals(ByteArray(188) { 0x47 }, first)
            val reread = ByteArray(188)
            assertEquals(188, source.readAt(0, reread, 0, reread.size))
            assertContentEquals(first, reread)
            release.countDown()
            val second = ByteArray(188)
            assertEquals(188, source.readAt(188, second, 0, second.size))
            assertContentEquals(ByteArray(188) { 0x48 }, second)
        } finally {
            release.countDown()
            source.close()
            server.close()
            executor.shutdownNow()
        }
    }
}
