package com.aura.music.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute

@Serializable data object LibraryRoute

@Serializable data object SearchRoute

@Serializable data object DiscoverRoute

@Serializable data object TogetherRoute

enum class LibraryTab { Songs, Albums, Artists, Playlists, Folders, Genres, Audios }

@Serializable data object SettingsRoute

enum class SettingsSection { Appearance, Home, Playback, Equalizer, Library, Folders, Hidden, Lyrics, Privacy, Backup }

@Serializable data class SettingsSectionRoute(val section: SettingsSection)

@Serializable data object LicensesRoute

@Serializable data object RecapRoute

@Serializable data class AlbumRoute(val albumId: Long)

@Serializable data class ArtistRoute(val artistId: Long)

@Serializable data class PlaylistRoute(val playlistId: Long)

enum class CollectionKind { Favorites, Genre, Folder, Mix }

@Serializable data class CollectionRoute(val kind: CollectionKind, val key: String = "")

/** Multi-select track picker. A null [playlistId] means "add to the playback queue". */
@Serializable data class TrackPickerRoute(val playlistId: Long? = null)
