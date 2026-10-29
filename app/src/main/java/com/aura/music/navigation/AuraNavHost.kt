package com.aura.music.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.feature.album.AlbumScreen
import com.aura.music.feature.artist.ArtistScreen
import com.aura.music.feature.collection.CollectionScreen
import com.aura.music.feature.home.HomeScreen
import com.aura.music.feature.recap.RecapScreen
import com.aura.music.feature.library.LibraryScreen
import com.aura.music.feature.playlist.PlaylistScreen
import com.aura.music.feature.playlist.TrackPickerScreen
import com.aura.music.feature.search.SearchScreen
import com.aura.music.feature.discover.DiscoverScreen
import com.aura.music.feature.together.TogetherScreen
import com.aura.music.feature.settings.LicensesScreen
import com.aura.music.feature.settings.SettingsScreen
import com.aura.music.feature.settings.SettingsSectionScreen
import androidx.navigation.toRoute

/**
 * Detail screens glide in slightly from the side while fading, a short and quiet transition;
 * with reduced motion everything switches instantly.
 */
@Composable
fun AuraNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
        enterTransition = {
            if (reduced) {
                EnterTransition.None
            } else {
                fadeIn(Motion.tween(Motion.MEDIUM, false)) + slideInHorizontally(Motion.tween(Motion.LONG, false, Motion.Decelerate)) { it / 14 }
            }
        },
        exitTransition = { if (reduced) ExitTransition.None else fadeOut(Motion.tween(Motion.SHORT, false)) },
        popEnterTransition = { if (reduced) EnterTransition.None else fadeIn(Motion.tween(Motion.MEDIUM, false)) },
        popExitTransition = {
            if (reduced) {
                ExitTransition.None
            } else {
                fadeOut(Motion.tween(Motion.SHORT, false)) + slideOutHorizontally(Motion.tween(Motion.MEDIUM, false)) { it / 14 }
            }
        },
    ) {
        composable<HomeRoute> { HomeScreen() }
        composable<LibraryRoute> { LibraryScreen() }
        composable<SearchRoute> { SearchScreen() }
        composable<DiscoverRoute> { DiscoverScreen() }
        composable<TogetherRoute> { TogetherScreen() }
        composable<LinkRoute> { com.aura.music.feature.link.LinkScreen() }
        composable<LinkRemoteRoute> { entry -> com.aura.music.feature.link.LinkRemoteScreen(entry.toRoute<LinkRemoteRoute>().deviceId) }
        composable<SettingsRoute> { SettingsScreen() }
        composable<SettingsSectionRoute> { entry -> SettingsSectionScreen(entry.toRoute<SettingsSectionRoute>().section) }
        composable<LicensesRoute> { LicensesScreen() }
        composable<RecapRoute> { RecapScreen() }
        composable<AlbumRoute> { AlbumScreen() }
        composable<ArtistRoute> { ArtistScreen() }
        composable<PlaylistRoute> { PlaylistScreen() }
        composable<CollectionRoute> { CollectionScreen() }
        composable<TrackPickerRoute> { TrackPickerScreen() }
    }
}
