package j5i.spatial8d.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test fun systemLanguagesUseChineseFallback() {
        assertEquals(AppLanguage.SIMPLIFIED, AppLanguage.systemLanguage(Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLanguage.TRADITIONAL, AppLanguage.systemLanguage(Locale.TRADITIONAL_CHINESE))
        assertEquals(AppLanguage.TRADITIONAL, AppLanguage.systemLanguage(Locale.forLanguageTag("zh-Hant")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.systemLanguage(Locale.US))
        assertEquals(AppLanguage.SIMPLIFIED, AppLanguage.systemLanguage(Locale.JAPANESE))
    }

    @Test fun videoAndCreativeTextUsesSelectedLanguage() {
        assertEquals("清晰度", AppLanguage.textForLanguage(AppLanguage.SIMPLIFIED, "video.quality"))
        assertEquals("影片連結", AppLanguage.textForLanguage(AppLanguage.TRADITIONAL, "video.links"))
        assertEquals("Quality", AppLanguage.textForLanguage(AppLanguage.ENGLISH, "video.quality"))
        assertEquals("Creative settings", AppLanguage.textForLanguage(AppLanguage.ENGLISH, "creative.title"))
    }

    @Test fun releaseAndLyricsTextIsAvailableInEveryLanguage() {
        for (language in listOf(AppLanguage.SIMPLIFIED, AppLanguage.TRADITIONAL, AppLanguage.ENGLISH)) {
            val release = AppLanguage.textForLanguage(language, "about.release_time")
                .format("2026", "11", "23", "10", 1)
            kotlin.test.assertTrue(release.contains("2026-11-23"))
            kotlin.test.assertTrue(AppLanguage.textForLanguage(language, "about.build_number").format(261123101L).contains("261123101"))
            for (key in listOf("lyrics.error.title", "lyrics.error.network", "lyrics.error.no_match", "lyrics.progress.fetch")) {
                kotlin.test.assertNotEquals(key, AppLanguage.textForLanguage(language, key))
            }
        }
    }

    @Test fun firstPartyErrorsTranslateAndUnknownMessagesRemainUnchanged() {
        kotlin.test.assertTrue(AppLanguage.isFirstPartyErrorOrigin("j5i.spatial8d.core.PcmBuffer"))
        kotlin.test.assertTrue(AppLanguage.isFirstPartyErrorOrigin("j5i.spatial8d.provider.ProviderJar"))
        kotlin.test.assertFalse(AppLanguage.isFirstPartyErrorOrigin("org.example.Plugin"))
        assertEquals("Invalid plugin domain", AppLanguage.errorForLanguage(AppLanguage.ENGLISH, "插件域名无效"))
        assertEquals("外掛網域無效", AppLanguage.errorForLanguage(AppLanguage.TRADITIONAL, "插件域名无效"))
        assertEquals("Source request failed (HTTP 403)", AppLanguage.errorForLanguage(AppLanguage.ENGLISH, "来源请求失败 (HTTP 403)"))
        assertEquals("外部错误", AppLanguage.errorForLanguage(AppLanguage.ENGLISH, "外部错误"))
    }

    @Test fun dynamicErrorTemplatesHaveCompatiblePlaceholders() {
        for (language in listOf(AppLanguage.SIMPLIFIED, AppLanguage.TRADITIONAL, AppLanguage.ENGLISH)) {
            for (key in listOf("error.field_path", "error.station_field", "error.unauthorized_domain", "error.source_http", "error.audio_http", "cache.delete.confirm", "cache.legacy")) {
                kotlin.test.assertTrue(AppLanguage.textForLanguage(language, key).format("test").contains("test"))
            }
        }
    }

    @Test fun knownMvQualityLabelsAreTranslatedWithoutChangingUnknownLabels() {
        assertEquals("HD", AppLanguage.mvQualityForLanguage(AppLanguage.ENGLISH, "高清"))
        assertEquals("畫質 720", AppLanguage.mvQualityForLanguage(AppLanguage.TRADITIONAL, "清晰度 720"))
        assertEquals("custom 4K", AppLanguage.mvQualityForLanguage(AppLanguage.ENGLISH, "custom 4K"))
    }
}
