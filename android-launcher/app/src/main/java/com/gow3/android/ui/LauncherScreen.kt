package com.gow3.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gow3.android.R
import com.gow3.android.data.LogManager
import com.gow3.android.data.LosslessDllManager
import com.gow3.android.data.SaveManager
import com.gow3.android.feature.about.AboutScreen
import com.gow3.android.feature.home.HomeScreen
import com.gow3.android.feature.mods.ModsScreen
import com.gow3.android.feature.performance.PerformanceScreen
import com.gow3.android.ui.components.LauncherColors

private enum class LauncherTab { HOME, PERFORMANCE, MODS, ABOUT }

@Composable
internal fun LauncherScreen(
    onStartGame: () -> Unit,
    losslessDlls: LosslessDllManager,
    onGetFromSteam: ((Boolean, String) -> Unit) -> Unit,
    onChooseGameFolder: () -> Unit,
    onChooseUpdateFolder: () -> Unit,
    saves: SaveManager,
    logs: LogManager,
    onChangeLanguage: (String) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(LauncherTab.HOME.ordinal) }
    val tab = LauncherTab.entries[selectedTab]

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = { BottomNavigation(tab) { selectedTab = it.ordinal } },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Image(
                painter = painterResource(R.drawable.bg_wallpaper),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(listOf(LauncherColors.backgroundTop.copy(alpha = 0.55f), LauncherColors.backgroundBottom.copy(alpha = 0.85f)), radius = 950f)),
            )
            when (tab) {
                LauncherTab.HOME -> HomeScreen(onStartGame, losslessDlls, onGetFromSteam, onChooseGameFolder, onChooseUpdateFolder)
                LauncherTab.PERFORMANCE -> PerformanceScreen(losslessDlls, onGetFromSteam)
                LauncherTab.MODS -> ModsScreen()
                LauncherTab.ABOUT -> AboutScreen(saves, logs, onChangeLanguage)
            }
        }
    }
}

@Composable
private fun BottomNavigation(selectedTab: LauncherTab, onTabSelected: (LauncherTab) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(LauncherColors.backgroundBottom),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .background(LauncherColors.backgroundBottom)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(LauncherColors.navSurface)
                .border(BorderStroke(1.dp, LauncherColors.accent.copy(alpha = 0.35f)), RoundedCornerShape(28.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavItem(LauncherTab.HOME, selectedTab == LauncherTab.HOME, stringResource(R.string.nav_home), "⌂", onTabSelected)
            NavItem(LauncherTab.PERFORMANCE, selectedTab == LauncherTab.PERFORMANCE, stringResource(R.string.nav_performance), "⚡", onTabSelected)
//        NavItem(LauncherTab.MODS, selectedTab == LauncherTab.MODS, "MOD & PATCH", "⚙", onTabSelected)
            NavItem(LauncherTab.ABOUT, selectedTab == LauncherTab.ABOUT, stringResource(R.string.nav_about), "ⓘ", onTabSelected)
        }
    }

}

@Composable
private fun RowScope.NavItem(
    tab: LauncherTab,
    selected: Boolean,
    label: String,
    icon: String,
    onTabSelected: (LauncherTab) -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (selected) LauncherColors.primary else Color.Transparent)
            .clickable { onTabSelected(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(icon, color = if (selected) LauncherColors.textPrimary else LauncherColors.textMuted, fontSize = 17.sp, lineHeight = 17.sp)
        Text(label, color = if (selected) LauncherColors.textPrimary else LauncherColors.textMuted, fontSize = 9.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.3.sp, maxLines = 1)
    }
}
