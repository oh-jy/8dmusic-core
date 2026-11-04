package j5i.spatial8d.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WbiTest {
    @Test fun mixinKeyUsesOfficialPermutation() {
        // 线上真实 img/sub 密钥对应的 mixin key（置换表与截取长度的回归向量）。
        val mixin = Wbi.mixinKey(
            "7cd084941338484aae1ad9425b84077c",
            "4932caff0ff746eab6f01bf08b70ac45"
        )
        assertEquals("ea1db124af3c7062474693fa704f4ff8", mixin)
    }

    @Test fun signedQuerySortsParamsAddsTimestampAndSignature() {
        val mixin = "ea1db124af3c7062474693fa704f4ff8"
        val query = Wbi.signedQuery(linkedMapOf(
            "keyword" to "晴天",
            "page" to "1"
        ), mixin, 1_700_000_000L)
        val params = query.split("&").associate {
            val pair = it.split("=", limit = 2)
            pair[0] to pair[1]
        }
        assertEquals("1700000000", params["wts"])
        // 业务参数按键名字典序排列，wts 参与排序，w_rid 按官方惯例追加在末尾。
        val keys = query.split("&").map { it.substringBefore("=") }
        assertEquals(listOf("keyword", "page", "wts", "w_rid"), keys)
        assertTrue((params["w_rid"] ?: "").matches(Regex("[0-9a-f]{32}")))
    }

    @Test fun signedQueryStripsReservedCharactersBeforeSigning() {
        val mixin = "ea1db124af3c7062474693fa704f4ff8"
        val query = Wbi.signedQuery(mapOf("keyword" to "a!b'c(d)e*f"), mixin, 1_700_000_000L)
        assertTrue(query.contains("keyword=abcdef"))
    }
}
