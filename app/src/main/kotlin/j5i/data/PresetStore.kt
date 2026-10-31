package j5i.spatial8d.data

import android.content.Context
import j5i.spatial8d.core.SpacePreset
import org.json.JSONArray
import org.json.JSONObject

class PresetStore(context: Context) {
    private val preferences = context.getSharedPreferences("spatial8d", Context.MODE_PRIVATE)

    fun load(): List<SpacePreset> = try {
        val array = JSONArray(preferences.getString("presets", "[]") ?: "[]")
        (0 until array.length()).map { index ->
            val p = array.getJSONObject(index)
            SpacePreset(
                p.getString("name"), p.optString("note", "自定义空间风格"),
                p.getDouble("period"), p.getDouble("elev"), p.getDouble("depth"),
                p.getDouble("room"), p.getDouble("wet"), p.getDouble("rt60"),
                p.getDouble("phase"), p.getDouble("direction"), p.optDouble("width", 100.0),
                p.optBoolean("raw")
            )
        }
    } catch (_: Exception) { emptyList() }

    fun save(presets: List<SpacePreset>) {
        val array = JSONArray()
        presets.forEach { p ->
            array.put(JSONObject().apply {
                put("name", p.name)
                put("note", p.note)
                put("period", p.period)
                put("elev", p.elev)
                put("depth", p.depth)
                put("room", p.room)
                put("wet", p.wet)
                put("rt60", p.rt60)
                put("phase", p.phase)
                put("direction", p.direction)
                put("width", p.width)
                put("raw", p.raw)
            })
        }
        preferences.edit().putString("presets", array.toString()).apply()
    }
}
