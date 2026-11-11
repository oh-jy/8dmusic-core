package j5i.spatial8d

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioScheduleTest {
    @Test fun sourcesRequireKnownHostsAndPaths() {
        val sources = radioScheduleSources(listOf("https://live.xmcdn.com/live/132/64.m3u8", "https://lhttp-hw.qtfm.cn/live/1502/64k.mp3", "https://evil.example/live/123/64k.mp3"))
        assertEquals(listOf(RadioScheduleSource("喜马拉雅", "132"), RadioScheduleSource("蜻蜓 FM", "1502")), sources)
    }

    @Test fun qingtingDaysWrapAndProgramsParse() {
        val data = JSONObject("""{"pList":{"7":[{"title":"晚间","start_time":"22:00:00","end_time":"00:00:00","broadcasters":[{"username":"主播"}]}],"1":[],"2":[]}}""")
        val days = parseRadioSchedule(RadioScheduleSource("蜻蜓 FM", "1502"), data, 1)
        assertEquals(RadioProgram("晚间", "22:00", "00:00", "主播"), days[0].single())
        assertTrue(days[1].isEmpty())
        assertTrue(isRadioProgramCurrent(days[0].single(), "23:00"))
        assertFalse(isRadioProgramCurrent(days[0].single(), "12:00"))
    }

    @Test fun ximalayaDaysParse() {
        val data = JSONObject("""{"result":{"todaySchedules":[{"programName":"节目","startTime":"12:00","endTime":"13:00","announcerList":[{"announcerName":"佳蕊"}]}]}}""")
        val days = parseRadioSchedule(RadioScheduleSource("喜马拉雅", "132"), data, 4)
        assertEquals(RadioProgram("节目", "12:00", "13:00", "佳蕊"), days[1].single())
    }
}
