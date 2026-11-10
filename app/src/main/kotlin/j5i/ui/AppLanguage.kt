package j5i.spatial8d.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
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

    fun systemLanguage(locale: Locale): String = when (locale.language) {
        "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) TRADITIONAL else SIMPLIFIED
        "en" -> ENGLISH
        else -> SIMPLIFIED
    }

    fun resolved(context: Context): String = when (val selected = current(context)) {
        SYSTEM -> systemLanguage(Resources.getSystem().configuration.locales[0] ?: Locale.SIMPLIFIED_CHINESE)
        SIMPLIFIED, TRADITIONAL, ENGLISH -> selected
        else -> SIMPLIFIED
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
        "settings" to e("设置", "設定", "Settings"),
        "theme" to e("外观颜色", "外觀顏色", "Appearance"),
        "theme.color" to e("主题配色", "主題配色", "Theme color"),
        "theme.blue" to e("经典蓝", "經典藍", "Classic blue"),
        "theme.green" to e("清新绿", "清新綠", "Fresh green"),
        "theme.orange" to e("暖阳橙", "暖陽橙", "Sunny orange"),
        "theme.neutral" to e("柔和灰", "柔和灰", "Soft gray"),
        "theme.rose" to e("玫瑰红", "玫瑰紅", "Rose red"),
        "theme.violet" to e("暮光紫", "暮光紫", "Twilight violet"),
        "theme.slate" to e("雾霭蓝", "霧靄藍", "Misty blue"),
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
        "lyrics.timeout" to e("歌词获取超时，可手动搜索", "歌詞取得逾時，可手動搜尋", "Lyrics request timed out. Search manually."),
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
        "trajectory.orbit" to e("环绕", "環繞", "Orbit"),
        "trajectory.figure8" to e("8 字", "8 字", "Figure eight"),
        "trajectory.vertical" to e("垂直摆动", "垂直擺動", "Vertical"),
        "trajectory.spiral" to e("螺旋升降", "螺旋升降", "Spiral"),
        "trajectory.wander" to e("平滑漫游", "平滑漫遊", "Smooth wander"),
        "trajectory.raw" to e("原始音频直通时不启用空间轨迹", "原始音訊直通時不啟用空間軌跡", "Spatial trajectories are bypassed in original audio mode"),
        "trajectory.raw.short" to e("直通未启用", "直通未啟用", "bypassed"),
        "room.acoustics" to e("房间声学", "房間聲學", "Room acoustics"),
        "room.hint" to e("空间大小 · 混响量感与尾音", "空間大小 · 混響量感與尾音", "Room size · reverb amount and tail"),
        "preset.name.hint" to e("自定义名称，留空自动编号", "自訂名稱，留空自動編號", "Custom name (blank for auto number)"),
        "preset.save" to e("保存风格", "儲存風格", "Save preset"),
        "transform" to e("音频变换", "音訊變換", "Audio transform"),
        "pitch.choose" to e("选择变声风格 · 从原声开始", "選擇變聲風格 · 從原聲開始", "Choose pitch preset · start from original"),
        "pitch" to e("变声", "變聲", "Pitch"),
        "original" to e("原声", "原聲", "Original"),
        "label.default" to e("默认", "預設", "Default"),
        "semitone" to e("半音", "半音", "semitones"),
        "reverse" to e("倒放", "倒放", "Reverse"),
        "reverse.hint" to e("从结尾向开头播放", "從結尾向開頭播放", "Play from end to beginning"),
        "speed" to e("倍速", "倍速", "Speed"),
        "speed.realtime" to e("实时", "即時", "Live"),
        "denoise" to e("降噪", "降噪", "Denoise"),
        "sound.clarity" to e("声音清晰度", "聲音清晰度", "Sound clarity"),
        "tape" to e("磁带模式", "磁帶模式", "Tape mode"),
        "tape.hint" to e("音高随时长一起变", "音高隨時長一起變", "Pitch changes with duration"),
        "new.duration" to e("新时长", "新時長", "New duration"),
        "off" to e("关闭", "關閉", "Off"),
        "curve" to e("空间曲线", "空間曲線", "Space curve"),
        "visualizer" to e("可视化", "視覺化", "Visualizer"),
        "analysis" to e("音频分析", "音訊分析", "Audio analysis"),
        "metadata" to e("歌曲信息", "歌曲資訊", "Song info"),
        "metadata.edit" to e("编辑", "編輯", "Edit"),
        "metadata.title" to e("歌名", "歌名", "Title"),
        "metadata.artist" to e("歌手", "歌手", "Artist"),
        "metadata.album" to e("专辑", "專輯", "Album"),
        "metadata.save" to e("保存信息", "儲存資訊", "Save metadata"),
        "metadata.saved" to e("歌曲信息已保存", "歌曲資訊已儲存", "Song metadata saved"),
        "metadata.read.only" to e("当前文件不可写，无法保存到原文件", "目前檔案不可寫，無法儲存到原檔案", "This file is not writable"),
        "driving" to e("驾驶", "駕駛", "Drive"),
        "driving.close" to e("退出", "退出", "Exit"),
        "driving.hint" to e("左右滑动切换视图", "左右滑動切換視圖", "Swipe to change view"),
        "driving.next.view" to e("切换可视化", "切換視覺化", "Change visualization"),
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
        "info.quick.body" to e("1. 选择本地歌曲；\n2. 在空间风格里选一个预设；\n3. 点击播放后可通过音频图谱和可视化观察效果；\n4. 播放时自动匹配歌词，需要修正时在「歌词」长按歌词即可。", "1. 選擇本地歌曲；\n2. 在空間風格裡選一個預設；\n3. 點擊播放後可透過音訊圖譜和視覺化觀察效果；\n4. 播放時自動匹配歌詞，需要修正時在「歌詞」長按歌詞即可。", "1. Choose a local song.\n2. Select a space preset.\n3. Play and explore the PCM graph and visualizer.\n4. Lyrics are matched automatically; long-press a line to correct them."),
        "info.privacy" to e("联网与内容", "聯網與內容", "Network & content"),
        "info.privacy.body" to e("使用在线音源、歌词或 FM 时，所选服务会收到搜索词、播放地址和设备网络信息；第三方账号认证仅在主动登录时进行。可播放不等于允许录音、导出或再分享，请遵守来源授权。", "使用線上音源、歌詞或 FM 時，所選服務會收到搜尋詞、播放地址和裝置網路資訊；第三方帳號認證僅在主動登入時進行。可播放不等於允許錄音、匯出或再分享，請遵守來源授權。", "Online music, lyrics and FM services receive searches, playback URLs and device network information when used. Third-party sign-in occurs only on your request. Playback does not grant recording, export or redistribution rights."),
        "info.lyrics" to e("歌词", "歌詞", "Lyrics"),
        "info.lyrics.body" to e("同步歌词自动滚动；点击任意行可跳转；长按当前歌词进入搜索、候选与预览。", "同步歌詞自動捲動；點擊任意行可跳轉；長按當前歌詞進入搜尋、候選與預覽。", "Synced lyrics scroll automatically. Tap a line to seek; long-press to search and preview candidates."),
        "info.visualizer" to e("可视化", "視覺化", "Visualizer"),
        "info.visualizer.body" to e("波形 / 频谱 / 响度 / 相位可组合显示；声谱、图谱、粒子、穿梭为独占模式；后三者会叠加当前歌词。", "波形 / 頻譜 / 響度 / 相位可組合顯示；聲譜、圖譜、粒子、穿梭為獨佔模式；後三者會疊加當前歌詞。", "Waveform, spectrum, loudness and phase can be combined. Spectrogram, PCM graph, particles and travel are exclusive; lyrics appear on the last three."),
        "info.export" to e("导出", "匯出", "Export"),
        "info.export.body" to e("本地歌曲可导出 WAV / MP3。", "本地歌曲可匯出 WAV / MP3。", "Export local songs as WAV or MP3."),
        "info.legend" to e("曲线图例：蓝=左耳 橙=右耳 绿=仰角 紫=远近；曲线与引擎同源。", "曲線圖例：藍=左耳 橙=右耳 綠=仰角 紫=遠近；曲線與引擎同源。", "Curve: blue=left, orange=right, green=elevation, purple=distance. Curves use the same engine data."),
        "slider.period" to e("环绕周期", "環繞週期", "Orbit period"),
        "slider.elev" to e("上下幅度", "上下幅度", "Elevation"),
        "slider.sourceDistance" to e("声源距离", "聲源距離", "Source distance"),
        "slider.depth" to e("远近幅度", "遠近幅度", "Depth range"),
        "slider.phase" to e("起始相位", "起始相位", "Starting phase"),
        "slider.room" to e("空间大小", "空間大小", "Room size"),
        "slider.wet" to e("混响强度", "混響強度", "Reverb mix"),
        "slider.rt60" to e("混响时间", "混響時間", "Reverb time"),
        "slider.width" to e("声场宽度", "聲場寬度", "Stereo width"),
        "slider.direction" to e("旋转方向", "旋轉方向", "Direction"),
        "unit.seconds" to e(" 秒", " 秒", " s"),
        "unit.meters" to e(" 米", " 米", " m"),
        "visual.wave" to e("波形  L / R", "波形  L / R", "Waveform  L / R"),
        "visual.spectrum" to e("频谱  40 Hz - 20 kHz", "頻譜  40 Hz - 20 kHz", "Spectrum  40 Hz - 20 kHz"),
        "visual.loudness" to e("响度  RMS %.1f dBFS   峰值 %.1f dBFS", "響度  RMS %.1f dBFS   峰值 %.1f dBFS", "Loudness  RMS %.1f dBFS   Peak %.1f dBFS"),
        "visual.phase" to e("相位  %+.2f   -1 反相 / +1 同相", "相位  %+.2f   -1 反相 / +1 同相", "Phase  %+.2f   -1 inverted / +1 in phase"),
        "visual.spectrogram" to e("声谱  时间向下 · 频率向右", "聲譜  時間向下 · 頻率向右", "Spectrogram  Time down · frequency right"),
        "visual.empty" to e("请至少开启一个图表", "請至少開啟一個圖表", "Enable at least one chart"),
        "preset.custom" to e("自定义", "自訂", "Custom"),
        "preset.custom.note" to e("当前参数为手动调整，未匹配任何预设。", "目前參數為手動調整，未匹配任何預設。", "Manually adjusted; no preset matches."),
        "status.preparing" to e("正在准备播放…", "正在準備播放…", "Preparing playback…"),
        "status.playing" to e("正在播放", "正在播放", "Playing"),
        "status.paused" to e("已暂停", "已暫停", "Paused"),
        "status.ready" to e("已就绪，可播放", "已就緒，可播放", "Ready to play"),
        "status.auto.next" to e("自动续播", "自動續播", "Auto next"),
        "playlist" to e("播放列表", "播放清單", "Playlist"),
        "edit.metadata" to e("编辑歌曲信息", "編輯歌曲資訊", "Edit song information"),
        "seek.position" to e("播放进度", "播放進度", "Playback position"),
        "playlist.filter" to e("过滤：歌名 / 歌手 / 专辑", "篩選：歌名 / 歌手 / 專輯", "Filter: title / artist / album"),
        "playlist.all" to e("全部", "全部", "All"),
        "playlist.favorites" to e("收藏", "收藏", "Favorites"),
        "playlist.add" to e("+添加文件", "+新增檔案", "+ Add file"),
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
        "param.sourceDistance" to e("设置固定虚拟距离基准；1 米保持原有声音，增大可减弱贴耳感。数值未经耳机校准，并非真实测距。", "設定固定虛擬距離基準；1 米保持原有聲音，增大可減弱貼耳感。數值未經耳機校準，並非真實測距。", "Sets a virtual distance baseline. 1 m preserves the original sound; higher values reduce proximity. Not a calibrated physical distance."),
        "param.depth" to e("0% = 等距绕圈；加大 = 声源周期性退远再拉近。电平、空气吸收与直达声/混响比例同时变化。", "0% = 等距繞圈；加大 = 聲源週期性退遠再拉近。電平、空氣吸收與直達聲/混響比例同時變化。", "0% keeps a constant radius. Higher values periodically move the source closer and farther, changing level, air absorption and direct/reverb balance."),
        "param.room" to e("3–50 米决定混响首反射时间与尾音密度。加大 = 更空旷、反射更晚、尾巴更暗；直达声电平不变。", "3–50 米決定混響首反射時間與尾音密度。加大 = 更空曠、反射更晚、尾巴更暗；直達聲電平不變。", "A 3–50 m room sets early reflection timing and tail density. Larger rooms sound more open with later, darker reflections; direct level is unchanged."),
        "param.wet" to e("湿声比例。0% = 全干、贴脸但没空间；加大 = 包围感强，过头会发糊。", "濕聲比例。0% = 全乾、貼臉但沒空間；加大 = 包圍感強，過頭會發糊。", "Wet signal ratio. 0% is fully dry. More reverb increases immersion but can blur detail."),
        "param.rt60" to e("尾巴衰减 60dB 的时长，范围 0.2–5.0s。只改尾音长度，不改直达声与音色。", "尾巴衰減 60dB 的時長，範圍 0.2–5.0s。只改尾音長度，不改直達聲與音色。", "Time for the tail to decay by 60 dB, from 0.2 to 5 seconds. Changes tail length only, not direct sound or timbre."),
        "param.phase" to e("决定歌曲第一个字落在头的哪个方位。只改开场落点，运动规律不变。", "決定歌曲第一個字落在頭的哪個方位。只改開場落點，運動規律不變。", "Sets where the opening sound is located. Changes only the starting point, not the motion pattern."),
        "param.direction" to e("顺时针 / 逆时针固定绕行；随机模式每隔 2–8 秒自动换向，并保持换向瞬间的角度连续。", "順時針 / 逆時針固定繞行；隨機模式每隔 2–8 秒自動換向，並保持換向瞬間的角度連續。", "Fixed clockwise or counterclockwise motion. Random mode changes direction every 2–8 seconds without angular jumps."),
        "param.width" to e("0% = 双耳完全居中；100% = 标准 HRTF 双耳差异；最高 150% = 更强的左右分离和包围感。", "0% = 雙耳完全居中；100% = 標準 HRTF 雙耳差異；最高 150% = 更強的左右分離和包圍感。", "0% centers both ears; 100% uses standard HRTF differences; up to 150% increases separation and immersion.")
    )

    private val presetNames = listOf(
        e("原始音频 · 直通", "原始音訊 · 直通", "Original audio · Passthrough"),
        e("标准模式 · 默认", "標準模式 · 預設", "Standard mode · Default"),
        e("室内客厅 · 宽场", "室內客廳 · 寬場", "Living room · Wide"),
        e("贴身耳语 · 小房间", "貼身耳語 · 小房間", "Close whisper · Small room"),
        e("舞台前排 · 现场", "舞台前排 · 現場", "Front row · Live"),
        e("音乐厅包厢 · 包围", "音樂廳包廂 · 包圍", "Concert box · Immersive"),
        e("大教堂穹顶 · 极空", "大教堂穹頂 · 極空", "Cathedral · Vast"),
        e("慢速漂浮 · 迷幻", "慢速漂浮 · 迷幻", "Slow drift · Psychedelic"),
        e("极速环绕 · 眩晕", "極速環繞 · 眩暈", "Fast orbit · Intense"),
        e("头顶升降 · 垂直", "頭頂升降 · 垂直", "Overhead · Vertical"),
        e("地下车库 · 近混响", "地下車庫 · 近混響", "Garage · Early reflections"),
        e("山谷回声 · 远反射", "山谷回聲 · 遠反射", "Valley · Distant echoes"),
        e("随机巡游 · 不定向", "隨機巡遊 · 不定向", "Random roam · Changing direction"),
        e("广场回响 · 户外", "廣場迴響 · 戶外", "Plaza · Outdoor"),
        e("耳机环绕 · 宽阔", "耳機環繞 · 寬闊", "Headphone orbit · Wide"),
        e("录音棚 · 干声", "錄音棚 · 乾聲", "Studio · Dry"),
        e("空中轨道 · 升降", "空中軌道 · 升降", "Air orbit · Elevation"),
        e("深夜卧室 · 轻眠", "深夜臥室 · 輕眠", "Bedroom · Night"),
        e("金属隧道 · 长鸣", "金屬隧道 · 長鳴", "Metal tunnel · Long tail"),
        e("侧后方掠影 · 反向", "側後方掠影 · 反向", "Rear pass · Reverse"),
        e("前方呼吸 · 远近", "前方呼吸 · 遠近", "Front breathing · Depth"),
        e("剧院楼座 · 宽厅", "劇院樓座 · 寬廳", "Theater balcony · Wide hall"),
        e("城市广场 · 开阔", "城市廣場 · 開闊", "City square · Open"),
        e("露天剧场 · 远景", "露天劇場 · 遠景", "Amphitheater · Distant"),
        e("空旷展馆 · 回声", "空曠展館 · 回聲", "Empty hall · Echo"),
        e("巨型礼堂 · 深远", "巨型禮堂 · 深遠", "Grand auditorium · Deep")
    )

    private val presetNotes = listOf(
        e("不做 HRTF、不加混响，左右声道保持原始素材，用来 A/B 对比。", "不做 HRTF、不加混響，左右聲道保持原始素材，用來 A/B 對比。", "No HRTF or reverb. Original stereo for A/B comparison."),
        e("最通用的 8 秒一圈，头前上方掠过；适合先判断素材是否合适。", "最通用的 8 秒一圈，頭前上方掠過；適合先判斷素材是否合適。", "An 8-second orbit passing above the head; a good starting point."),
        e("15 米居家客厅空间，混响短、空间开阔；适合日常听歌与人声播客。", "15 米居家客廳空間，混響短、空間開闊；適合日常聽歌與人聲播客。", "A 15 m living room with short reverb for music and podcasts."),
        e("人声贴耳、几乎没有尾巴；空间极小，适合 ASMR / 睡前 / 独白。", "人聲貼耳、幾乎沒有尾巴；空間極小，適合 ASMR / 睡前 / 獨白。", "Close vocals with almost no tail for ASMR and spoken word."),
        e("转得偏快、反射早，像站在乐手前面听；适合摇滚 / 乐队现场。", "轉得偏快、反射早，像站在樂手前面聽；適合搖滾 / 樂隊現場。", "Fast orbit and early reflections for rock and live bands."),
        e("中等厅堂、尾巴 1.8s，人声有明确包围感但字头仍清楚。", "中等廳堂、尾巴 1.8s，人聲有明確包圍感但字頭仍清楚。", "A medium hall with a 1.8 s tail and clear vocals."),
        e("50 米空间 + 3.2s 尾巴，声音被拉长拉远；适合氛围 / 合唱 / 空灵段落。", "50 米空間 + 3.2s 尾巴，聲音被拉長拉遠；適合氛圍 / 合唱 / 空靈段落。", "A 50 m space with a 3.2 s tail for ambient and choral music."),
        e("缓慢平滑地改变方位、上下和远近，适合后摇 / 电子 / 助眠。", "緩慢平滑地改變方位、上下和遠近，適合後搖 / 電子 / 助眠。", "Slow, smooth changes of direction, height and depth for ambient listening."),
        e("3 秒一圈，左右耳快速交替；适合短片段特效。", "3 秒一圈，左右耳快速交替；適合短片段特效。", "A three-second orbit for short effects."),
        e("声像在固定方位大幅上下摆动，形成升降感。", "聲像在固定方位大幅上下擺動，形成升降感。", "Broad vertical movement at a fixed direction."),
        e("近反射密、尾巴短，低频和人声周围有明显墙面感。", "近反射密、尾巴短，低頻和人聲周圍有明顯牆面感。", "Dense early reflections and short tails with nearby walls."),
        e("50 米开阔空间、稀疏延迟和长尾，远近落差明显。", "50 米開闊空間、稀疏延遲和長尾，遠近落差明顯。", "A 50 m open space with sparse delays and long echoes."),
        e("平滑漫游并每 2–8 秒随机换向，配合较大起伏。", "平滑漫遊並每 2–8 秒隨機換向，配合較大起伏。", "Smooth roaming with direction changes every 2–8 seconds."),
        e("34 米大空间 + 逆时针、从右侧起手，反射多而散；适合开阔感的编曲。", "34 米大空間 + 逆時針、從右側起手，反射多而散；適合開闊感的編曲。", "A 34 m space with a reverse orbit and diffuse reflections."),
        e("加快左右交替并拉宽双耳差异，保持较浅混响，突出耳边的绕行感。", "加快左右交替並拉寬雙耳差異，保持較淺混響，突出耳邊的繞行感。", "Fast interaural changes and wide stereo with light reverb."),
        e("小房间、低混响和轻微仰角，尽量保留口播、乐器的直接细节。", "小房間、低混響和輕微仰角，盡量保留口播、樂器的直接細節。", "Small room and light reverb to preserve detail."),
        e("逆向环绕配合缓慢升降，形成螺旋轨迹。", "逆向環繞配合緩慢升降，形成螺旋軌跡。", "A reverse orbit with a slow rise and fall."),
        e("慢速、近距离、短尾音，适合低音量助眠，不强调夸张的左右跳跃。", "慢速、近距離、短尾音，適合低音量助眠，不強調誇張的左右跳躍。", "Slow, close and short-tailed for quiet nighttime listening."),
        e("较窄空间配合较长的混响尾巴，适合合成器、氛围和实验音效。", "較窄空間配合較長的混響尾巴，適合合成器、氛圍和實驗音效。", "A narrow space with a long tail for synth and ambient effects."),
        e("从右后方起步，以中速反向绕行，适合需要明确后脑方位的试听。", "從右後方起步，以中速反向繞行，適合需要明確後腦方位的試聽。", "A medium reverse orbit from the rear-right for clear rear imaging."),
        e("慢速绕行，增强远近变化，让声源在前方周期性靠近又远离。", "慢速繞行，增強遠近變化，讓聲源在前方週期性靠近又遠離。", "Slow orbit with strong depth movement in front."),
        e("30 米厅堂与 2 秒尾音，保留清晰人声，同时增加环绕包围。", "30 米廳堂與 2 秒尾音，保留清晰人聲，同時增加環繞包圍。", "A 30 m hall with a 2 s tail for clear, immersive vocals."),
        e("36 米开阔空间，反射舒展，适合现场和层次丰富的编曲。", "36 米開闊空間，反射舒展，適合現場和層次豐富的編曲。", "A 36 m open space for live and layered music."),
        e("40 米空间与较大的远近落差，声像更开阔，尾音不过分拖沓。", "40 米空間與較大的遠近落差，聲像更開闊，尾音不過分拖沓。", "A 40 m space with broad imaging and controlled reverb."),
        e("44 米大空间与 3 秒尾音，强化宽阔回响，适合氛围和人声。", "44 米大空間與 3 秒尾音，強化寬闊迴響，適合氛圍和人聲。", "A 44 m hall with a 3 s tail for spacious vocals and ambience."),
        e("48 米深广厅堂，长反射与慢速绕行营造庄重、深远的空间感。", "48 米深廣廳堂，長反射與慢速繞行營造莊重、深遠的空間感。", "A 48 m auditorium with long reflections and a slow, deep orbit.")
    )

    fun preset(context: Context, index: Int, note: Boolean = false): String {
        val value = if (note) presetNotes.getOrNull(index) else presetNames.getOrNull(index)
        return value?.let { when (resolved(context)) {
            TRADITIONAL -> it.traditional
            ENGLISH -> it.english
            else -> it.simplified
        } } ?: text(context, "preset.custom")
    }

    private val pitchNames = listOf(
        "Chipmunk", "Screech", "Elf", "Cute", "Minion", "Crisp", "Bright", "Playful",
        "Cartoon", "Lively", "Clear", "Soft bright", "Original", "Warm", "Low", "Mature",
        "Steady", "Full", "Narrator", "Magnetic", "Demon", "Beast", "Torn", "Heavy", "Growl"
    )

    fun pitch(context: Context, semi: Double): String = when (resolved(context)) {
        ENGLISH -> pitchNames.getOrNull((12 - semi.toInt()).coerceIn(0, 24)) ?: text(context, "preset.custom")
        else -> when (semi.toInt()) {
            0 -> text(context, "original")
            12 -> if (resolved(context) == TRADITIONAL) "花栗鼠" else "花栗鼠"
            11 -> "尖叫鸡"
            10 -> if (resolved(context) == TRADITIONAL) "小精靈" else "小精灵"
            9 -> if (resolved(context) == TRADITIONAL) "萌寵" else "萌宠"
            8 -> if (resolved(context) == TRADITIONAL) "小黃人" else "小黄人"
            7 -> "脆生生"
            6 -> if (resolved(context) == TRADITIONAL) "少女" else "少女"
            5 -> if (resolved(context) == TRADITIONAL) "俏皮" else "俏皮"
            4 -> if (resolved(context) == TRADITIONAL) "卡通" else "卡通"
            3 -> if (resolved(context) == TRADITIONAL) "靈動" else "灵动"
            2 -> if (resolved(context) == TRADITIONAL) "清亮" else "清亮"
            1 -> if (resolved(context) == TRADITIONAL) "微亮" else "微亮"
            -1 -> if (resolved(context) == TRADITIONAL) "溫潤" else "温润"
            -2 -> "低沉"
            -3 -> if (resolved(context) == TRADITIONAL) "成熟男聲" else "成熟男声"
            -4 -> if (resolved(context) == TRADITIONAL) "穩重" else "稳重"
            -5 -> if (resolved(context) == TRADITIONAL) "厚實" else "厚实"
            -6 -> if (resolved(context) == TRADITIONAL) "低沉旁白" else "低沉旁白"
            -7 -> "磁性"
            -8 -> if (resolved(context) == TRADITIONAL) "惡魔" else "恶魔"
            -9 -> if (resolved(context) == TRADITIONAL) "巨獸" else "巨兽"
            -10 -> "撕裂"
            -11 -> if (resolved(context) == TRADITIONAL) "重壓" else "重压"
            else -> "低吼"
        }
    }
}
