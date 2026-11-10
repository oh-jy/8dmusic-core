package j5i.spatial8d.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Lightweight on-device Chinese streaming ASR. Audio work never runs on the playback thread. */
class OfflineAsr(context: Context, private val onText: (String) -> Unit, private val onError: (String) -> Unit) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "offline-asr") }
    private val started = AtomicBoolean(false)
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var pendingText = ""
    private fun createRecognizer(): OnlineRecognizer {
        val dir = "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01"
        return OnlineRecognizer(
            assetManager = appContext.assets,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(16_000, 80),
                modelConfig = OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig("$dir/model.int8.onnx"),
                    tokens = "$dir/tokens.txt",
                    modelingUnit = "bpe",
                    bpeVocab = "$dir/bbpe.model",
                    numThreads = 2,
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0f),
                    rule2 = EndpointRule(true, 0.8f, 0f),
                    rule3 = EndpointRule(false, 0f, 20f),
                ),
                enableEndpoint = true,
            ),
        )
    }

    fun start() {
        if (started.compareAndSet(false, true)) executor.execute {
            try {
                val model = recognizer ?: createRecognizer().also { recognizer = it }
                stream?.release()
                stream = model.createStream()
                pendingText = ""
            } catch (error: Exception) {
                started.set(false)
                onError(error.message ?: "模型加载失败")
            }
        }
    }

    /** Accept interleaved signed 16-bit PCM and downmix/resample to 16 kHz. */
    fun acceptPcm(data: ByteArray, length: Int, sampleRate: Int, channels: Int) {
        if (!started.get() || length < 2) return
        val copy = data.copyOf(length)
        executor.execute { feed(copy, sampleRate, channels) }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        executor.execute {
            stream?.inputFinished()
            stream?.let { decode(it) }
            stream?.release(); stream = null
        }
    }

    fun release() {
        stop()
        executor.execute { recognizer?.release(); recognizer = null }
        executor.shutdown()
    }

    private fun feed(data: ByteArray, sourceRate: Int, channels: Int) {
        val frames = data.size / 2 / channels
        if (frames <= 0) return
        val samples = FloatArray((frames * 16_000.0 / sourceRate).toInt().coerceAtLeast(1))
        for (i in samples.indices) {
            val source = (i * sourceRate / 16_000.0).toInt().coerceAtMost(frames - 1)
            var sum = 0
            repeat(channels) { c ->
                val p = (source * channels + c) * 2
                sum += (data[p].toInt() and 0xff) or (data[p + 1].toInt() shl 8)
            }
            samples[i] = (sum / channels).toShort() / 32768f
        }
        val current = stream ?: return
        try {
            current.acceptWaveform(samples, 16_000)
            decode(current)
        } catch (error: Exception) {
            started.set(false)
            onError(error.message ?: "识别失败")
        }
    }

    private fun decode(current: OnlineStream) {
        val model = recognizer ?: return
        while (model.isReady(current)) model.decode(current)
        val result = model.getResult(current)
        if (result.text.isNotBlank() && result.text != pendingText) {
            pendingText = result.text
            onText(result.text)
        }
        if (model.isEndpoint(current)) {
            model.reset(current)
            pendingText = ""
        }
    }
}
