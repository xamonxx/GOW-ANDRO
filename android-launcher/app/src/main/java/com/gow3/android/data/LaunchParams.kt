package com.gow3.android.data

import androidx.compose.runtime.Immutable
import com.gow3.android.R

/**
 * 一个可编辑的启动参数：其 gow3.ini 键、显示标签、分组以及 Android 运行时
 * 实际消费的取值域。`defaultValue` 是键缺失时 UI 显示的值。
 *
 * 显示标签、分组与说明使用字符串资源 id（labelRes / groupRes / descriptionRes），
 * 由 UI 层通过 Context 解析，数据层保持纯 JVM 结构、不依赖 Android。
 */
@Immutable
internal data class LaunchParam(
    val key: String,
    val labelRes: Int,
    val groupRes: Int,
    val options: List<LaunchOption>,
    val defaultValue: String,
    val descriptionRes: Int? = null,
)

@Immutable
internal data class LaunchOption(val value: String, val labelRes: Int)

/**
 * launcher 编辑的启动参数，由 Android 运行时消费的 gow3.ini 键支撑。
 * 默认值与取值域遵循 `SettingsDialog.java` 的常量（UPSCALER_VALUES/FPS_VALUES …）
 * 以及 `gpu/shim/gow3_graphics.h` / `gpu/shim/gow3_settings.cpp` 中的运行时解析器；
 * 当两者不一致时（fps_limit：Java 前端提供 40/45，但 `ParseFpsLimit` 把它们映射为 0）
 * 以运行时取值域为准，确保每个选项都实际生效。
 *
 * 各参数的默认值均为移动端优化值（render_resolution=720p、fps_limit=60、
 * engine_fps=60；display_mode 已从可编辑项移除，固定 fullscreen）。这些默认值
 * MUST 与 `tools/android/config/gow3-default.ini`（首启落盘为 gow3.ini 的基准）
 * 保持一致，避免 UI 展示默认与实际运行默认漂移。
 */
internal object LaunchParams {
    val all: List<LaunchParam> = listOf(
        // 不提供 upscaler：Android 上 launch_env.py 固定 GOW3_UPSCALER=none（时域放大尚未针对本游戏校准），
        // 该选项不会生效。800p 也不提供：补丁 XML 里没有 800p 分辨率补丁。
        LaunchParam(
            key = "render_resolution", labelRes = R.string.param_resolution, groupRes = R.string.param_group_display,
            options = listOf(
                LaunchOption("native", R.string.param_option_native),
                LaunchOption("480p", R.string.param_option_480p),
                LaunchOption("720p", R.string.param_option_720p),
                LaunchOption("1440p", R.string.param_option_1440p),
                LaunchOption("1800p", R.string.param_option_1800p),
                LaunchOption("4K", R.string.param_option_4k),
            ),
            // 移动端优化默认值：720p 内部分辨率 + FSR 升采样，手机/平板上更流畅省电。
            // 与 tools/android/config/gow3-default.ini 保持一致。
            defaultValue = "720p",
        ),
        LaunchParam(
            key = "fps_limit", labelRes = R.string.param_fps_limit, groupRes = R.string.param_group_performance,
            options = listOf(
                LaunchOption("30", R.string.param_option_30),
                LaunchOption("60", R.string.param_option_60),
                LaunchOption("120", R.string.param_option_120),
                LaunchOption("240", R.string.param_option_240),
                LaunchOption("0", R.string.param_option_unlimited),
            ),
            // 移动端优化默认值：以 60 封顶，避免无限帧率导致发热与耗电。
            // 与 tools/android/config/gow3-default.ini 保持一致。
            defaultValue = "60",
        ),
        LaunchParam(
            key = "engine_fps", labelRes = R.string.param_engine_fps, groupRes = R.string.param_group_performance,
            options = listOf(
                LaunchOption("60", R.string.param_option_engine_60),
                LaunchOption("120", R.string.param_option_120),
                LaunchOption("240", R.string.param_option_240),
            ),
            // 移动端优化默认值：游戏原生引擎速度，最稳定、最省电。
            // 与 tools/android/config/gow3-default.ini 保持一致。
            defaultValue = "60",
            descriptionRes = R.string.param_desc_engine_fps,
        ),
        LaunchParam(
            key = "vsync", labelRes = R.string.param_vsync, groupRes = R.string.param_group_performance,
            options = listOf(
                LaunchOption("0", R.string.param_option_off),
                LaunchOption("1", R.string.param_option_on),
            ),
            defaultValue = "1",
            descriptionRes = R.string.param_desc_vsync,
        ),
        LaunchParam(
            key = "frames_queued", labelRes = R.string.param_frames_queued, groupRes = R.string.param_group_performance,
            options = listOf(
                LaunchOption("1", R.string.param_option_frames_1),
                LaunchOption("2", R.string.param_option_frames_2),
                LaunchOption("0", R.string.param_option_unlimited),
            ),
            // 移动端：2 让 CPU（FEX）在 GPU 绘制当前帧时准备下一帧。与 gow3-default.ini 保持一致。
            defaultValue = "2",
            descriptionRes = R.string.param_desc_frames_queued,
        ),
        LaunchParam(
            // run-gow3.sh：非 0 时设置 TU_KGSL_PWR_CONSTRAINT=max。
            key = "gpu_boost", labelRes = R.string.param_gpu_boost, groupRes = R.string.param_group_performance,
            options = listOf(
                LaunchOption("0", R.string.param_option_off),
                LaunchOption("1", R.string.param_option_on),
            ),
            defaultValue = "1",
            descriptionRes = R.string.param_desc_gpu_boost,
        ),
        LaunchParam(
            // run-gow3.sh：fast 时以 FEX_APP_CONFIG 载入 gow3-fex-compatible.json。
            key = "cpu_mode", labelRes = R.string.param_cpu_mode, groupRes = R.string.param_group_performance,
            options = listOf(
                LaunchOption("fast", R.string.param_option_cpu_fast),
                LaunchOption("accurate", R.string.param_option_cpu_accurate),
            ),
            defaultValue = "fast",
            descriptionRes = R.string.param_desc_cpu_mode,
        ),
        LaunchParam(
            // GameActivity：设备支持时调用 Window.setSustainedPerformanceMode(true)。
            key = "sustained_perf", labelRes = R.string.param_sustained_perf, groupRes = R.string.param_group_performance,
            options = listOf(
                LaunchOption("0", R.string.param_option_off),
                LaunchOption("1", R.string.param_option_on),
            ),
            defaultValue = "0",
            descriptionRes = R.string.param_desc_sustained_perf,
        ),
        LaunchParam(
            // GameActivity：温度达到 SEVERE 时写 gow3port/thermal.cap=30，降到 LIGHT 后删除（缺省视为开启）。
            key = "thermal_guard", labelRes = R.string.param_thermal_guard, groupRes = R.string.param_group_performance,
            options = listOf(
                LaunchOption("0", R.string.param_option_off),
                LaunchOption("1", R.string.param_option_on),
            ),
            defaultValue = "1",
            descriptionRes = R.string.param_desc_thermal_guard,
        ),
        LaunchParam(
            // run-gow3.sh：非 0 时导出 GOW3_CPU_PLACEMENT=1（gow3_threads.h KeepOffPrimeCores）。
            key = "cpu_placement", labelRes = R.string.param_cpu_placement, groupRes = R.string.param_group_performance,
            options = listOf(
                LaunchOption("0", R.string.param_option_off),
                LaunchOption("1", R.string.param_option_on),
            ),
            defaultValue = "1",
            descriptionRes = R.string.param_desc_cpu_placement,
        ),
        LaunchParam(
            // run-gow3.sh：TU_DEBUG=gmem|sysmem，仅供 A/B 测试；auto 不改变 Turnip 的选择。
            key = "gpu_render_mode", labelRes = R.string.param_gpu_render_mode, groupRes = R.string.param_group_diagnostics,
            options = listOf(
                LaunchOption("auto", R.string.param_option_render_auto),
                LaunchOption("gmem", R.string.param_option_render_gmem),
                LaunchOption("sysmem", R.string.param_option_render_sysmem),
            ),
            defaultValue = "auto",
            descriptionRes = R.string.param_desc_gpu_render_mode,
        ),
        LaunchParam(
            // run-gow3.sh：1 时导出 GOW3_FRAME_STATS=1，每 5 秒在 android-game.log 记录 Frame stats。
            key = "frame_stats", labelRes = R.string.param_frame_stats, groupRes = R.string.param_group_diagnostics,
            options = listOf(
                LaunchOption("0", R.string.param_option_off),
                LaunchOption("1", R.string.param_option_on),
            ),
            defaultValue = "0",
            descriptionRes = R.string.param_desc_frame_stats,
        ),
        // skip_network_choice / debug_camera 不再提供：本仓库没有任何代码读取它们。
        LaunchParam(
            // launch_env.py：skip_intro=1 时选择内置补丁 "Skip Intro"；缺省（未写入）为关闭，与运行时一致。
            key = "skip_intro", labelRes = R.string.param_skip_intros, groupRes = R.string.param_group_startup,
            options = listOf(
                LaunchOption("0", R.string.param_option_off),
                LaunchOption("1", R.string.param_option_on),
            ),
            defaultValue = "0",
        ),
    )

    /** 参数的当前原始值；缺失或为空时返回其默认值。 */
    fun valueOf(ini: Map<String, String>, param: LaunchParam): String {
        val v = ini[param.key]?.trim()
        return if (v.isNullOrEmpty()) param.defaultValue else v
    }

    /** 当前值对应的选项；未知值返回 null（由 UI 层回退到原始值）。 */
    fun selectedOption(ini: Map<String, String>, param: LaunchParam): LaunchOption? {
        val v = valueOf(ini, param)
        return param.options.firstOrNull { it.value == v }
    }
}
