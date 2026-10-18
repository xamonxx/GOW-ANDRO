package com.gow3.android.feature.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gow3.android.R
import com.gow3.android.data.Advisor
import com.gow3.android.data.Gow3Ini
import com.gow3.android.data.LaunchParams
import com.gow3.android.data.LosslessDllManager
import com.gow3.android.data.LosslessDllState
import com.gow3.android.data.PerfPreset
import com.gow3.android.data.PerformancePresets
import com.gow3.android.feature.home.FrameGenerationSection
import com.gow3.android.feature.home.ParamRow
import com.gow3.android.feature.home.presetName
import com.gow3.android.ui.components.LauncherColors
import com.gow3.android.ui.components.ScreenColumn
import java.io.File
import java.util.Locale

private enum class PerfTab(val labelRes: Int, val keys: List<String>) {
    PRESET(R.string.perf_tab_preset, emptyList()),
    GRAPHICS(R.string.perf_tab_graphics, listOf("render_resolution", "fps_limit", "engine_fps", "vsync", "frames_queued", "skip_intro")),
    SYSTEM(R.string.perf_tab_system, listOf("thermal_guard", "gpu_boost", "cpu_mode", "cpu_placement", "sustained_perf")),
    FRAME_GEN(R.string.perf_tab_frame_gen, listOf("lsfg_multiplier", "lsfg_flow_scale", "lsfg_performance")),
    DIAGNOSTICS(R.string.perf_tab_diagnostics, listOf("hud_monitor", "frame_stats", "gpu_render_mode")),
}

/** The Performance tab: presets, then every launch parameter in four themed sub-tabs. */
@Composable
internal fun PerformanceScreen(
    losslessDlls: LosslessDllManager,
    onGetFromSteam: ((Boolean, String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var ini by remember { mutableStateOf(Gow3Ini.read(iniFile(context))) }
    var lsfgAvailable by remember { mutableStateOf(losslessDlls.resolve() is LosslessDllState.Installed) }
    var tab by remember { mutableIntStateOf(PerfTab.PRESET.ordinal) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ini = Gow3Ini.read(iniFile(context))
                lsfgAvailable = losslessDlls.resolve() is LosslessDllState.Installed
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun write(updates: Map<String, String>) {
        if (Gow3Ini.write(iniFile(context), updates)) {
            ini = ini + updates
        } else {
            Toast.makeText(context, context.getString(R.string.settings_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    ScreenColumn(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.perf_title), color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(stringResource(R.string.perf_subtitle), color = LauncherColors.accent, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PerfTab.entries.forEach { entry ->
                val selected = entry.ordinal == tab
                Text(
                    stringResource(entry.labelRes),
                    color = if (selected) LauncherColors.textPrimary else LauncherColors.textMuted,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) LauncherColors.primary else LauncherColors.navSurface)
                        .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = if (selected) 0.9f else 0.3f)), RoundedCornerShape(50))
                        .clickable { tab = entry.ordinal }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        val current = PerfTab.entries[tab]
        when (current) {
            PerfTab.PRESET -> PresetTab(ini, lsfgAvailable) { preset -> write(PerformancePresets.updates(preset, lsfgAvailable)) }
            PerfTab.FRAME_GEN -> {
                FrameGenerationSection(losslessDlls, onGetFromSteam) {
                    lsfgAvailable = losslessDlls.resolve() is LosslessDllState.Installed
                }
                Spacer(Modifier.height(16.dp))
                ParamSection(current, ini, ::write)
                LsfgRefreshWarning(ini)
            }
            PerfTab.DIAGNOSTICS -> {
                ParamSection(current, ini, ::write)
                Spacer(Modifier.height(16.dp))
                DeviceMonitorCard()
            }
            else -> ParamSection(current, ini, ::write)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun iniFile(context: Context): File = File(context.filesDir, "gow3port/gow3.ini")

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LauncherColors.surface)
            .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = 0.45f)), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun ParamSection(tab: PerfTab, ini: Map<String, String>, write: (Map<String, String>) -> Unit) {
    SectionCard {
        tab.keys.mapNotNull { key -> LaunchParams.all.firstOrNull { it.key == key } }
            .forEach { param -> ParamRow(param, ini) { key, value -> write(mapOf(key to value)) } }
        Text(stringResource(R.string.launch_parameters_footer), color = LauncherColors.textMuted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun PresetTab(ini: Map<String, String>, lsfgAvailable: Boolean, onApply: (PerfPreset) -> Unit) {
    val context = LocalContext.current
    val device = remember { deviceSummary(context) }
    val active = PerformancePresets.detect(ini, lsfgAvailable)
    val recommended = remember { Advisor.recommendPreset(device.soc, device.ramMb) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PerfPreset.entries.forEach { preset ->
            val selected = preset == active
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) LauncherColors.surfaceRaised else LauncherColors.surface)
                    .border(BorderStroke(if (selected) 2.dp else 1.dp, LauncherColors.accent.copy(alpha = if (selected) 1f else 0.35f)), RoundedCornerShape(14.dp))
                    .clickable { onApply(preset) }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(presetName(preset), color = LauncherColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (selected) Badge(stringResource(R.string.preset_active), LauncherColors.primary)
                    if (preset == recommended) Badge(stringResource(R.string.preset_recommended), LauncherColors.success.copy(alpha = 0.85f))
                }
                Text(presetDescription(preset), color = LauncherColors.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        if (active == null) {
            Text(stringResource(R.string.preset_custom_note), color = LauncherColors.warning, fontSize = 12.sp)
        }
        SectionCard {
            Text(stringResource(R.string.device_title), color = LauncherColors.accent, fontFamily = FontFamily.Serif, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(
                stringResource(
                    R.string.device_summary_fmt,
                    device.soc.ifBlank { "—" },
                    String.format(Locale.ROOT, "%.0f GB", device.ramMb / 1024f),
                    if (device.maxRefresh > 0) String.format(Locale.ROOT, "%.0f Hz", device.maxRefresh) else "—",
                ),
                color = LauncherColors.textPrimary, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        color = LauncherColors.textPrimary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun presetDescription(preset: PerfPreset): String = when (preset) {
    PerfPreset.MAX -> stringResource(R.string.preset_desc_max)
    PerfPreset.BALANCED -> stringResource(R.string.preset_desc_balanced)
    PerfPreset.COOL -> stringResource(R.string.preset_desc_cool)
}

/** P5: frame generation above the panel's fastest rate cannot be shown evenly. */
@Composable
private fun LsfgRefreshWarning(ini: Map<String, String>) {
    val context = LocalContext.current
    val maxRefresh = remember { deviceSummary(context).maxRefresh }
    val multiplier = ini["lsfg_multiplier"]?.trim()?.toIntOrNull() ?: return
    val limit = ini["fps_limit"]?.trim()?.toIntOrNull()?.let { if (it == 0) 60 else it } ?: 60
    if (multiplier !in 2..4 || maxRefresh <= 0f || limit * multiplier <= maxRefresh + 1) return
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.lsfg_warning_fmt, limit * multiplier, maxRefresh.toInt()),
        color = LauncherColors.warning, fontSize = 12.sp, lineHeight = 17.sp,
    )
}

private data class DeviceSummary(val soc: String, val ramMb: Long, val maxRefresh: Float)

private fun deviceSummary(context: Context): DeviceSummary {
    val memory = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
    @Suppress("DEPRECATION")
    val display = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    return DeviceSummary(
        soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
        ramMb = memory.totalMem shr 20,
        maxRefresh = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f,
    )
}
