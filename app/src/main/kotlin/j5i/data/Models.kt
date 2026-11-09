package j5i.spatial8d.data

internal fun MusicItem.isOnlineAudio() = album.startsWith("plugin:") || album.startsWith("provider:") ||
    album.startsWith("在线缓存") || uri.startsWith("online://") || uri.startsWith("http://") || uri.startsWith("https://")

data class MusicItem(
    val id: Long,
    val name: String,
    val uri: String,
    val size: Long = 0L,
    val duration: Long = 0L,
    val artist: String = "未知歌手",
    val album: String = "未知专辑",
    /** MediaStore DATE_ADDED 换算成毫秒；手动添加时为首次记住时间。 */
    val addedAt: Long = 0L,
    /** MediaStore DATE_MODIFIED 换算成毫秒；手动添加时回退为加入时间。 */
    val modifiedAt: Long = 0L,
)

data class Playlist(val name: String, val ids: List<Long>, val rule: String = "")

/** 内置「收藏」列表名称，与用户自建列表区分，不可删除。 */
const val FAVORITES_NAME = "收藏"
/** playlistIndex 的固定值，表示「收藏」列表（不在 playlists 集合内）。 */
const val FAVORITES_INDEX = -2

enum class PlayMode { LOOP_LIST, LOOP_ONE, SHUFFLE }
