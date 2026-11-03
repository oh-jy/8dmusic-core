package jiangyi.spatial8d.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** 应用内语言；第三方歌曲、歌手、电台与 API 内容不经过翻译。 */
object AppLanguage {
    private const val PREF = "language"
    const val SYSTEM = "system"
    const val SIMPLIFIED = "zh-CN"
    const val TRADITIONAL = "zh-TW"
    const val ENGLISH = "en"

    fun current(context: Context): String =
        context.getSharedPreferences("spatial8d", Context.MODE_PRIVATE).getString(PREF, SYSTEM) ?: SYSTEM

    fun resolved(context: Context): String = when (val selected = current(context)) {
        SYSTEM -> when (Locale.getDefault().language) {
            "zh" -> if (Locale.getDefault().script == "Hant" || Locale.getDefault().country in setOf("TW", "HK", "MO"))
                TRADITIONAL else SIMPLIFIED
            else -> ENGLISH
        }
        else -> selected
    }

    fun select(context: Context, value: String) {
        context.getSharedPreferences("spatial8d", Context.MODE_PRIVATE).edit().putString(PREF, value).apply()
    }

    fun wrap(context: Context): Context {
        val locale = when (resolved(context)) {
            SIMPLIFIED -> Locale.SIMPLIFIED_CHINESE
            TRADITIONAL -> Locale.TRADITIONAL_CHINESE
            else -> Locale.ENGLISH
        }
        Locale.setDefault(locale)
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
    }

    fun text(context: Context, key: String): String {
        val value = entries[key] ?: return key
        return when (resolved(context)) {
            TRADITIONAL -> value.traditional
            ENGLISH -> value.english
            else -> value.simplified
        }
    }

    private data class Entry(val simplified: String, val traditional: String, val english: String)
    private fun e(zh: String, tw: String, en: String) = Entry(zh, tw, en)

    private val entries = mapOf(
        "app.name" to e("8D 空间音频", "8D 空間音訊", "8D Spatial Audio"),
        "app.subtitle" to e("HRTF 双耳引擎 · Android", "HRTF 雙耳引擎 · Android", "HRTF binaural engine · Android"),
        "language" to e("语言", "語言", "Language"),
        "language.system" to e("跟随系统", "跟隨系統", "System"),
        "language.simplified" to e("简体中文", "簡體中文", "Simplified Chinese"),
        "language.traditional" to e("繁体中文", "繁體中文", "Traditional Chinese"),
        "language.english" to e("English", "English", "English"),
        "theme.dark" to e("深色", "深色", "Dark"),
        "theme.light" to e("浅色", "淺色", "Light"),
        "theme.system" to e("跟随", "跟隨", "System"),
        "library" to e("曲库", "曲庫", "Library"),
        "close" to e("关闭", "關閉", "Close"),
        "cancel" to e("取消", "取消", "Cancel"),
        "delete" to e("删除", "刪除", "Delete"),
        "remove" to e("移除", "移除", "Remove"),
        "save" to e("保存", "儲存", "Save"),
        "scan" to e("扫描", "掃描", "Scan"),
        "expand" to e("展开 ▾", "展開 ▾", "Expand ▾"),
        "collapse" to e("收起 ▴", "收起 ▴", "Collapse ▴"),
        "ready" to e("就绪", "就緒", "Ready"),
        "no.song" to e("未选择歌曲", "未選擇歌曲", "No song selected"),
        "no.audio" to e("未加载音频", "未載入音訊", "No audio loaded"),
        "no.lyrics" to e("暂无匹配歌词", "暫無匹配歌詞", "No matching lyrics"),
        "instrumental" to e("纯音乐，无歌词", "純音樂，無歌詞", "Instrumental"),
        "song.no.lyrics" to e("该歌曲暂无歌词", "該歌曲暫無歌詞", "No lyrics for this song"),
        "lyrics.matching" to e("正在匹配在线歌词…", "正在匹配線上歌詞…", "Matching online lyrics…"),
        "lyrics.edit" to e("手动修正歌词", "手動修正歌詞", "Edit lyrics"),
        "lyrics.not.found" to e("未找到匹配歌词：", "未找到匹配歌詞：", "No matching lyrics: "),
        "space.style" to e("空间风格", "空間風格", "Space preset"),
        "space.choose" to e("选择空间风格 · 房间由小到大", "選擇空間風格 · 房間由小到大", "Choose space preset · by room size"),
        "direction" to e("旋转方向", "旋轉方向", "Direction"),
        "direction.cw" to e("顺时针", "順時針", "Clockwise"),
        "direction.ccw" to e("逆时针", "逆時針", "Counterclockwise"),
        "direction.random" to e("随机", "隨機", "Random"),
        "direction.hint" to e("随机模式每 2–8 秒自动切换顺/逆时针", "隨機模式每 2–8 秒自動切換順/逆時針", "Random mode changes direction every 2–8 seconds"),
        "trajectory" to e("运动轨迹", "運動軌跡", "Trajectory"),
        "trajectory.hint" to e("环绕速度 · 起伏 · 起手方位", "環繞速度 · 起伏 · 起手方位", "Orbit speed · elevation · starting position"),
        "room.acoustics" to e("房间声学", "房間聲學", "Room acoustics"),
        "room.hint" to e("空间大小 · 混响量感与尾音", "空間大小 · 混響量感與尾音", "Room size · reverb amount and tail"),
        "preset.name.hint" to e("自定义名称，留空自动编号", "自訂名稱，留空自動編號", "Custom name (blank for auto number)"),
        "preset.save" to e("保存风格", "儲存風格", "Save preset"),
        "transform" to e("音频变换", "音訊變換", "Audio transform"),
        "pitch.choose" to e("选择变声风格 · 从原声开始", "選擇變聲風格 · 從原聲開始", "Choose pitch preset · start from original"),
        "pitch" to e("变声", "變聲", "Pitch"),
        "original" to e("原声", "原聲", "Original"),
        "semitone" to e("半音", "半音", "semitones"),
        "reverse" to e("倒放", "倒放", "Reverse"),
        "reverse.hint" to e("从结尾向开头播放", "從結尾向開頭播放", "Play from end to beginning"),
        "speed" to e("倍速", "倍速", "Speed"),
        "speed.realtime" to e("实时", "即時", "Live"),
        "denoise" to e("降噪", "降噪", "Denoise"),
        "tape" to e("磁带模式", "磁帶模式", "Tape mode"),
        "tape.hint" to e("音高随时长一起变", "音高隨時長一起變", "Pitch changes with duration"),
        "new.duration" to e("新时长", "新時長", "New duration"),
        "off" to e("关闭", "關閉", "Off"),
        "curve" to e("空间曲线", "空間曲線", "Space curve"),
        "visualizer" to e("可视化", "視覺化", "Visualizer"),
        "lyrics" to e("歌词", "歌詞", "Lyrics"),
        "left.ear" to e("左耳", "左耳", "Left"),
        "right.ear" to e("右耳", "右耳", "Right"),
        "elevation" to e("仰角", "仰角", "Elevation"),
        "distance" to e("远近", "遠近", "Distance"),
        "front" to e("前", "前", "Front"),
        "back" to e("后", "後", "Back"),
        "left" to e("左", "左", "Left"),
        "right" to e("右", "右", "Right"),
        "wave" to e("波形", "波形", "Waveform"),
        "spectrum" to e("频谱", "頻譜", "Spectrum"),
        "loudness" to e("响度", "響度", "Loudness"),
        "phase" to e("相位", "相位", "Phase"),
        "spectrogram" to e("声谱", "聲譜", "Spectrogram"),
        "graph" to e("图谱", "圖譜", "PCM graph"),
        "particles" to e("粒子", "粒子", "Particles"),
        "travel" to e("穿梭", "穿梭", "Travel"),
        "popup" to e("弹窗", "彈窗", "Popup"),
        "export" to e("导出", "匯出", "Export"),
        "info" to e("说明", "說明", "Guide"),
        "info.app" to e("应用说明", "應用說明", "App guide"),
        "info.params" to e("参数说明", "參數說明", "Parameter guide"),
        "info.quick" to e("快速入门", "快速入門", "Quick start"),
        "info.quick.body" to e("1. 选择本地歌曲、FM 电台或在线曲库；\n2. 在空间风格里选一个预设；\n3. 点击播放后可通过音频图谱和可视化观察效果；\n4. 需要修正歌词时，在「歌词」长按歌词即可。", "1. 選擇本地歌曲、FM 電台或線上曲庫；\n2. 在空間風格裡選一個預設；\n3. 點擊播放後可透過音訊圖譜和視覺化觀察效果；\n4. 需要修正歌詞時，在「歌詞」長按歌詞即可。", "1. Choose a local song, FM station, or online track.\n2. Select a space preset.\n3. Play and explore the PCM graph and visualizer.\n4. Long-press a line in Lyrics to correct it."),
        "info.lyrics" to e("歌词", "歌詞", "Lyrics"),
        "info.lyrics.body" to e("同步歌词自动滚动；点击任意行可跳转；长按当前歌词进入搜索、候选与预览。", "同步歌詞自動捲動；點擊任意行可跳轉；長按當前歌詞進入搜尋、候選與預覽。", "Synced lyrics scroll automatically. Tap a line to seek; long-press to search and preview candidates."),
        "info.visualizer" to e("可视化", "視覺化", "Visualizer"),
        "info.visualizer.body" to e("波形 / 频谱 / 响度 / 相位可组合显示；声谱、图谱、粒子、穿梭为独占模式；后三者会叠加当前歌词。", "波形 / 頻譜 / 響度 / 相位可組合顯示；聲譜、圖譜、粒子、穿梭為獨佔模式；後三者會疊加當前歌詞。", "Waveform, spectrum, loudness and phase can be combined. Spectrogram, PCM graph, particles and travel are exclusive; lyrics appear on the last three."),
        "info.export" to e("导出与录音", "匯出與錄音", "Export and recording"),
        "info.export.body" to e("本地歌曲可导出 WAV / MP3；FM 电台可录制最终 8D 输出。", "本地歌曲可匯出 WAV / MP3；FM 電台可錄製最終 8D 輸出。", "Export local songs as WAV or MP3. Record the final 8D output from FM stations."),
        "info.legend" to e("曲线图例：蓝=左耳 橙=右耳 绿=仰角 紫=远近；曲线与引擎同源。", "曲線圖例：藍=左耳 橙=右耳 綠=仰角 紫=遠近；曲線與引擎同源。", "Curve: blue=left, orange=right, green=elevation, purple=distance. Curves use the same engine data."),
        "slider.period" to e("环绕周期", "環繞週期", "Orbit period"),
        "slider.elev" to e("上下幅度", "上下幅度", "Elevation"),
        "slider.depth" to e("远近距离", "遠近距離", "Depth"),
        "slider.phase" to e("起始相位", "起始相位", "Starting phase"),
        "slider.room" to e("空间大小", "空間大小", "Room size"),
        "slider.wet" to e("混响强度", "混響強度", "Reverb mix"),
        "slider.rt60" to e("混响时间", "混響時間", "Reverb time"),
        "slider.width" to e("声场宽度", "聲場寬度", "Stereo width"),
        "playlist" to e("播放列表", "播放清單", "Playlist"),
        "playlist.filter" to e("过滤：歌名 / 歌手 / 专辑", "篩選：歌名 / 歌手 / 專輯", "Filter: title / artist / album"),
        "playlist.all" to e("全部", "全部", "All"),
        "playlist.favorites" to e("收藏", "收藏", "Favorites"),
        "playlist.add" to e("＋ 添加", "＋ 新增", "+ Add"),
        "playlist.save" to e("保存列表", "儲存清單", "Save list"),
        "playlist.collapse" to e("收起", "收起", "Collapse"),
        "playlist.new" to e("新播放列表名称", "新播放清單名稱", "New playlist name"),
        "playlist.sort" to e("排序", "排序", "Sort"),
        "sort.name" to e("文件名", "檔名", "Title"),
        "sort.artist" to e("歌手", "歌手", "Artist"),
        "sort.album" to e("专辑", "專輯", "Album"),
        "sort.time" to e("时间", "時間", "Time"),
        "sort.manual" to e("自定义", "自訂", "Custom"),
        "mode.list" to e("列表循环", "清單循環", "Repeat all"),
        "mode.one" to e("单曲循环", "單曲循環", "Repeat one"),
        "mode.shuffle" to e("随机播放", "隨機播放", "Shuffle"),
        "play" to e("播放", "播放", "Play"),
        "pause" to e("暂停", "暫停", "Pause"),
        "previous" to e("上一首", "上一首", "Previous"),
        "next" to e("下一首", "下一首", "Next"),
        "playback.controls" to e("播放控制", "播放控制", "Playback controls"),
        "visualizer.popup" to e("音频可视化", "音訊視覺化", "Audio visualizer"),
        "visualizer.popup.subtitle" to e("实际输出 · 独立图表 · 全屏展示", "實際輸出 · 獨立圖表 · 全螢幕展示", "Actual output · independent charts · full screen"),
        "fm" to e("FM 电台", "FM 電台", "FM radio"),
        "online" to e("在线曲库", "線上曲庫", "Online library"),
        "online.aggregate" to e("聚合", "聚合", "All"),
        "search" to e("搜索", "搜尋", "Search"),
        "online.search.hint" to e("搜索歌曲 / 歌手", "搜尋歌曲 / 歌手", "Search songs / artists"),
        "online.searching" to e("正在加载…", "正在載入…", "Loading…"),
        "online.no.tracks" to e("没有找到可播放歌曲", "沒有找到可播放歌曲", "No playable songs found"),
        "online.tracks" to e("找到 %d 首", "找到 %d 首", "%d songs found"),
        "online.preview" to e("试听 ▸", "試聽 ▸", "Preview ▸"),
        "online.login" to e("账号认证", "帳號認證", "Sign in"),
        "online.logout" to e("退出登录", "登出", "Sign out"),
        "online.logged.in" to e("已登录 · 按账号权限播放", "已登入 · 按帳號權限播放", "Signed in · playback follows account access"),
        "online.logged.out" to e("未登录 · 公开曲目与免费内容", "未登入 · 公開曲目與免費內容", "Signed out · public and free content"),
        "online.footer" to e("仅试听平台允许的免费内容；QQ 音乐按当前账号权限播放，不绕过会员限制。", "僅試聽平台允許的免費內容；QQ 音樂按當前帳號權限播放，不繞過會員限制。", "Only platform-permitted free content. QQ playback follows account access; membership limits are not bypassed."),
        "online.unavailable" to e("在线曲库暂不可用", "線上曲庫暫不可用", "Online library unavailable"),
        "online.address" to e("正在获取 %s 的播放地址…", "正在取得 %s 的播放地址…", "Resolving playback URL for %s…"),
        "online.address.failed" to e("播放地址获取失败", "播放地址取得失敗", "Could not resolve playback URL"),
        "online.status" to e("检索各平台公开曲目，按当前账号权限播放", "檢索各平台公開曲目，按當前帳號權限播放", "Search public tracks; playback follows account access"),
        "fm.status" to e("选择 FM 电台，或粘贴自己的直播流地址", "選擇 FM 電台，或貼上自己的直播串流地址", "Choose an FM station or paste your own stream URL"),
        "fm.search.hint" to e("搜索电台 / 省市 / 类型", "搜尋電台 / 省市 / 類型", "Search stations / regions / genres"),
        "fm.custom" to e("流地址 / 麦克风录音 ▸", "串流地址 / 麥克風錄音 ▸", "Stream URL / microphone recording ▸"),
        "fm.url.hint" to e("粘贴 MP3/AAC 直播流地址", "貼上 MP3/AAC 直播串流地址", "Paste MP3/AAC stream URL"),
        "fm.recording.note" to e("电台自动尝试多路备用源；录音会保存最终 8D 输出。", "電台自動嘗試多路備用來源；錄音會儲存最終 8D 輸出。", "Backup streams are tried automatically. Recordings save the final 8D output."),
        "region" to e("地区", "地區", "Region"),
        "city" to e("城市", "城市", "City"),
        "genre" to e("类型", "類型", "Genre"),
        "all" to e("全部", "全部", "All"),
        "param.period" to e("1–20 秒一圈。改小 = 转得更快。只改变左右耳能量/时间差交替的快慢，音色、响度都不变，是纯「运动速度」参数。", "1–20 秒一圈。改小 = 轉得更快。只改變左右耳能量/時間差交替的快慢，音色、響度都不變，是純「運動速度」參數。", "One orbit takes 1–20 seconds. Lower values rotate faster. Only the timing of interaural energy and delay changes, not timbre or loudness."),
        "param.elev" to e("0–80°。0° = 只在水平面绕圈；加大 = 耳廓陷波随仰角前后移动，出现「头顶掠过 / 沉到脚下」的感觉。", "0–80°。0° = 只在水平面繞圈；加大 = 耳廓陷波隨仰角前後移動，出現「頭頂掠過 / 沉到腳下」的感覺。", "0–80°. Zero keeps motion horizontal. Higher values move pinna cues with elevation for overhead and below-head movement."),
        "param.depth" to e("0% = 等距绕圈；加大 = 声源周期性退远再拉近。电平、空气吸收与直达声/混响比例同时变化。", "0% = 等距繞圈；加大 = 聲源週期性退遠再拉近。電平、空氣吸收與直達聲/混響比例同時變化。", "0% keeps a constant radius. Higher values periodically move the source closer and farther, changing level, air absorption and direct/reverb balance."),
        "param.room" to e("3–50 米决定混响首反射时间与尾音密度。加大 = 更空旷、反射更晚、尾巴更暗；直达声电平不变。", "3–50 米決定混響首反射時間與尾音密度。加大 = 更空曠、反射更晚、尾巴更暗；直達聲電平不變。", "A 3–50 m room sets early reflection timing and tail density. Larger rooms sound more open with later, darker reflections; direct level is unchanged."),
        "param.wet" to e("湿声比例。0% = 全干、贴脸但没空间；加大 = 包围感强，过头会发糊。", "濕聲比例。0% = 全乾、貼臉但沒空間；加大 = 包圍感強，過頭會發糊。", "Wet signal ratio. 0% is fully dry. More reverb increases immersion but can blur detail."),
        "param.rt60" to e("尾巴衰减 60dB 的时长，范围 0.2–5.0s。只改尾音长度，不改直达声与音色。", "尾巴衰減 60dB 的時長，範圍 0.2–5.0s。只改尾音長度，不改直達聲與音色。", "Time for the tail to decay by 60 dB, from 0.2 to 5 seconds. Changes tail length only, not direct sound or timbre."),
        "param.phase" to e("决定歌曲第一个字落在头的哪个方位。只改开场落点，运动规律不变。", "決定歌曲第一個字落在頭的哪個方位。只改開場落點，運動規律不變。", "Sets where the opening sound is located. Changes only the starting point, not the motion pattern."),
        "param.direction" to e("顺时针 / 逆时针固定绕行；随机模式每隔 2–8 秒自动换向，并保持换向瞬间的角度连续。", "順時針 / 逆時針固定繞行；隨機模式每隔 2–8 秒自動換向，並保持換向瞬間的角度連續。", "Fixed clockwise or counterclockwise motion. Random mode changes direction every 2–8 seconds without angular jumps."),
        "param.width" to e("0% = 双耳完全居中；100% = 标准 HRTF 双耳差异；最高 150% = 更强的左右分离和包围感。", "0% = 雙耳完全居中；100% = 標準 HRTF 雙耳差異；最高 150% = 更強的左右分離和包圍感。", "0% centers both ears; 100% uses standard HRTF differences; up to 150% increases separation and immersion.")
    )
}
