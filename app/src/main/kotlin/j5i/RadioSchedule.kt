package j5i.spatial8d

import android.app.Dialog
import android.widget.LinearLayout
import android.widget.ScrollView
import j5i.spatial8d.core.RadioStation
import j5i.spatial8d.ui.ThemeUi
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal data class RadioScheduleSource(val provider: String, val id: String)
internal data class RadioProgram(val name: String, val start: String, val end: String, val hosts: String)

internal fun MainActivity.openCurrentRadioSchedule() {
    val station = importedStations().firstOrNull { it.id == currentStationId }
    if (station != null) showRadioSchedule(station) else toast("该电台暂无可用节目单")
}

internal fun isRadioProgramCurrent(program: RadioProgram, time: String): Boolean =
    if (program.end <= program.start) time >= program.start || time < program.end
    else time >= program.start && time < program.end

internal fun radioScheduleSources(urls: List<String>): List<RadioScheduleSource> = urls.mapNotNull { raw ->
    runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase() ?: return@runCatching null
        val pattern = when (host) {
            "live.xmcdn.com" -> "^/live/(\\d+)/"
            "live.ximalaya.com" -> "^/radio-first-page-app/live/(\\d+)/"
            "ls.qingting.fm", "lhttp.qingting.fm", "lhttp.qtfm.cn", "lhttp-hw.qtfm.cn" -> "^/live/(\\d+)(?:/|\\.m3u8)"
            else -> return@runCatching null
        }
        Regex(pattern).find(uri.path)?.groupValues?.get(1)?.let {
            RadioScheduleSource(if (host.contains("qingting") || host.contains("qtfm")) "蜻蜓 FM" else "喜马拉雅", it)
        }
    }.getOrNull()
}.distinct().sortedBy { if (it.provider == "喜马拉雅") 0 else 1 }

internal fun parseRadioSchedule(source: RadioScheduleSource, json: JSONObject, dayOfWeek: Int): List<List<RadioProgram>> {
    val lists = if (source.provider == "喜马拉雅") {
        val result = json.getJSONObject("result")
        listOf("yesterdaySchedules", "todaySchedules", "tomorrowSchedules").map { result.optJSONArray(it) }
    } else {
        val result = json.getJSONObject("pList")
        listOf(-1, 0, 1).map { offset -> result.optJSONArray((((dayOfWeek - 1 + offset + 7) % 7) + 1).toString()) }
    }
    return lists.map { array ->
        (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val item = array!!.optJSONObject(index) ?: return@mapNotNull null
            val ximalaya = source.provider == "喜马拉雅"
            val name = item.optString(if (ximalaya) "programName" else "title")
            val start = item.optString(if (ximalaya) "startTime" else "start_time").take(5)
            val end = item.optString(if (ximalaya) "endTime" else "end_time").take(5)
            val people = item.optJSONArray(if (ximalaya) "announcerList" else "broadcasters") ?: JSONArray()
            val hosts = (0 until people.length()).mapNotNull { people.optJSONObject(it)?.optString(if (ximalaya) "announcerName" else "username")?.takeIf(String::isNotBlank) }.joinToString("、")
            if (name.isBlank() || start.length != 5 || end.length != 5) null else RadioProgram(name, start, end, hosts)
        }
    }
}

internal fun MainActivity.showRadioSchedule(station: RadioStation) {
    val sources = radioScheduleSources(station.sources.map { it.url })
    lateinit var dialog: Dialog
    lateinit var content: LinearLayout
    dialog = ThemeUi.showSheet(this, palette, "${station.name} · 节目单", fullHeight = true, scrollable = false) {
        content = this
    }
    dialog.show()
    if (sources.isEmpty()) {
        content.addView(ThemeUi.text(this, "该电台暂无可用节目单", 13f, palette, role = ThemeUi.ROLE_SUB))
        return
    }
    val status = ThemeUi.text(this, "正在加载节目单…", 13f, palette, role = ThemeUi.ROLE_SUB)
    content.addView(status)
    Thread {
        val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        val result = sources.firstNotNullOfOrNull { source ->
            runCatching {
                val url = if (source.provider == "喜马拉雅") "https://mobile.ximalaya.com/radio-first-page-app/v1/getProgramSchedules?device=ios&radioId=${source.id}"
                    else "https://webapi.qtfm.cn/api/pc/radio/${source.id}"
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                response.use {
                    check(it.isSuccessful)
                    val days = parseRadioSchedule(source, JSONObject(it.body!!.string()), now.get(Calendar.DAY_OF_WEEK))
                    if (days.all(List<RadioProgram>::isEmpty)) null else source to days
                }
            }.getOrNull()
        }
        runOnUiThread {
            if (!dialog.isShowing || isFinishing || isDestroyed) return@runOnUiThread
            content.removeAllViews()
            if (result == null) {
                content.addView(ThemeUi.text(this, "节目单暂不可用，请稍后重试", 13f, palette, role = ThemeUi.ROLE_SUB))
                return@runOnUiThread
            }
            val (source, days) = result
            content.addView(ThemeUi.text(this, "数据来源：${source.provider}", 11f, palette, role = ThemeUi.ROLE_SUB))
            val tabs = ThemeUi.row(this)
            content.addView(tabs)
            val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply { addView(rows) }
            content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            fun render(day: Int) {
                tabs.removeAllViews()
                listOf("昨天", "今天", "明天").forEachIndexed { index, label ->
                    tabs.addView(ThemeUi.button(this, palette, label, if (index == day) ThemeUi.ROLE_ACCENT_FILL else ThemeUi.ROLE_CONTROL) { render(index) }, LinearLayout.LayoutParams(0, ThemeUi.dp(this, 40f), 1f))
                }
                rows.removeAllViews()
                val clock = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
                val time = "%02d:%02d".format(clock.get(Calendar.HOUR_OF_DAY), clock.get(Calendar.MINUTE))
                var active: android.view.View? = null
                days[day].forEach { program ->
                    val current = day == 1 && isRadioProgramCurrent(program, time)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(ThemeUi.dp(this@showRadioSchedule, 8f), ThemeUi.dp(this@showRadioSchedule, 8f), ThemeUi.dp(this@showRadioSchedule, 8f), ThemeUi.dp(this@showRadioSchedule, 8f))
                        if (current) background = ThemeUi.bgDrawable(this@showRadioSchedule, palette.surfaceAlt, 8f)
                        addView(ThemeUi.text(this@showRadioSchedule, "${if (current) "▶ 直播中 · " else ""}${program.name}", 13f, palette, role = if (current) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT))
                        addView(ThemeUi.text(this@showRadioSchedule, "${program.start} - ${program.end}${if (program.hosts.isNotBlank()) " · ${program.hosts}" else ""}", 11f, palette, role = ThemeUi.ROLE_SUB))
                    }
                    rows.addView(row)
                    if (current) active = row
                }
                if (days[day].isEmpty()) rows.addView(ThemeUi.text(this, "暂无节目", 13f, palette, role = ThemeUi.ROLE_SUB))
                scroll.post { scroll.scrollTo(0, active?.top ?: 0) }
            }
            render(1)
        }
    }.start()
}
