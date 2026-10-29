package com.aura.music.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/** Screen-agnostic navigation entry points, so features never depend on each other's internals. */
@Stable
class Navigator(private val navController: NavHostController) {

    /** Tab the library should show next time it's displayed (set by shortcuts such as Home → Playlists). */
    var requestedLibraryTab by mutableStateOf<LibraryTab?>(null)

    /** The bottom-navigation section the user is in, including its detail screens. */
    var currentTab: Any by mutableStateOf(HomeRoute)
        private set

    private val topLevelRoutes = listOf(HomeRoute, LibraryRoute, SearchRoute, DiscoverRoute)

    init {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            topLevelRoutes.firstOrNull { route -> destination.hasRoute(route::class) }?.let { currentTab = it }
        }
    }

    /** Switches sections, restoring each one's own back stack; re-selecting a section returns to its root. */
    fun openTopLevel(route: Any) {
        if (route == currentTab && navController.popBackStack(route, inclusive = false)) return
        currentTab = route
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openAlbum(albumId: Long) = navController.navigate(AlbumRoute(albumId))
    fun openArtist(artistId: Long) = navController.navigate(ArtistRoute(artistId))
    fun openPlaylist(playlistId: Long) = navController.navigate(PlaylistRoute(playlistId))
    fun openFavorites() = navController.navigate(CollectionRoute(CollectionKind.Favorites))
    fun openGenre(name: String) = navController.navigate(CollectionRoute(CollectionKind.Genre, name))
    fun openFolder(path: String) = navController.navigate(CollectionRoute(CollectionKind.Folder, path))
    fun openMix(kind: com.aura.music.data.repository.MixKind) = navController.navigate(CollectionRoute(CollectionKind.Mix, kind.name))
    fun openPlaylists() {
        requestedLibraryTab = LibraryTab.Playlists
        openTopLevel(LibraryRoute)
    }
    fun openRecap() = navController.navigate(RecapRoute) { launchSingleTop = true }
    fun openSettings() = navController.navigate(SettingsRoute) { launchSingleTop = true }
    fun openLicenses() = navController.navigate(LicensesRoute)
    fun openTogether() = navController.navigate(TogetherRoute) { launchSingleTop = true }
    fun openLink() = navController.navigate(LinkRoute) { launchSingleTop = true }
    fun openLinkRemote(deviceId: String) = navController.navigate(LinkRemoteRoute(deviceId))
    fun openSettingsSection(section: SettingsSection) = navController.navigate(SettingsSectionRoute(section)) { launchSingleTop = true }
    fun openSearch() = openTopLevel(SearchRoute)
    fun openTrackPicker(playlistId: Long?) = navController.navigate(TrackPickerRoute(playlistId))
    fun back() = navController.popBackStack()
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
