package com.gow3.android.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gow3.android.R
import com.gow3.android.data.LaunchParam
import com.gow3.android.data.LaunchParams
import com.gow3.android.ui.components.LauncherColors

/** The game and update folders (Home). The launch parameters live in the Performance tab. */
@Composable
internal fun GameFolderCard(
    gameDir: String?,
    updateDir: String?,
    onChooseGameFolder: () -> Unit,
    onChooseUpdateFolder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LauncherColors.surface)
            .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(stringResource(R.string.launch_parameters_group_game), color = LauncherColors.accent, fontFamily = FontFamily.Serif, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onChooseGameFolder)
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.game_folder_label), color = LauncherColors.textMuted, fontSize = 13.sp)
                Text(stringResource(R.string.change_button), color = LauncherColors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
            }
            Text(
                gameDir ?: stringResource(R.string.game_folder_not_selected),
                color = if (gameDir != null) LauncherColors.textPrimary else LauncherColors.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
//            Text(stringResource(R.string.game_folder_hint), color = LauncherColors.textMuted, fontSize = 10.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onChooseUpdateFolder)
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.update_folder_label), color = LauncherColors.textMuted, fontSize = 13.sp)
                Text(stringResource(R.string.change_button), color = LauncherColors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
            }
            Text(
                updateDir ?: stringResource(R.string.update_folder_not_set),
                color = if (updateDir != null) LauncherColors.textPrimary else LauncherColors.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
            Text(stringResource(R.string.update_folder_description), color = LauncherColors.textMuted, fontSize = 10.sp)
        }
    }
}

@Composable
internal fun ParamRow(param: LaunchParam, values: Map<String, String>, save: (String, String) -> Unit) {
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(context.getString(param.labelRes), color = LauncherColors.textMuted, fontSize = 13.sp)
            if (param.descriptionRes != null) {
                Text(
                    "ⓘ",
                    color = LauncherColors.accent,
                    fontSize = 13.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showInfo = true }
                        .padding(2.dp),
                )
            }
        }
        if (param.options.size == 2 && param.options[0].value == "0" && param.options[1].value == "1") {
            Switch(
                checked = LaunchParams.valueOf(values, param) == "1",
                onCheckedChange = { save(param.key, if (it) "1" else "0") },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LauncherColors.textPrimary,
                    checkedTrackColor = LauncherColors.primary,
                    checkedBorderColor = LauncherColors.accent,
                    uncheckedThumbColor = LauncherColors.textMuted,
                    uncheckedTrackColor = LauncherColors.navSurface,
                    uncheckedBorderColor = LauncherColors.accent.copy(alpha = 0.35f),
                ),
            )
        } else {
            var expanded by remember { mutableStateOf(false) }
            Box {
                Text(
                    selectedLabel(context, values, param) + "  ▾",
                    color = LauncherColors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(LauncherColors.navSurface)
                        .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = 0.35f)), RoundedCornerShape(8.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    containerColor = LauncherColors.surfaceRaised,
                ) {
                    param.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(context.getString(option.labelRes), color = LauncherColors.textPrimary) },
                            onClick = {
                                expanded = false
                                save(param.key, option.value)
                            },
                        )
                    }
                }
            }
        }
    }
    if (showInfo && param.descriptionRes != null) {
        ParamInfoDialog(
            title = context.getString(param.labelRes),
            message = context.getString(param.descriptionRes),
            onDismiss = { showInfo = false },
        )
    }
}

/** 当前值对应的本地化展示名；未知值回退到原始值。 */
private fun selectedLabel(context: android.content.Context, values: Map<String, String>, param: LaunchParam): String {
    val option = LaunchParams.selectedOption(values, param)
    return option?.let { context.getString(it.labelRes) } ?: LaunchParams.valueOf(values, param)
}

@Composable
private fun ParamInfoDialog(title: String, message: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LauncherColors.surface)
                .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = LauncherColors.accent, fontFamily = FontFamily.Serif, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Text(message, color = LauncherColors.textMuted, fontSize = 13.sp, lineHeight = 19.sp)
            Text(
                stringResource(R.string.dialog_ok),
                color = LauncherColors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LauncherColors.navSurface)
                    .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = 0.45f)), RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
