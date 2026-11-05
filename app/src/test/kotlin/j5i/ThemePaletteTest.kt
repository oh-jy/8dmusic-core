package j5i.spatial8d.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.math.pow

class ThemePaletteTest {
    @Test fun colorThemesHaveDistinctDarkAndLightSurfaces() {
        val previous = ThemeUi.colorTheme
        try {
            ThemeUi.colorTheme = 0
            val dark = ThemeUi.palette(true)
            val light = ThemeUi.palette(false)
            assertNotEquals(dark.background, light.background)
            val palettes = (0..6).map { theme ->
                ThemeUi.colorTheme = theme
                ThemeUi.palette(true) to ThemeUi.palette(false)
            }
            assertEquals(7, palettes.map { it.first.background }.distinct().size)
            assertEquals(7, palettes.map { it.second.background }.distinct().size)
            palettes.flatMap { listOf(it.first, it.second) }.forEach { p ->
                assertTrue(contrast(p.text, p.surface) >= 4.5)
                assertTrue(contrast(p.sub, p.surface) >= 4.5)
                assertTrue(contrast(p.darkInk, p.accent) >= 4.5)
            }
        } finally {
            ThemeUi.colorTheme = previous
        }
    }

    @Test fun classicBlueControlsStandOutFromBackground() {
        val previous = ThemeUi.colorTheme
        try {
            ThemeUi.colorTheme = 0
            listOf(true, false).map(ThemeUi::palette).forEach { p ->
                assertTrue(contrast(p.control, p.background) >= 1.15)
                assertTrue(contrast(p.controlText, p.control) >= 4.5)
            }
        } finally {
            ThemeUi.colorTheme = previous
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color ushr shift) and 255) / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
