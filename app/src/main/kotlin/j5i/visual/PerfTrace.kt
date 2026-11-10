package j5i.spatial8d.visual

import android.util.Log
import j5i.spatial8d.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量性能埋点：debug 构建开启，release 中调用点被常量折叠消除。
 * 每 5 秒聚合一条 8DPerf 日志，用于重构前后对比，不参与任何业务逻辑。
 */
object PerfTrace {
    private const val TAG = "8DPerf"
    private const val INTERVAL_MS = 5_000L
    private const val RING = 512

    @JvmStatic
    val enabled: Boolean get() = BuildConfig.DEBUG

    private val metrics = ConcurrentHashMap<String, Metric>()
    @Volatile private var schedulerStarted = false

    private class Metric {
        val ring = LongArray(RING)
        @Volatile var pos = 0
        @Volatile var wrapped = false
        var sum = 0L
        var max = 0L
        var count = 0L
        var lastAtNs = 0L
        var gapMaxNs = 0L
        var events = 0L
    }

    private fun ensureScheduler() {
        if (schedulerStarted || !enabled) return
        synchronized(this) {
            if (schedulerStarted) return
            schedulerStarted = true
            Thread({
                while (true) {
                    try { Thread.sleep(INTERVAL_MS) } catch (_: InterruptedException) { return@Thread }
                    flush()
                }
            }, "perf-trace").apply { isDaemon = true }.start()
        }
    }

    inline fun <T> timed(name: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            sample(name, System.nanoTime() - start)
        }
    }

    fun sample(name: String, valueNs: Long) {
        if (!enabled) return
        ensureScheduler()
        val m = metrics.getOrPut(name) { Metric() }
        synchronized(m) {
            m.sum += valueNs
            if (valueNs > m.max) m.max = valueNs
            m.count++
            m.ring[m.pos] = valueNs
            m.pos = (m.pos + 1) % RING
            if (m.pos == 0) m.wrapped = true
        }
    }

    /** 同名连续调用的时间间隔（毫秒级埋点用纳秒记录），如 PCM 包到达间隔。 */
    fun interval(name: String) {
        if (!enabled) return
        ensureScheduler()
        val now = System.nanoTime()
        val m = metrics.getOrPut(name) { Metric() }
        synchronized(m) {
            if (m.lastAtNs != 0L) {
                val gap = now - m.lastAtNs
                if (gap > m.gapMaxNs) m.gapMaxNs = gap
                m.sum += gap
                m.count++
                m.ring[m.pos] = gap
                m.pos = (m.pos + 1) % RING
                if (m.pos == 0) m.wrapped = true
            }
            m.lastAtNs = now
        }
    }

    fun bump(name: String, delta: Long = 1L) {
        if (!enabled) return
        ensureScheduler()
        val m = metrics.getOrPut(name) { Metric() }
        synchronized(m) { m.events += delta }
    }

    private fun percentile(sorted: LongArray, length: Int, q: Double): Long {
        if (length == 0) return 0L
        val index = ((length - 1) * q).toInt().coerceIn(0, length - 1)
        return sorted[index]
    }

    private fun flush() {
        if (!enabled || metrics.isEmpty()) return
        val parts = mutableListOf<String>()
        for ((name, m) in metrics.toMap()) {
            val snapshot: LongArray
            val n: Int
            val sum: Long
            val max: Long
            val count: Long
            val events: Long
            val gapMax: Long
            synchronized(m) {
                n = if (m.wrapped) RING else m.pos
                snapshot = if (n > 0) m.ring.copyOfRange(0, n).also { it.sort() } else LongArray(0)
                sum = m.sum; max = m.max; count = m.count; events = m.events; gapMax = m.gapMaxNs
            }
            when {
                events > 0 && count == 0L -> parts.add("$name=${events}x")
                count > 0 -> parts.add(
                    "$name[avg=${sum / count / 1000}us p50=${percentile(snapshot, n, 0.5) / 1000}us " +
                        "p99=${percentile(snapshot, n, 0.99) / 1000}us max=${max / 1000}us gapMax=${gapMax / 1_000_000}ms n=$count" +
                        if (events > 0) " ${events}x]" else "]"
                )
            }
        }
        if (parts.isNotEmpty()) Log.i(TAG, parts.joinToString(" | "))
    }
}
