package com.gow3.android.feature.about

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gow3.android.R
import com.gow3.android.data.LanguageManager
import com.gow3.android.data.LogArchive
import com.gow3.android.data.LogManager
import com.gow3.android.data.LogResult
import com.gow3.android.data.SaveArchive
import com.gow3.android.data.SaveManager
import com.gow3.android.data.SaveResult
import com.gow3.android.ui.components.LauncherColors
import com.gow3.android.ui.components.ScreenColumn

@Composable
internal fun AboutScreen(
    saves: SaveManager,
    logs: LogManager,
    onChangeLanguage: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasSaves by remember { mutableStateOf(SaveArchive.hasSaves(saves.savedataDir())) }
    var hasLogs by remember { mutableStateOf(LogArchive.hasLogs(logs.logsDir())) }

    fun refresh() {
        hasSaves = SaveArchive.hasSaves(saves.savedataDir())
        hasLogs = LogArchive.hasLogs(logs.logsDir())
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 玩家可能在应用退到后台期间备份/恢复存档、安装运行时或运行游戏，
    // 因此每次 About 回到前台时都会从磁盘重新读取 savedata/日志状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ScreenColumn {
        Text(stringResource(R.string.about_title), color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.about_version_fmt, "1.2.4"), color = LauncherColors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.about_description), color = LauncherColors.textMuted, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(8.dp))
        ContactCard()
        GitHubCard()
        SaveCard(
            hasSaves = hasSaves,
            onExport = {
                saves.launchExport { result ->
                    when (result) {
                        is SaveResult.Success -> toast(result.message)
                        SaveResult.Cancelled -> Unit
                        is SaveResult.Failed -> toast(result.message)
                    }
                }
            },
            onImport = {
                saves.launchImport { result ->
                    when (result) {
                        is SaveResult.Success -> {
                            refresh()
                            toast(result.message)
                        }
                        SaveResult.Cancelled -> Unit
                        is SaveResult.Failed -> toast(result.message)
                    }
                }
            },
        )
        LogCard(
            hasLogs = hasLogs,
            onExport = {
                logs.launchExport { result ->
                    when (result) {
                        is LogResult.Success -> {
                            refresh()
                            toast(result.message)
                        }
                        LogResult.Cancelled -> Unit
                        is LogResult.Failed -> toast(result.message)
                    }
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LauncherColors.surface)
                .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
                .clickable { }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.about_third_party_licenses), color = LauncherColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("›", color = LauncherColors.accent, fontSize = 24.sp)
        }
        LanguageSetting(onChangeLanguage)
    }
}

/** 语言 / Language 设置：跟随系统 / 简体中文 / English，切换后由调用方持久化并重建。 */
@Composable
private fun LanguageSetting(onChange: (String) -> Unit) {
    val context = LocalContext.current
    val current = LanguageManager.read(context)
    val options = listOf(
        R.string.language_id to LanguageManager.ID,
        R.string.language_en to LanguageManager.EN,
        R.string.language_zh to LanguageManager.ZH,
        R.string.language_follow_system to LanguageManager.FOLLOW_SYSTEM,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LauncherColors.surface)
            .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.language_setting), color = LauncherColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (labelRes, value) ->
                val selected = current == value
                Text(
                    stringResource(labelRes),
                    color = if (selected) LauncherColors.textPrimary else LauncherColors.textMuted,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) LauncherColors.primary else LauncherColors.navSurface)
                        .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = 0.45f)), RoundedCornerShape(8.dp))
                        .clickable { onChange(value) }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                )
            }
        }
    }
}
