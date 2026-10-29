package com.aura.music.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Sizes

private data class TopLevel(val route: Any, val icon: ImageVector, val label: Int)

private val destinations = listOf(
    TopLevel(HomeRoute, AuraIcons.Home, R.string.nav_home),
    TopLevel(LibraryRoute, AuraIcons.Library, R.string.nav_library),
    TopLevel(SearchRoute, AuraIcons.Search, R.string.nav_search),
    TopLevel(DiscoverRoute, AuraIcons.Discover, R.string.nav_discover),
)

/** Calm four-destination bar; the active item gets a short accent rule instead of a heavy pill. */
@Composable
fun AuraBottomBar(currentTab: Any, onNavigate: (Any) -> Unit, modifier: Modifier = Modifier) {
    val background = AuraTheme.colors.background
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(background.copy(alpha = 0.92f), background))),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(Sizes.bottomBarHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            destinations.forEach { destination ->
                val selected = destination.route == currentTab
                BarItem(destination.icon, stringResource(destination.label), selected, onClick = { onNavigate(destination.route) })
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val tint by animateColorAsState(
        if (selected) AuraTheme.colors.textPrimary else AuraTheme.colors.textTertiary,
        Motion.tween(Motion.SHORT, reduced),
        label = "barTint",
    )
    val indicator by animateDpAsState(if (selected) 18.dp else 0.dp, Motion.tween(Motion.MEDIUM, reduced), label = "barIndicator")
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .width(indicator)
                .height(2.dp)
                .clip(Radius.pill)
                .background(if (selected) AuraTheme.colors.accent else Color.Transparent),
        )
        Spacer(Modifier.height(6.dp))
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = AuraTheme.type.label.copy(fontSize = AuraTheme.type.overline.fontSize), color = tint)
    }
}
