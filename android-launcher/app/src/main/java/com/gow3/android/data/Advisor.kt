package com.gow3.android.data

/**
 * 智能建议：根据上一局（logs/session-last.json）、启动历史、gow3.ini 与设备情况，给出可一键应用的建议。
 * 纯 JVM 逻辑（不依赖 Android），由 AdvisorTest 直接验证；UI 层把 [AdviceKind] 映射为本地化文字。
 */
internal data class SessionReport(
    val durationS: Int,
    val avgFps: Float,
    val low1Fps: Int,
    val pct55: Int,
    val maxBatteryC: Float,
    val severeS: Int,
    val caps: Int,
    val exitCode: Int,
    val fpsLimit: Int,
    val lsfg: Int,
    val removable: Boolean,
) {
    companion object {
        private val FIELD = Regex("\"(\\w+)\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")

        /** SessionRecorder.json() 的扁平 JSON；字段缺失或格式不对时为 null。 */
        fun parse(json: String): SessionReport? {
            val values = FIELD.findAll(json).associate { it.groupValues[1] to it.groupValues[2] }
            fun int(key: String) = values[key]?.toFloatOrNull()?.toInt()
            fun float(key: String) = values[key]?.toFloatOrNull()
            return SessionReport(
                durationS = int("duration_s") ?: return null,
                avgFps = float("avg_fps") ?: return null,
                low1Fps = int("low1_fps") ?: -1,
                pct55 = int("pct_55") ?: -1,
                maxBatteryC = float("max_battery_c") ?: -1f,
                severeS = int("severe_s") ?: 0,
                caps = int("caps") ?: 0,
                exitCode = int("exit_code") ?: 0,
                fpsLimit = int("fps_limit") ?: 60,
                lsfg = int("lsfg") ?: 0,
                removable = int("removable") == 1,
            )
        }
    }
}

/** 设备情况：SoC、内存、屏幕最高刷新率、电量与充电、温度、游戏是否在可移除存储上。 */
internal data class DeviceFacts(
    val socModel: String,
    val totalRamMb: Long,
    val maxRefreshHz: Float,
    val batteryPercent: Int,
    val charging: Boolean,
    val batteryC: Float,
    val lsfgAvailable: Boolean,
    val gameOnRemovable: Boolean,
)

internal enum class AdviceKind {
    /** 还没有选过预设：按设备推荐一个。 */
    RECOMMENDED_PRESET,
    /** 上一局长时间处于 SEVERE/CRITICAL：改用「低温稳定」。 */
    OVERHEATED,
    /** 不热但 FPS 低于 50：降到 480p。 */
    LOW_FPS,
    /** LSFG 倍率 × 帧率超过屏幕最高刷新率：改为自动倍率。 */
    LSFG_TOO_HIGH,
    /** 游戏在 USB/可移除存储上（iQOO 15R 为 USB 2.0）：建议移到内部存储。仅提示。 */
    GAME_ON_USB,
    /** 最近两次都崩溃且 CPU 为快速模式：改用精确模式。 */
    CRASHES_FAST_CPU,
    /** 电量低于 20% 且未充电：改用「低温稳定」。 */
    BATTERY_LOW,
    /** 充电时电池温度 ≥ 40 °C：建议旁路充电或拔掉充电器。仅提示。 */
    CHARGING_HOT,
}

/** 一条建议；[updates] 为空表示仅提示，[preset] 为相关预设（推荐预设时用于显示名称）。 */
internal data class Advice(
    val kind: AdviceKind,
    val updates: Map<String, String> = emptyMap(),
    val preset: PerfPreset? = null,
)

internal object Advisor {

    /** 按 SoC 与内存推荐预设：骁龙 8 Gen 5 / 8 Elite 级别且内存 ≥ 10 GB 用「均衡」，否则「低温稳定」。 */
    fun recommendPreset(socModel: String, totalRamMb: Long): PerfPreset {
        val flagship = socModel.uppercase().let { soc -> FLAGSHIP_SOCS.any { soc.startsWith(it) } }
        return if (flagship && totalRamMb >= 10_000) PerfPreset.BALANCED else PerfPreset.COOL
    }

    /** 骁龙 8 Gen 5（SM8845）、8 Elite（SM8750）、8 Elite Gen 5（SM8850）、8 Gen 3（SM8650）。 */
    private val FLAGSHIP_SOCS = listOf("SM8845", "SM8850", "SM8750", "SM8650")

    /** [crashedTwice]：最近两次启动都崩溃（SessionRecorder.crashedTwice）。 */
    fun advise(
        session: SessionReport?,
        ini: Map<String, String>,
        device: DeviceFacts,
        crashedTwice: Boolean,
    ): List<Advice> {
        val advice = mutableListOf<Advice>()
        val current = PerformancePresets.detect(ini, device.lsfgAvailable)
        val cool = PerformancePresets.updates(PerfPreset.COOL, device.lsfgAvailable)

        if (ini[PerformancePresets.KEY].isNullOrBlank()) {
            val preset = recommendPreset(device.socModel, device.totalRamMb)
            advice += Advice(AdviceKind.RECOMMENDED_PRESET, PerformancePresets.updates(preset, device.lsfgAvailable), preset)
        }
        if (crashedTwice && ini["cpu_mode"]?.trim() != "accurate") {
            advice += Advice(AdviceKind.CRASHES_FAST_CPU, mapOf("cpu_mode" to "accurate"))
        }
        if (session != null) {
            if (session.severeS >= 120 && current != PerfPreset.COOL) {
                advice += Advice(AdviceKind.OVERHEATED, cool, PerfPreset.COOL)
            } else if (session.avgFps in 1f..49.9f && session.severeS < 60 && session.lsfg == 0 &&
                (session.fpsLimit == 0 || session.fpsLimit >= 60) && ini["render_resolution"]?.trim() != "480p"
            ) {
                advice += Advice(AdviceKind.LOW_FPS, mapOf("render_resolution" to "480p"))
            }
        }
        val multiplier = ini["lsfg_multiplier"]?.trim()?.toIntOrNull()
        val limit = ini["fps_limit"]?.trim()?.toIntOrNull() ?: 60
        if (multiplier != null && multiplier in 2..4 && device.maxRefreshHz > 0 &&
            (if (limit == 0) 60 else limit) * multiplier > device.maxRefreshHz + 1
        ) {
            advice += Advice(AdviceKind.LSFG_TOO_HIGH, mapOf("lsfg_multiplier" to "auto"))
        }
        if (device.gameOnRemovable || session?.removable == true) advice += Advice(AdviceKind.GAME_ON_USB)
        if (device.batteryPercent in 0..19 && !device.charging && current != PerfPreset.COOL &&
            advice.none { it.kind == AdviceKind.OVERHEATED }
        ) {
            advice += Advice(AdviceKind.BATTERY_LOW, cool, PerfPreset.COOL)
        }
        if (device.charging && !device.batteryC.isNaN() && device.batteryC >= 40f) advice += Advice(AdviceKind.CHARGING_HOT)
        return advice
    }
}
