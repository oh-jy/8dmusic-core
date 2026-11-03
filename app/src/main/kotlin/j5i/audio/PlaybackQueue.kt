package j5i.spatial8d.audio

import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.PlayMode

/** 队列归服务持有；Activity 销毁后，自然播完仍能按同一规则选下一首。 */
class PlaybackQueue(
    private val items: List<MusicItem>,
    private val mode: PlayMode,
    private val randomIndex: (Int) -> Int = { (0 until it).random() }
) {
    fun next(currentUri: String?, auto: Boolean = false): MusicItem? {
        if (items.isEmpty()) return null
        if (auto && mode == PlayMode.LOOP_ONE) return null
        val current = items.indexOfFirst { it.uri == currentUri }
        if (auto && current < 0) return null
        val index = when (mode) {
            PlayMode.SHUFFLE -> {
                val candidates = items.indices.filter { it != current }
                if (candidates.isEmpty()) 0 else candidates[randomIndex(candidates.size)]
            }
            else -> (current + 1 + items.size) % items.size
        }
        return items.getOrNull(index)
    }

    fun previous(currentUri: String?): MusicItem? {
        if (items.isEmpty()) return null
        val current = items.indexOfFirst { it.uri == currentUri }
        return items[(current - 1 + items.size) % items.size]
    }
}
