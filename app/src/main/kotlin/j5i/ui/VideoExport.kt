package j5i.spatial8d.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import j5i.spatial8d.audio.LyricsResult
import com.j5i.spatial8d.VideoPixels

internal object VideoExport {
    fun render(context: Context, wav: File, output: File, state: VisualizerView.ExportState,
        lyrics: LyricsResult?, landscape: Boolean, resolution: Int, speed: Double, reverse: Boolean,
        startSeconds: Double, durationSeconds: Double,
        progress: (Int) -> Unit) {
        if (Looper.myLooper() == null) Looper.prepare()
        RandomAccessFile(wav, "r").use { input ->
            val header = ByteArray(44)
            input.readFully(header)
            val rate = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            require(rate > 0 && input.length() > 44 && header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()))
            val totalFrames = (input.length() - 44) / 4
            val (firstFrame, frames) = videoClipFrames(totalFrames, rate, startSeconds, durationSeconds)
            val durationUs = frames * 1_000_000L / rate
            require(resolution == 720 || resolution == 1080)
            val longSide = if (resolution == 1080) 1920 else 1280
            val width = if (landscape) longSide else resolution
            val height = if (landscape) resolution else longSide
            var view = VisualizerView(context)
            view.prepareExport(state, lyrics, width, height, rate)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val pixels = IntArray(width * height)
            val video = File.createTempFile("8d-video-", ".mp4", context.cacheDir)
            val audio = File.createTempFile("8d-audio-", ".mp4", context.cacheDir)
            val started = android.os.SystemClock.elapsedRealtime()
            var outroBase: Bitmap? = null
            try {
                fun videoFormat(color: Int) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (resolution == 1080) 12_000_000 else 6_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
                val sceneCount = videoFrameCount(durationUs).toInt()
                val outro = state.creative.outroText.trim().takeIf { it.isNotEmpty() }?.let {
                    ParticleTextOutro(width, height, it, state.creative.outroMotif)
                }
                val outroFrames = if (outro == null) 0 else videoFrameCount(state.creative.outroDuration * 1_000_000L).toInt()
                val count = sceneCount + outroFrames
                fun drawFrame(frame: Int): Long {
                    val timeUs = videoFrameTimeUs(frame.toLong())
                    val sceneTimeUs = timeUs.coerceAtMost((durationUs - 1).coerceAtLeast(0))
                    val sampleFrame = sceneTimeUs * rate / 1_000_000
                    val sampleBytes = minOf(4096L, (frames - sampleFrame).coerceAtLeast(0) * 4).toInt()
                    if (frame < sceneCount) {
                        val pcm = ByteArray(sampleBytes)
                        input.seek(44 + (firstFrame + sampleFrame) * 4)
                        input.readFully(pcm)
                        val position = ((timeUs / 1000.0 + firstFrame * 1000.0 / rate) * speed).toLong()
                        val lyricTime = if (reverse) ((totalFrames * 1000.0 / rate * speed).toLong() - position).coerceAtLeast(0) else position
                        view.drawExportFrame(canvas, pcm, pcm.size, timeUs, lyricTime)
                    } else if (outro != null) {
                        if (outroBase == null) outroBase = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        canvas.drawBitmap(outroBase!!, 0f, 0f, null)
                        outro.draw(canvas, (frame - sceneCount) / 30f)
                    }
                    progress(frame * 75 / count.coerceAtLeast(1))
                    return timeUs
                }
                try {
                    encodeSurface(video, videoFormat(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface), count, width, height, bitmap, ::drawFrame)
                    android.util.Log.i("8DExport", "encoder=surface")
                } catch (exception: Exception) {
                    android.util.Log.w("8DExport", "surface encoder failed, retrying YUV", exception)
                    video.delete()
                    outroBase?.recycle()
                    outroBase = null
                    view = VisualizerView(context)
                    view.prepareExport(state, lyrics, width, height, rate)
                    encode(video, videoFormat(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible), count) { codec, index, frame ->
                    val timeUs = drawFrame(frame)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    val image = codec.getInputImage(index) ?: error("视频编码器不支持 YUV 输入")
                    try {
                        val planes = image.planes
                        VideoPixels.toYuv(pixels, width, height,
                            planes[0].buffer, planes[0].buffer.position(), planes[0].rowStride, planes[0].pixelStride,
                            planes[1].buffer, planes[1].buffer.position(), planes[1].rowStride, planes[1].pixelStride,
                            planes[2].buffer, planes[2].buffer.position(), planes[2].rowStride, planes[2].pixelStride)
                    } finally { image.close() }
                    (codec.getInputBuffer(index)?.capacity() ?: error("视频编码输入缓冲区不可用")) to timeUs
                    }
                    android.util.Log.i("8DExport", "encoder=yuv")
                }
                android.util.Log.i("8DExport", "video frames=$count size=${width}x$height elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                }
                input.seek(44 + firstFrame * 4)
                val blocks = ((frames * 4 + 4095) / 4096).toInt()
                encode(audio, audioFormat, blocks) { codec, index, block ->
                    val buffer = codec.getInputBuffer(index) ?: error("音频编码器不可用")
                    buffer.clear()
                    require(buffer.remaining() >= 4096) { "音频编码器输入缓冲区过小" }
                    val remaining = frames * 4 - block * 4096L
                    val bytes = ByteArray(minOf(buffer.remaining().toLong(), 4096L, remaining).toInt())
                    input.readFully(bytes)
                    buffer.put(bytes)
                    progress(75 + block * 20 / blocks.coerceAtLeast(1))
                    bytes.size to (block * 1024L * 1_000_000L / rate)
                }
                progress(95)
                mux(video, audio, output)
                android.util.Log.i("8DExport", "video and audio complete elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                progress(100)
            } finally {
                outroBase?.recycle()
                bitmap.recycle()
                video.delete()
                audio.delete()
            }
        }
    }

    private fun encodeSurface(file: File, format: MediaFormat, count: Int, width: Int, height: Int,
        bitmap: Bitmap, drawFrame: (Int) -> Long) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null
        var renderer: VideoSurface? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            renderer = VideoSurface(surface, width, height)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var frame = 0
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone) {
                var advanced = false
                if (!inputDone) {
                    if (frame < count) { renderer.draw(bitmap, drawFrame(frame++)); advanced = true }
                    else { codec.signalEndOfInputStream(); inputDone = true; advanced = true }
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); advanced = true
                    } else if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index) ?: error("编码输出为空")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(track >= 0)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false); advanced = true
                        if (outputDone) break
                    } else break
                }
                idle = if (advanced) 0 else idle + 1
                check(idle < 1000) { "编码器超时" }
            }
            muxer.stop()
        } finally {
            renderer?.close(); surface?.release(); muxer?.release(); codec.release()
        }
    }

    private fun encode(file: File, format: MediaFormat, count: Int,
        fill: (MediaCodec, Int, Int) -> Pair<Int, Long>) {
        val codec = MediaCodec.createEncoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var frame = 0
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone) {
                var advanced = false
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (frame == count) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val (size, timestamp) = fill(codec, index, frame++)
                            codec.queueInputBuffer(index, 0, size, timestamp, 0)
                        }
                        advanced = true
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); advanced = true }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index) ?: error("编码输出为空")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(track >= 0)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        advanced = true
                    }
                }
                idle = if (advanced) 0 else idle + 1
                check(idle < 1000) { "编码器超时" }
            }
            muxer.stop()
        } finally {
            muxer?.release()
            codec.release()
        }
    }

    private fun mux(video: File, audio: File, output: File) {
        val videoInput = MediaExtractor()
        val audioInput = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            videoInput.setDataSource(video.absolutePath)
            audioInput.setDataSource(audio.absolutePath)
            videoInput.selectTrack(0); audioInput.selectTrack(0)
            val videoTrack = muxer.addTrack(videoInput.getTrackFormat(0))
            val audioTrack = muxer.addTrack(audioInput.getTrackFormat(0))
            muxer.start()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (videoInput.sampleTime >= 0 || audioInput.sampleTime >= 0) {
                val chooseVideo = videoInput.sampleTime >= 0 && (audioInput.sampleTime < 0 || videoInput.sampleTime <= audioInput.sampleTime)
                val source = if (chooseVideo) videoInput else audioInput
                buffer.clear()
                val size = source.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, source.sampleTime, source.sampleFlags)
                muxer.writeSampleData(if (chooseVideo) videoTrack else audioTrack, buffer, info)
                source.advance()
            }
            muxer.stop()
        } finally {
            videoInput.release(); audioInput.release(); muxer.release()
        }
    }
}
