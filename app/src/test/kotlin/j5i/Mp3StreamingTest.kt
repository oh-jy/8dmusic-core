package com.j5i.spatial8d

import kotlin.test.Test
import kotlin.test.assertEquals

class Mp3StreamingTest {
    @Test fun clientMp3FlowProducesConsecutiveFramesBeforeDecoding() {
        val sample = java.io.File("../.codex-tmp/tj.mp3").inputStream().use { it.readBytes() }
        var offset = 0
        var frames = 0
        var bytes = 0
        repeat(100) {
            while (offset + 4 <= sample.size) {
                val header = ((sample[offset].toInt() and 255) shl 24) or
                    ((sample[offset + 1].toInt() and 255) shl 16) or
                    ((sample[offset + 2].toInt() and 255) shl 8) or (sample[offset + 3].toInt() and 255)
                val length = Mp3MediaDataSource.frameLength(header)
                if (length < 0) { offset++; continue }
                check(offset + length <= sample.size)
                if (frames > 0) {
                    check(sample[offset].toInt() and 255 == 0xff &&
                        sample[offset + 1].toInt() and 0xe0 == 0xe0) { "第 $frames 帧边界错位" }
                }
                offset += length; frames++; bytes += length; break
            }
        }
        assertEquals(100, frames)
        check(bytes > 4000) { "没有读到足够的真实 MP3 音频帧" }
    }
}
