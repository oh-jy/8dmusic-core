package j5i.spatial8d.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import j5i.spatial8d.audio.LyricsResult
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

internal object GifExport {
    fun render(context: Context, wav: File, output: File, state: VisualizerView.ExportState,
        lyrics: LyricsResult?, landscape: Boolean, startSeconds: Double, durationSeconds: Double,
        speed: Double, reverse: Boolean, smoothLoop: Boolean, progress: (Int) -> Unit) {
        if (Looper.myLooper() == null) Looper.prepare()
        RandomAccessFile(wav, "r").use { input ->
            val header = ByteArray(44)
            input.readFully(header)
            require(header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()))
            val rate = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            val totalFrames = (input.length() - 44) / 4
            val (first, frames) = videoClipFrames(totalFrames, rate, startSeconds, durationSeconds.coerceIn(1.0, 10.0))
            val width = if (landscape) 640 else 360
            val height = if (landscape) 360 else 640
            val view = VisualizerView(context)
            view.prepareExport(state, lyrics, width, height, rate)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val pixels = IntArray(width * height)
            val firstFramePixels = IntArray(width * height)
            val outro = state.creative.outroText.trim().takeIf { it.isNotEmpty() }?.let {
                ParticleTextOutro(width, height, it, state.creative.outroMotifs, state.creative.outroDuration.toFloat())
            }
            val sceneCount = ((frames * 10 + rate - 1) / rate).toInt()
            val outroCount = if (outro == null) 0 else state.creative.outroDuration * 10
            val count = sceneCount + outroCount
            var outroCaptured = false
            try {
                output.outputStream().buffered().use { stream ->
                    val encoder = GifEncoder(stream, width, height, 0)
                    val options = ImageOptions().setDelay(100, TimeUnit.MILLISECONDS)
                    for (frame in 0 until count) {
                        if (frame < sceneCount) {
                            val sample = frame.toLong() * rate / 10
                            val bytes = ByteArray(minOf(4096L, (frames - sample) * 4).toInt())
                            input.seek(44 + (first + sample) * 4)
                            input.readFully(bytes)
                            val position = ((first + sample) * 1000.0 / rate * speed).toLong()
                            val lyricTime = if (reverse) (totalFrames * 1000.0 / rate * speed).toLong() - position else position
                            view.drawExportFrame(canvas, bytes, bytes.size, frame * 100_000L, lyricTime.coerceAtLeast(0))
                        } else if (outro != null) {
                            if (!outroCaptured) { outro.captureOrigins(bitmap); outroCaptured = true }
                            view.drawExportFrame(canvas, ByteArray(0), 0, frame * 100_000L,
                                ((first + frames) * 1000.0 / rate * speed).toLong())
                            outro.draw(canvas, (frame - sceneCount) / 10f)
                        }
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                        if (frame == 0) pixels.copyInto(firstFramePixels)
                        if (smoothLoop && frame >= count - 5) {
                            val blend = ((frame - (count - 5) + 1) / 5f).coerceIn(0f, 1f)
                            for (index in pixels.indices) {
                                val from = pixels[index]
                                val to = firstFramePixels[index]
                                val inverse = 1f - blend
                                pixels[index] = (0xff shl 24) or
                                    ((((from shr 16 and 0xff) * inverse + (to shr 16 and 0xff) * blend).toInt()) shl 16) or
                                    ((((from shr 8 and 0xff) * inverse + (to shr 8 and 0xff) * blend).toInt()) shl 8) or
                                    ((from and 0xff) * inverse + (to and 0xff) * blend).toInt()
                            }
                        }
                        encoder.addImage(pixels, width, options)
                        progress((frame + 1) * 100 / count)
                    }
                    encoder.finishEncoding()
                }
            } finally { bitmap.recycle() }
        }
    }
}
