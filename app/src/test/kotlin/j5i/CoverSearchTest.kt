package j5i.spatial8d

import j5i.spatial8d.feature.ui.coverMatchRank
import j5i.spatial8d.feature.ui.parseQqCovers
import j5i.spatial8d.feature.ui.parseKugouCovers
import j5i.spatial8d.feature.ui.parseNeteaseCovers
import j5i.spatial8d.feature.ui.rankCoverCandidates
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverSearchTest {
    @Test fun editedTitleAndArtistControlMatchingAndArtistMayBeCleared() {
        val candidates = parseQqCovers(JSONArray("""[
            {"name":"遇见","singer":[{"name":"孙燕姿"}],"album":{"mid":"original"}},
            {"name":"遇见","singer":[{"name":"其他歌手"}],"album":{"mid":"cover"}}]"""))
        assertTrue(rankCoverCandidates("错误歌名", "错误歌手", candidates).isEmpty())
        assertEquals("孙燕姿", rankCoverCandidates("遇见", "孙燕姿", candidates).single().artist)
        assertEquals("其他歌手", rankCoverCandidates("遇见", "其他歌手", candidates).single().artist)
        assertEquals(2, rankCoverCandidates("遇见", "", candidates).size)
    }

    @Test fun requiresSongAndArtistAndRanksOriginalAboveLive() {
        val original = coverMatchRank("不能说的秘密", "周杰伦", "不能说的秘密", "周杰伦")
        val live = coverMatchRank("不能说的秘密", "周杰伦", "不能说的秘密 (Live)", "周杰伦")
        assertTrue(original > live && live >= 0)
        assertEquals(-1, coverMatchRank("不能说的秘密", "周杰伦", "屋顶", "周杰伦"))
        assertEquals(-1, coverMatchRank("不能说的秘密", "周杰伦", "不能说的秘密", "其他歌手"))
        assertEquals(-1, coverMatchRank("不能说的秘密", "周杰伦", "Secret (from Secret)", "Baroque Camerata"))
        assertEquals(-1, coverMatchRank("不能说的秘密", "周杰伦", "", ""))
    }

    @Test fun combinesAlbumCoversButRejectsSingerPortraitsAndWrongArtists() {
        val qq = parseQqCovers(JSONArray("""[
            {"songname":"不能说的秘密 (Live)","singer":[{"name":"周杰伦"}],"albummid":"live","albumname":"演唱会"},
            {"songname":"不能说的秘密","singer":[{"name":"周杰伦"}],"albummid":"001UP7mW458ipG","albumname":"不能说的秘密"},
            {"songname":"屋顶","singer":[{"name":"周杰伦"}],"albummid":"other"},
            {"songname":"不能说的秘密","singer":[{"name":"周杰伦"}]}]"""))
        val kugou = parseKugouCovers(JSONArray("""[
            {"SongName":"不能说的秘密","SingerName":"周杰伦","Image":"http://singerimg.kugou.com/uploadpic/softhead/{size}/portrait.jpg"},
            {"SongName":"不能说的秘密 (Live)","SingerName":"周杰伦","AlbumName":"演唱会","Image":"http://imge.kugou.com/stdmusic/{size}/album.jpg"}]"""))
        val netease = parseNeteaseCovers(JSONArray("""[
            {"name":"不能说的秘密","artists":[{"name":"纸飞机"}],"album":{"name":"翻唱","picUrl":"https://p1.music.126.net/cover.jpg"}},
            {"name":"不能说的秘密","artists":[{"name":"周杰伦"}],"album":{"name":"无图片"}}]"""))
        assertEquals(3, qq.size)
        assertEquals(1, kugou.size)
        assertEquals("https://imge.kugou.com/stdmusic/400/album.jpg", kugou.single().imageUrl)
        val merged = rankCoverCandidates("不能说的秘密", "周杰伦", qq + kugou + netease + qq)
        assertEquals(3, merged.size)
        assertEquals("不能说的秘密", merged.first().title)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000001UP7mW458ipG.jpg", merged.first().imageUrl)
        assertTrue(merged.all { it.artist == "周杰伦" })
    }

    @Test fun supportsModernMetadataAndCanonicalChinese() {
        val qq = parseQqCovers(JSONArray("""[{"name":"不能說的秘密","singer":[{"name":"周杰倫"}],"album":{"mid":"abc123","name":"原声带"}}]"""))
        val netease = parseNeteaseCovers(JSONArray("""[{"name":"不能说的秘密","ar":[{"name":"周杰伦"}],"al":{"picUrl":"http://p1.music.126.net/album.jpg","name":"原声带"}}]"""))
        val merged = rankCoverCandidates("不能说的秘密", "周杰伦", qq + netease) {
            it.replace('說', '说').replace('倫', '伦')
        }
        assertEquals(2, merged.size)
        assertEquals("原声带", merged.first().album)
        assertEquals("https://p1.music.126.net/album.jpg?param=800y800", merged.last().imageUrl)
        assertEquals("https://p1.music.126.net/album.jpg?param=300y300", merged.last().previewUrl)
    }
}
