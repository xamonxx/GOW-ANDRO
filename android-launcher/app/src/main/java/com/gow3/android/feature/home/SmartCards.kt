package com.gow3.android.feature.home

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R
import com.gow3.android.data.Advice
import com.gow3.android.data.AdviceKind
import com.gow3.android.data.Advisor
import com.gow3.android.data.DeviceFacts
import com.gow3.android.data.PerfPreset
import com.gow3.android.data.SessionReport
import com.gow3.android.ui.components.LauncherColors
import ro.cobrabm.fexdroid.SessionRecorder
import java.io.File
import java.util.Locale

/** The last session (GameActivity's logs/session-last.json) and the advice drawn from it, the
 *  launch history, gow3.ini and the phone. [refresh] changes when Home comes back to the front. */
@Composable
internal fun SmartCards(
    ini: Map<String, String>,
    lsfgAvailable: Boolean,
    gameDir: String?,
    refresh: Int,
    onApply: (Map<String, String>) -> Unit,
) {
    val context = LocalContext.current
    val port = File(context.filesDir, "gow3port")
    val session = remember(refresh) {
        runCatching { SessionReport.parse(File(port, "logs/session-last.json").readText()) }.getOrNull()
    }
    val crashedTwice = remember(refresh) { SessionRecorder.crashedTwice(SessionRecorder.history(File(port, "launch-history"))) }
    val device = remember(refresh, lsfgAvailable, gameDir) { deviceFacts(context, lsfgAvailable, gameDir) }
    val advice = remember(session, crashedTwice, ini, device) { Advisor.advise(session, ini, device, crashedTwice) }

    if (session != null) {
        Card {
            Heading(stringResource(R.string.session_title))
            val dash = stringResource(R.string.monitor_unavailable)
            Text(
                stringResource(
                    R.string.session_summary_fmt,
                    (session.durationS / 60).toString(),
                    if (session.avgFps < 0) dash else String.format(Locale.ROOT, "%.0f", session.avgFps),
                    if (session.low1Fps < 0) dash else session.low1Fps.toString(),
                    if (session.maxBatteryC < 0) dash else String.format(Locale.ROOT, "%.0f°C", session.maxBatteryC),
                ),
                color = LauncherColors.textPrimary, fontSize = 13.sp,
            )
            if (session.severeS > 0 || session.caps > 0) {
                Text(
                    stringResource(R.string.session_hot_fmt, session.severeS.toString(), session.caps.toString()),
                    color = LauncherColors.textMuted, fontSize = 12.sp,
                )
            }
        }
    }
    if (advice.isNotEmpty()) {
        Card {
            Heading(stringResource(R.string.advice_title))
            advice.forEach { AdviceRow(it, onApply) }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LauncherColors.surface)
            .border(BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = LauncherColors.gold, fontFamily = FontFamily.Serif, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
}

@Composable
private fun AdviceRow(advice: Advice, onApply: (Map<String, String>) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(adviceText(advice), color = LauncherColors.textPrimary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        if (advice.updates.isNotEmpty()) {
            Text(
                stringResource(R.string.advice_apply),
                color = LauncherColors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(LauncherColors.crimson)
                    .clickable { onApply(advice.updates) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun adviceText(advice: Advice): String = when (advice.kind) {
    AdviceKind.RECOMMENDED_PRESET -> stringResource(R.string.advice_recommended_fmt, presetName(advice.preset ?: PerfPreset.BALANCED))
    AdviceKind.OVERHEATED -> stringResource(R.string.advice_overheated)
    AdviceKind.LOW_FPS -> stringResource(R.string.advice_low_fps)
    AdviceKind.LSFG_TOO_HIGH -> stringResource(R.string.advice_lsfg_high)
    AdviceKind.GAME_ON_USB -> stringResource(R.string.advice_usb)
    AdviceKind.CRASHES_FAST_CPU -> stringResource(R.string.advice_crashes)
    AdviceKind.BATTERY_LOW -> stringResource(R.string.advice_battery_low)
    AdviceKind.CHARGING_HOT -> stringResource(R.string.advice_charging_hot)
}

@Composable
internal fun presetName(preset: PerfPreset): String = when (preset) {
    PerfPreset.MAX -> stringResource(R.string.preset_max)
    PerfPreset.BALANCED -> stringResource(R.string.preset_balanced)
    PerfPreset.COOL -> stringResource(R.string.preset_cool)
}

/** The phone as the advisor sees it; unknown values are neutral (no advice from them). */
private fun deviceFacts(context: Context, lsfgAvailable: Boolean, gameDir: String?): DeviceFacts {
    val memory = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    @Suppress("DEPRECATION")
    val display = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    val maxRefresh = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
    return DeviceFacts(
        socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
        totalRamMb = memory.totalMem shr 20,
        maxRefreshHz = maxRefresh,
        batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
        charging = plugged,
        batteryC = if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f,
        lsfgAvailable = lsfgAvailable,
        gameOnRemovable = gameDir != null && gameDir.startsWith("/storage/") && !gameDir.startsWith("/storage/emulated/"),
    )
}
