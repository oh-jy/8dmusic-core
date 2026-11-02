package j5i.spatial8d

import j5i.spatial8d.core.PARAM_INFO
import j5i.spatial8d.core.SLIDERS
import j5i.spatial8d.ui.AppLanguage
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppLanguageTest {
    @Test fun parameterLabelsAndDescriptionsAreTranslated() {
        val keys = SLIDERS.map { "slider.${it.key}" } +
            PARAM_INFO.flatMap { listOf("slider.${it.key}", "param.${it.key}") } +
            listOf("settings", "theme")
        for (language in listOf(AppLanguage.SIMPLIFIED, AppLanguage.TRADITIONAL, AppLanguage.ENGLISH)) {
            for (key in keys) {
                val text = AppLanguage.textForLanguage(language, key)
                assertNotEquals(key, text, "$language missing $key")
                assertTrue(text.isNotBlank(), "$language empty $key")
            }
        }
    }
}
