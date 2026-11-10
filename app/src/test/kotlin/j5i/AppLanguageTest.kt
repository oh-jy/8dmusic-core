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
}
