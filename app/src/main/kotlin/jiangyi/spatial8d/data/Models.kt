package jiangyi.spatial8d.data

data class MusicItem(
    val id: Long,
    val name: String,
    val uri: String,
    val size: Long = 0L,
    val duration: Long = 0L,
    val artist: String = "未知歌手",
    val album: String = "未知专辑"
)

data class Playlist(val name: String, val ids: List<Long>, val rule: String = "")

enum class PlayMode { LOOP_LIST, LOOP_ONE, SHUFFLE }
