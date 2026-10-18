package com.gow3.android.feature.performance

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R
import com.gow3.android.ui.components.LauncherColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ro.cobrabm.fexdroid.DeviceStats
import java.util.Locale

/** One reading of the device monitor; NaN / negative values are unknown (denied or absent). */
internal data class DeviceReading(
    val batteryC: Float,
    val socC: Float,
    val thermalStatus: Int,
    val gpuBusy: Int,
    val gpuMhz: Int,
    val usedMb: Long,
    val totalMb: Long,
)

internal fun readDevice(context: Context, stats: DeviceStats): DeviceReading {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val thermal = if (Build.VERSION.SDK_INT >= 29 && power != null) power.currentThermalStatus else -1
    val memory = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
    return DeviceReading(
        batteryC = if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f,
        socC = stats.socTempC(),
        thermalStatus = thermal,
        gpuBusy = stats.gpuBusyPercent(),
        gpuMhz = stats.gpuMhz(),
        usedMb = if (memory.totalMem > 0) (memory.totalMem - memory.availMem) shr 20 else -1,
        totalMb = if (memory.totalMem > 0) memory.totalMem shr 20 else -1,
    )
}

/** Live phone temperature, thermal state, GPU and memory, refreshed every 2 s while shown. */
@Composable
internal fun DeviceMonitorCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val stats = remember { DeviceStats.system() }
    val reading by produceState<DeviceReading?>(initialValue = null) {
        while (true) {
            value = withContext(Dispatchers.IO) { readDevice(context, stats) }
            delay(2000)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LauncherColors.surface)
            .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.monitor_title), color = LauncherColors.gold, fontFamily = FontFamily.Serif, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        val r = reading
        val dash = stringResource(R.string.monitor_unavailable)
        val temperature = when {
            r == null -> dash
            r.socC.isNaN() -> celsius(r.batteryC, dash)
            else -> "${celsius(r.batteryC, dash)} · SoC ${celsius(r.socC, dash)}"
        }
        MonitorRow(stringResource(R.string.monitor_temperature), temperature, thermalColor(r?.thermalStatus ?: -1))
        MonitorRow(stringResource(R.string.monitor_thermal), thermalLabel(r?.thermalStatus ?: -1, dash), thermalColor(r?.thermalStatus ?: -1))
        val gpu = when {
            r == null || (r.gpuBusy < 0 && r.gpuMhz < 0) -> dash
            else -> listOfNotNull(
                r.gpuBusy.takeIf { it >= 0 }?.let { "$it%" },
                r.gpuMhz.takeIf { it >= 0 }?.let { "$it MHz" },
            ).joinToString(" · ")
        }
        MonitorRow(stringResource(R.string.monitor_gpu), gpu, LauncherColors.textPrimary)
        val memory = if (r == null || r.totalMb < 0) dash
        else stringResource(R.string.monitor_memory_fmt, gigabytes(r.usedMb), gigabytes(r.totalMb))
        MonitorRow(stringResource(R.string.monitor_memory), memory, LauncherColors.textPrimary)
    }
}

@Composable
private fun MonitorRow(label: String, value: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = LauncherColors.textMuted, fontSize = 13.sp)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun thermalLabel(status: Int, dash: String): String = when (status) {
    0 -> stringResource(R.string.monitor_thermal_none)
    1 -> stringResource(R.string.monitor_thermal_light)
    2 -> stringResource(R.string.monitor_thermal_moderate)
    3 -> stringResource(R.string.monitor_thermal_severe)
    in 4..6 -> stringResource(R.string.monitor_thermal_critical)
    else -> dash
}

private fun thermalColor(status: Int): Color = when (status) {
    0, 1 -> Color(0xFF34D399)
    2 -> Color(0xFFFBBF24)
    in 3..6 -> Color(0xFFF87171)
    else -> LauncherColors.textPrimary
}

private fun celsius(c: Float, dash: String): String = if (c.isNaN()) dash else String.format(Locale.ROOT, "%.0f°C", c)

private fun gigabytes(mb: Long): String = String.format(Locale.ROOT, "%.1f GB", mb / 1024f)
