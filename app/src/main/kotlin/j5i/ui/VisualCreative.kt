package j5i.spatial8d.ui

import org.json.JSONObject
import org.json.JSONArray

internal data class VisualTouch(val x: Float, val y: Float, val positionMs: Long, val drag: Boolean = false)

internal data class VisualCreative(val hue: Int = 0, val overlay: Int = 0, val watermark: Boolean = false, val mode: Int? = null,
    val backgroundAlpha: Int = 43, val density: Int = 100, val brightness: Int = 100, val watermarkPosition: Int = 3,
    val intensity: Int = 100, val backgroundMask: Boolean = false, val watermarkText: String = "8D Music",
    val backgroundBlur: Int = 0, val outroText: String = "", val outroMotif: Int = 3, val outroDuration: Int = 2,
    val outroMotifs: Int = when (outroMotif) { 1 -> 1; 2 -> 2; else -> 7 },
    val randomVisual: Boolean = false, val randomMinSeconds: Int = 5, val randomMaxSeconds: Int = 10,
    val randomModes: List<Int> = listOf(0) + exclusiveVisualModes.map { it.second }.filter { it != 39 }) {
    fun encode(): String = JSONObject().put("version", 1).put("hue", hue).put("overlay", overlay)
        .put("watermark", watermark).put("backgroundAlpha", backgroundAlpha).put("density", density)
        .put("brightness", brightness).put("watermarkPosition", watermarkPosition).put("intensity", intensity)
        .put("backgroundMask", backgroundMask).put("watermarkText", watermarkText).put("backgroundBlur", backgroundBlur)
        .put("outroText", outroText).put("outroMotif", outroMotif).put("outroDuration", outroDuration).put("outroMotifs", outroMotifs)
        .put("randomVisual", randomVisual).put("randomMinSeconds", randomMinSeconds).put("randomMaxSeconds", randomMaxSeconds)
        .put("randomModes", JSONArray(randomModes))
        .apply { mode?.let { put("mode", it) } }.toString()

    companion object {
        fun decode(text: String): VisualCreative {
            val json = JSONObject(text)
            require(json.getInt("version") == 1)
            val hue = json.getInt("hue")
            val overlay = json.getInt("overlay")
            val mode = if (json.has("mode")) json.getInt("mode") else null
            val backgroundAlpha = json.optInt("backgroundAlpha", 43)
            val density = json.optInt("density", 100)
            val brightness = json.optInt("brightness", 100)
            val watermarkPosition = json.optInt("watermarkPosition", 3)
            val intensity = json.optInt("intensity", 100)
            val watermarkText = json.optString("watermarkText", "8D Music").trim().take(32)
            val backgroundBlur = json.optInt("backgroundBlur", 0)
            val outroText = json.optString("outroText", "").trim().take(24)
            val outroMotif = json.optInt("outroMotif", 3)
            val outroDuration = json.optInt("outroDuration", 2)
            val outroMotifs = json.optInt("outroMotifs", when (outroMotif) { 1 -> 1; 2 -> 2; else -> 7 })
            val randomMinSeconds = json.optInt("randomMinSeconds", 5)
            val randomMaxSeconds = json.optInt("randomMaxSeconds", 10)
            val randomArray = json.optJSONArray("randomModes")
            val randomModes = if (randomArray == null) listOf(0) + exclusiveVisualModes.map { it.second }.filter { it != 39 }
                else (0 until randomArray.length()).map { randomArray.getInt(it) }
            require(hue in 0..359 && overlay in listOf(0, 18, 19, 30))
            require(mode == null || mode == 0 || exclusiveVisualModes.any { it.second == mode })
            require(backgroundAlpha in 0..100 && density in 25..200 && brightness in 25..150 && watermarkPosition in 0..3 && intensity in 25..200 &&
                backgroundBlur in 0..24 && outroMotif in 1..3 && outroDuration in 1..5 && outroMotifs in 1..31)
            require(randomMinSeconds in 1..60 && randomMaxSeconds in randomMinSeconds..60 && randomModes.isNotEmpty() &&
                randomModes.distinct().size == randomModes.size && randomModes.all { it == 0 || exclusiveVisualModes.any { mode -> mode.second == it } })
            return VisualCreative(hue, overlay, json.optBoolean("watermark", false), mode, backgroundAlpha, density,
                brightness, watermarkPosition, intensity, json.optBoolean("backgroundMask", false), watermarkText, backgroundBlur,
                outroText, outroMotif, outroDuration, outroMotifs, json.optBoolean("randomVisual", false), randomMinSeconds, randomMaxSeconds, randomModes)
        }
    }
}
