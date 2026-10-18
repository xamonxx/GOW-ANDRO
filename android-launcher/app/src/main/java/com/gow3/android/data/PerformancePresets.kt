package com.gow3.android.data

/**
 * 性能预设：每个预设写入 gow3.ini 的一组键值（只用运行时真正读取的键：launch_env.py /
 * run-gow3.sh / GameActivity / gow3_settings.cpp）。纯 JVM 数据，单元测试直接验证。
 *
 * 写入后 `perf_preset` 记录所选预设；任一值被手动改动时 [detect] 返回 null（自定义）。
 */
internal enum class PerfPreset(val key: String) {
    /** 最高帧率：60 FPS、GPU 满频，不锁可持续频率。 */
    MAX("max"),
    /** iQOO 15R 默认：同上，但开启可持续性能模式，发热后不掉帧。 */
    BALANCED("balanced"),
    /** 低温稳定：游戏 30 FPS + LSFG x2（有 Lossless.dll 时）在屏幕上显示 60。 */
    COOL("cool"),
}

internal object PerformancePresets {
    const val KEY = "perf_preset"

    /** 预设涉及的键在 gow3.ini 缺失时的运行时取值（与 LaunchParams 默认值、run-gow3.sh 一致）。 */
    val runtimeDefaults: Map<String, String> = mapOf(
        "render_resolution" to "720p",
        "fps_limit" to "60",
        "frames_queued" to "2",
        "gpu_boost" to "1",
        "sustained_perf" to "0",
        "thermal_guard" to "1",
        "lsfg_multiplier" to "0",
    )

    /** [preset] 写入的值（不含 `perf_preset` 本身）。 */
    fun values(preset: PerfPreset, lsfgAvailable: Boolean): Map<String, String> = when (preset) {
        PerfPreset.MAX -> mapOf(
            "render_resolution" to "720p", "fps_limit" to "60", "frames_queued" to "2",
            "gpu_boost" to "1", "sustained_perf" to "0", "thermal_guard" to "1", "lsfg_multiplier" to "0",
        )
        PerfPreset.BALANCED -> mapOf(
            "render_resolution" to "720p", "fps_limit" to "60", "frames_queued" to "2",
            "gpu_boost" to "1", "sustained_perf" to "1", "thermal_guard" to "1", "lsfg_multiplier" to "0",
        )
        PerfPreset.COOL -> mapOf(
            "render_resolution" to "720p", "fps_limit" to "30", "frames_queued" to "2",
            "gpu_boost" to "0", "sustained_perf" to "1", "thermal_guard" to "1",
            "lsfg_multiplier" to (if (lsfgAvailable) "2" else "0"),
        )
    }

    /** 应用 [preset] 时写入 gow3.ini 的全部键值。 */
    fun updates(preset: PerfPreset, lsfgAvailable: Boolean): Map<String, String> =
        values(preset, lsfgAvailable) + (KEY to preset.key)

    /** 当前 gow3.ini 与哪个预设完全一致；都不一致时为 null（自定义）。 */
    fun detect(ini: Map<String, String>, lsfgAvailable: Boolean): PerfPreset? =
        PerfPreset.entries.firstOrNull { preset ->
            values(preset, lsfgAvailable).all { (key, value) -> effective(ini, key) == value }
        }

    private fun effective(ini: Map<String, String>, key: String): String {
        val raw = ini[key]?.trim()
        val value = if (raw.isNullOrEmpty()) runtimeDefaults.getValue(key) else raw
        // run-gow3.sh 只把 2-4 当作 LSFG 倍率，其余都是关闭。
        return if (key == "lsfg_multiplier" && value !in setOf("2", "3", "4")) "0" else value
    }
}
