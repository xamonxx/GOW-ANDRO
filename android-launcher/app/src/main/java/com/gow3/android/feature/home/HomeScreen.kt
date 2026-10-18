package com.gow3.android.feature.home

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.gow3.android.data.Gow3Ini
import com.gow3.android.data.LaunchParams
import com.gow3.android.data.PerformancePresets
import com.gow3.android.data.LosslessDllManager
import com.gow3.android.data.LosslessDllState
import com.gow3.android.feature.performance.DeviceMonitorCard
import com.gow3.android.ui.components.LauncherColors
import com.gow3.android.ui.components.ScreenColumn
import java.io.File

@Composable
internal fun HomeScreen(
    onStartGame: () -> Unit,
    losslessDlls: LosslessDllManager,
    onGetFromSteam: ((Boolean, String) -> Unit) -> Unit,
    onChooseGameFolder: () -> Unit,
    onChooseUpdateFolder: () -> Unit,
) {
    val context = LocalContext.current
    var iniValues by remember { mutableStateOf(loadIni(context)) }
    var dllState by remember { mutableStateOf(losslessDlls.resolve()) }
    var gameDir by remember { mutableStateOf(currentGameDir(context)) }
    var updateDir by remember { mutableStateOf(currentUpdateDir(context)) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Bumped when Home comes back to the front: the last session and gow3.ini may have changed.
    var resumeTick by remember { mutableIntStateOf(0) }

    fun refreshDll() {
        dllState = losslessDlls.resolve()
    }

    fun toast(message: String) {
        notice = message
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 玩家可能在游戏运行期间把 Lossless.dll 放到游戏目录旁，因此每次 Home 回到前台
    // 时都会从磁盘重新读取状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshDll()
                gameDir = currentGameDir(context)
                updateDir = currentUpdateDir(context)
                iniValues = loadIni(context)
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** An advice's settings, written together. */
    fun applyAll(updates: Map<String, String>) {
        if (Gow3Ini.write(gow3IniFile(context), updates)) {
            iniValues = iniValues + updates
            toast(context.getString(R.string.advice_applied))
        } else {
            Toast.makeText(context, context.getString(R.string.settings_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    ScreenColumn(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.title_god_of_war_iii), color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(stringResource(R.string.title_remastered), color = LauncherColors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
        Spacer(Modifier.height(24.dp))
        LaunchButton(onStartGame)
        Spacer(Modifier.height(12.dp))
        StatusStrip(iniValues, dllState is LosslessDllState.Installed)
        Spacer(Modifier.height(16.dp))
        DeviceMonitorCard()
        Spacer(Modifier.height(16.dp))
        SmartCards(iniValues, dllState is LosslessDllState.Installed, gameDir, resumeTick, ::applyAll)
        GameFolderCard(gameDir, updateDir, onChooseGameFolder, onChooseUpdateFolder)
        Spacer(Modifier.height(24.dp))
    }
}

/** What the next start runs with: the preset (or custom), resolution, frame limit, Smart FPS. */
@Composable
private fun StatusStrip(ini: Map<String, String>, lsfgAvailable: Boolean) {
    val preset = PerformancePresets.detect(ini, lsfgAvailable)
    val chips = listOfNotNull(
        preset?.let { presetName(it) } ?: stringResource(R.string.preset_custom),
        LaunchParams.all.firstOrNull { it.key == "render_resolution" }?.let { param ->
            LaunchParams.selectedOption(ini, param)?.let { stringResource(it.labelRes) } ?: LaunchParams.valueOf(ini, param)
        },
        LaunchParams.all.firstOrNull { it.key == "fps_limit" }?.let { param ->
            val value = LaunchParams.valueOf(ini, param)
            if (value == "0") stringResource(R.string.param_option_unlimited) else "$value FPS"
        },
        if (ini["thermal_guard"]?.trim() != "0") stringResource(R.string.status_smart_fps) else null,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
        chips.forEach { label ->
            Text(
                label,
                color = LauncherColors.accentSoft,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(LauncherColors.navSurface)
                    .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = 0.4f)), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** launcher 的 gow3.ini 路径，即游戏、run-gow3.sh 和 GameActivity 读取的同一个文件。 */
private fun gow3IniFile(context: Context): File = File(context.filesDir, "gow3port/gow3.ini")

private fun loadIni(context: Context): Map<String, String> = Gow3Ini.read(gow3IniFile(context))

private fun currentGameDir(context: Context): String? =
    context.getSharedPreferences("gow3", Context.MODE_PRIVATE).getString("game_dir", null)

private fun currentUpdateDir(context: Context): String? =
    context.getSharedPreferences("gow3", Context.MODE_PRIVATE).getString("update_dir", null)

@Composable
private fun LaunchButton(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(LauncherColors.primary, LauncherColors.primaryDeep, LauncherColors.primary)))
            .border(BorderStroke(1.dp, LauncherColors.accentSoft), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.launch_button), color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
        Text(stringResource(R.string.start_game_button), color = LauncherColors.textPrimary, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
    }
}
