package com.gow3.android.feature.home

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gow3.android.R
import com.gow3.android.data.ImportResult
import com.gow3.android.data.LosslessDllManager
import com.gow3.android.data.RemoveResult
import com.gow3.android.ui.components.formatBytes

/** The Lossless.dll card with its import / Steam / remove actions (Performance > Frame Gen). */
@Composable
internal fun FrameGenerationSection(
    losslessDlls: LosslessDllManager,
    onGetFromSteam: ((Boolean, String) -> Unit) -> Unit,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    var dllState by remember { mutableStateOf(losslessDlls.resolve()) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        dllState = losslessDlls.resolve()
        onChanged()
    }

    fun toast(message: String) {
        notice = message
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    FrameGenerationCard(
        state = dllState,
        notice = notice,
        onImport = {
            losslessDlls.launchPicker { result ->
                when (result) {
                    is ImportResult.Success -> {
                        refresh()
                        toast(context.getString(R.string.home_imported_fmt, LosslessDllManager.FILE_NAME, formatBytes(result.state.sizeBytes)))
                    }
                    ImportResult.Cancelled -> refresh()
                    is ImportResult.Failed -> toast(result.message)
                }
            }
        },
        onSteam = {
            // Steam 下载成功后（该构建不含 Steam 时则由 stub 自行提示）回到这里刷新。
            onGetFromSteam { _, message ->
                refresh()
                toast(message)
            }
        },
        onRemove = {
            when (val result = losslessDlls.remove()) {
                is RemoveResult.Done -> {
                    refresh()
                    toast(
                        if (result.gameFolderRemains)
                            context.getString(R.string.home_removed_game_folder_copy)
                        else
                            context.getString(R.string.home_removed_fmt, LosslessDllManager.FILE_NAME),
                    )
                }
                is RemoveResult.Failed -> toast(result.message)
            }
        },
    )
}
