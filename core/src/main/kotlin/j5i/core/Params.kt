package j5i.spatial8d.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class SpacePreset(
    val name: String,
    val note: String,
    val period: Double,
    val elev: Double,
    val depth: Double,
    val room: Double,
    val wet: Double,
    val rt60: Double,
    val phase: Double,
    val direction: Double,
    val width: Double = 100.0,
    val raw: Boolean = false,
    val trajectory: Int = 0
)

data class SliderDef(
    val key: String,
    val label: String,
    val min: Double,
    val max: Double,
    val unit: String,
    val digits: Int
)

data class PitchPreset(val name: String, val semi: Double)

data class PitchTick(val semi: Double, val name: String, val desc: String)

data class ParamInfo(val key: String, val label: String, val desc: String)

data class SpaceSlide(val key: String, val value: Double)

private fun mk(
    name: String,
    note: String,
    period: Double,
    elev: Double,
    depth: Double,
    room: Double,
    wet: Double,
    rt60: Double,
    phase: Double,
    direction: Double,
    width: Double = 100.0,
    raw: Boolean = false,
    trajectory: Int = 0
) = SpacePreset(name, note, period, elev, depth, room, wet, rt60, phase, direction, width, raw, trajectory)

val SPACE_PRESETS: List<SpacePreset> = listOf(
    mk("原始音频 · 直通", "不做 HRTF、不加混响，左右声道保持原始素材，用来 A/B 对比。", 8.0, 0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 1.0, 100.0, true),
    mk("标准绕头 · 默认", "最通用的 8 秒一圈，头前上方掠过；加强前后耳廓线索，先听这个判断素材是否合适。", 8.0, 35.0, 45.0, 10.0, 18.0, 1.2, 0.0, 1.0),
    mk("室内客厅 · 宽场", "15 米居家客厅空间，混响短、空间开阔；适合日常听歌与人声播客。", 10.0, 35.0, 40.0, 15.0, 22.0, 1.0, 0.0, 1.0, trajectory = 1),
    mk("贴身耳语 · 小房间", "人声贴耳、几乎没有尾巴；空间极小，适合 ASMR / 睡前 / 独白。", 14.0, 12.0, 10.0, 4.0, 8.0, 0.5, 0.0, 1.0, 90.0),
    mk("舞台前排 · 现场", "转得偏快、反射早，像站在乐手前面听；适合摇滚 / 乐队现场。", 4.0, 30.0, 25.0, 16.0, 24.0, 1.0, 0.0, 1.0, 115.0),
    mk("音乐厅包厢 · 包围", "中等厅堂、尾巴 1.8s，人声有明确包围感但字头仍清楚。", 10.0, 45.0, 60.0, 26.0, 34.0, 1.8, 0.0, 1.0, 105.0, trajectory = 1),
    mk("大教堂穹顶 · 极空", "50 米空间 + 3.2s 尾巴，声音被拉长拉远；适合氛围 / 合唱 / 空灵段落。", 14.0, 60.0, 75.0, 50.0, 52.0, 3.2, 0.0, 1.0, 110.0),
    mk("慢速漂浮 · 迷幻", "缓慢平滑地改变方位、上下和远近，适合后摇 / 电子 / 助眠。", 18.0, 50.0, 65.0, 18.0, 30.0, 1.6, 0.0, 1.0, 100.0, trajectory = 4),
    mk("极速环绕 · 眩晕", "1 秒一圈，左右耳高速交替；适合短片段特效，不建议整首长听。", 1.0, 55.0, 35.0, 9.0, 16.0, 0.7, 0.0, 1.0, 130.0),
    mk("头顶升降 · 垂直", "声像在固定方位大幅上下摆动，形成升降感。", 6.0, 80.0, 20.0, 8.0, 20.0, 1.0, 0.0, 1.0, 95.0, trajectory = 2),
    mk("地下车库 · 近混响", "近反射密、尾巴短，低频和人声周围有明显墙面感。", 7.0, 35.0, 35.0, 20.0, 48.0, 1.4, 0.0, -1.0, 110.0, trajectory = 1),
    mk("山谷回声 · 远反射", "50 米开阔空间、稀疏延迟和长尾，远近落差明显。", 16.0, 55.0, 70.0, 50.0, 46.0, 3.8, 0.0, 1.0, 120.0, trajectory = 4),
    mk("随机巡游 · 不定向", "平滑漫游并每 2–8 秒随机换向，配合较大起伏。", 6.0, 55.0, 55.0, 16.0, 28.0, 1.4, 0.0, 0.0, 110.0, trajectory = 4),
    mk("广场回响 · 户外", "34 米大空间 + 逆时针、从右侧起手，反射多而散；适合开阔感的编曲。", 16.0, 45.0, 50.0, 34.0, 44.0, 2.4, 90.0, -1.0),
    mk("耳机环绕 · 宽阔", "加快左右交替并拉宽双耳差异，保持较浅混响，突出耳边的绕行感。", 2.5, 25.0, 22.0, 5.0, 10.0, 0.5, 0.0, 1.0, 140.0),
    mk("录音棚 · 干声", "小房间、低混响和轻微仰角，尽量保留口播、乐器的直接细节。", 12.0, 18.0, 12.0, 6.0, 5.0, 0.4, 0.0, 1.0, 95.0),
    mk("空中轨道 · 升降", "逆向环绕配合缓慢升降，形成螺旋轨迹。", 4.0, 80.0, 32.0, 12.0, 22.0, 1.1, 270.0, -1.0, 105.0, trajectory = 3),
    mk("深夜卧室 · 轻眠", "慢速、近距离、短尾音，适合低音量助眠，不强调夸张的左右跳跃。", 16.0, 16.0, 18.0, 4.0, 9.0, 0.6, 0.0, 1.0, 80.0, trajectory = 2),
    mk("金属隧道 · 长鸣", "较窄空间配合较长的混响尾巴，适合合成器、氛围和实验音效。", 9.0, 40.0, 42.0, 24.0, 58.0, 2.6, 0.0, 1.0, 110.0, trajectory = 3),
    mk("侧后方掠影 · 反向", "从右后方起步，以中速反向绕行，适合需要明确后脑方位的试听。", 7.0, 45.0, 35.0, 14.0, 20.0, 1.0, 135.0, -1.0, 120.0),
    mk("前方呼吸 · 远近", "慢速绕行，增强远近变化，让声源在前方周期性靠近又远离。", 12.0, 30.0, 100.0, 18.0, 30.0, 1.6, 0.0, 1.0, 95.0)
    ,
    mk("剧院楼座 · 宽厅", "30 米厅堂与 2 秒尾音，保留清晰人声，同时增加环绕包围。", 12.0, 38.0, 45.0, 30.0, 32.0, 2.0, 0.0, 1.0, 105.0, trajectory = 1),
    mk("城市广场 · 开阔", "36 米开阔空间，反射舒展，适合现场和层次丰富的编曲。", 15.0, 42.0, 55.0, 36.0, 40.0, 2.4, 45.0, -1.0, 115.0, trajectory = 4),
    mk("露天剧场 · 远景", "40 米空间与较大的远近落差，声像更开阔，尾音不过分拖沓。", 14.0, 48.0, 65.0, 40.0, 42.0, 2.6, 90.0, 1.0, 110.0, trajectory = 3),
    mk("空旷展馆 · 回声", "44 米大空间与 3 秒尾音，强化宽阔回响，适合氛围和人声。", 16.0, 52.0, 70.0, 44.0, 46.0, 3.0, 135.0, -1.0, 115.0, trajectory = 2),
    mk("巨型礼堂 · 深远", "48 米深广厅堂，长反射与慢速绕行营造庄重、深远的空间感。", 18.0, 58.0, 75.0, 48.0, 50.0, 3.4, 180.0, 1.0, 120.0)
)

const val CUSTOM_SPACE = "自定义"

val SLIDERS: List<SliderDef> = listOf(
    SliderDef("period", "环绕周期", 1.0, 20.0, " 秒", 1),
    SliderDef("elev", "上下幅度", 0.0, 80.0, " °", 0),
    SliderDef("depth", "远近距离", 0.0, 100.0, " %", 0),
    SliderDef("phase", "起始相位", 0.0, 360.0, " °", 0),
    SliderDef("room", "空间大小", 3.0, 50.0, " 米", 0),
    SliderDef("wet", "混响强度", 0.0, 100.0, " %", 0),
    SliderDef("rt60", "混响时间", 0.2, 5.0, " 秒", 1),
    SliderDef("width", "声场宽度", 0.0, 150.0, " %", 0)
)

val PARAM_INFO: List<ParamInfo> = listOf(
    ParamInfo("period", "环绕周期", "1–20 秒一圈。改小 = 转得更快。只改变左右耳能量/时间差交替的快慢，音色、响度都不变，是纯「运动速度」参数。"),
    ParamInfo("elev", "上下幅度", "0–80°。0° = 只在水平面绕圈；加大 = 耳廓陷波（3.2–9.8kHz 窄带）随仰角前后移动，出现「头顶掠过 / 沉到脚下」的感觉。只动那一小段高频，不改整体亮度。"),
    ParamInfo("depth", "远近距离", "0% = 等距绕圈；加大 = 声源周期性退远再拉近。三件事同时变：①电平下降（100% 时最远处约 -18dB）②5kHz 以上被空气吸收（最多 -14dB，声音变暗）③直达声压低、混响占比抬高（干湿比落差约 12dB，这是人耳判断距离最硬的线索）。呼吸周期固定 8–16 秒（= 1.5 × 环绕周期），所以再慢的预设也能在一首歌里听出几个来回。"),
    ParamInfo("room", "空间大小", "3–50 米决定混响首反射时间（4m≈12ms / 32m≈93ms / 50m≈146ms）与尾音密度。加大 = 更空旷、反射更晚、尾巴更暗；直达声电平不变。"),
    ParamInfo("wet", "混响强度", "湿声比例。0% = 全干、贴脸但没空间；加大 = 包围感强，过头会发糊。"),
    ParamInfo("rt60", "混响时间", "尾巴衰减 60dB 的时长，范围 0.2–5.0s。0.5s ≈ 小房间，1.2s ≈ 厅，2.8s ≈ 教堂。只改尾音长度，不改直达声与音色。"),
    ParamInfo("phase", "起始相位", "决定歌曲第一个字落在头的哪个方位（0° = 正前，90° = 右，180° = 后枕）。只改开场落点，运动规律不变；同一首歌换相位 = 换起手式。"),
    ParamInfo("direction", "旋转方向", "顺时针 / 逆时针固定绕行；随机模式会在播放过程中每隔 2–8 秒自动换向，并保持换向瞬间的角度连续，不会跳位。"),
    ParamInfo("width", "声场宽度", "0% = 双耳完全居中；100% = 标准 HRTF 双耳差异；最高 150% = 更强的左右分离和包围感。它同时缩放耳间时间差、头部遮蔽和幅度声像，是控制「空间感强弱」的总量参数。")
)

val PITCH_PRESETS: List<PitchPreset> = listOf(
    PitchPreset("花栗鼠 +12", 12.0),
    PitchPreset("尖叫鸡 +11", 11.0),
    PitchPreset("小精灵 +10", 10.0),
    PitchPreset("萌宠 +9", 9.0),
    PitchPreset("小黄人 +8", 8.0),
    PitchPreset("脆生生 +7", 7.0),
    PitchPreset("少女 +6", 6.0),
    PitchPreset("俏皮 +5", 5.0),
    PitchPreset("卡通 +4", 4.0),
    PitchPreset("灵动 +3", 3.0),
    PitchPreset("清亮 +2", 2.0),
    PitchPreset("微亮 +1", 1.0),
    PitchPreset("原声 0", 0.0),
    PitchPreset("温润 -1", -1.0),
    PitchPreset("低沉 -2", -2.0),
    PitchPreset("成熟男声 -3", -3.0),
    PitchPreset("稳重 -4", -4.0),
    PitchPreset("厚实 -5", -5.0),
    PitchPreset("低沉旁白 -6", -6.0),
    PitchPreset("磁性 -7", -7.0),
    PitchPreset("恶魔 -8", -8.0),
    PitchPreset("巨兽 -9", -9.0),
    PitchPreset("撕裂 -10", -10.0),
    PitchPreset("重压 -11", -11.0),
    PitchPreset("低吼 -12", -12.0)
)

val PITCH_TICKS: List<PitchTick> = listOf(
    PitchTick(-12.0, "低吼", "一个八度下移"),
    PitchTick(-8.0, "恶魔", "大幅压低"),
    PitchTick(-4.0, "稳重", "略低一点"),
    PitchTick(0.0, "原声", "不做变声"),
    PitchTick(4.0, "卡通", "略尖俏皮"),
    PitchTick(8.0, "小黄人", "明显尖细"),
    PitchTick(12.0, "花栗鼠", "极尖极细")
)

const val PITCH_LO = -12.0
const val PITCH_HI = 12.0

fun defaultParams(): SpacePreset = SPACE_PRESETS[1]

val DEFAULT_SPACE_PRESET: SpacePreset get() = defaultParams()

fun sliderOf(key: String): SliderDef? = SLIDERS.firstOrNull { it.key == key }

fun infoOf(key: String): ParamInfo? = PARAM_INFO.firstOrNull { it.key == key }

fun matchPreset(
    period: Double,
    elev: Double,
    depth: Double,
    room: Double,
    wet: Double,
    rt60: Double,
    phase: Double,
    direction: Double,
    width: Double,
    raw: Boolean,
    trajectory: Int = 0
): String = matchPresetIn(
    SPACE_PRESETS, period, elev, depth, room, wet, rt60, phase, direction, width, raw, trajectory
)

fun matchPresetIn(
    presets: List<SpacePreset>,
    period: Double,
    elev: Double,
    depth: Double,
    room: Double,
    wet: Double,
    rt60: Double,
    phase: Double,
    direction: Double,
    width: Double,
    raw: Boolean,
    trajectory: Int = 0
): String {
    for (p in presets) {
        if (
            abs(p.period - period) < 0.051 &&
            abs(p.elev - elev) < 0.51 &&
            abs(p.depth - depth) < 0.51 &&
            abs(p.room - room) < 0.51 &&
            abs(p.wet - wet) < 0.51 &&
            abs(p.rt60 - rt60) < 0.051 &&
            abs(p.phase - phase) < 0.51 &&
            p.direction == direction &&
            abs(p.width - width) < 0.51 &&
            p.raw == raw && p.trajectory == trajectory
        ) return p.name
    }
    return CUSTOM_SPACE
}

fun clampSlider(key: String, v: Double): Double {
    val d = sliderOf(key) ?: return v
    var x = min(d.max, max(d.min, v))
    x = if (d.digits == 0) round(x) else round(x * 10) / 10
    return x
}

fun formatValue(key: String, v: Double): String {
    val d = sliderOf(key) ?: return v.toString()
    return if (d.digits == 0) round(v).toLong().toString() + d.unit
    else String.format(java.util.Locale.US, "%.${d.digits.coerceAtMost(2)}f", v) + d.unit
}
