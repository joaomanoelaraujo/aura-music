package com.aura.music.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aura.music.core.model.AlbumSort
import com.aura.music.core.model.ArtistSort
import com.aura.music.core.model.CornerStyle
import com.aura.music.core.model.CoverSize
import com.aura.music.core.model.DefaultRepeat
import com.aura.music.core.model.HomeSectionPref
import com.aura.music.core.model.HomeSections
import com.aura.music.core.model.LibraryLayout
import com.aura.music.core.model.ListDensity
import com.aura.music.core.model.MiniPlayerStyle
import com.aura.music.core.model.MotionLevel
import com.aura.music.core.model.NowPlayingButton
import com.aura.music.core.model.OutputPreference
import com.aura.music.core.model.PreviousBehavior
import com.aura.music.core.model.SortSpec
import com.aura.music.core.model.ThemeMode
import com.aura.music.core.model.TrackSort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class AppSettings(
    val onboardingCompleted: Boolean = false,
    // Playback
    val resumeLastSession: Boolean = true,
    val skipSilence: Boolean = false,
    /** Turn loud songs down so everything plays at a similar volume (from each song's analysis). */
    val normalizeVolume: Boolean = true,
    /** Seconds songs fade into each other; 0 = straight cut. */
    val transitionSeconds: Int = 0,
    /** Analyze the whole library while charging, for mood mixes and even volume. */
    val analyzeLibrary: Boolean = true,
    val pauseOnDisconnect: Boolean = true,
    val resumeOnConnect: Boolean = false,
    val defaultRepeat: DefaultRepeat = DefaultRepeat.Remember,
    val keepShuffle: Boolean = false,
    val previousBehavior: PreviousBehavior = PreviousBehavior.RestartFirst,
    val outputPreference: OutputPreference = OutputPreference.Auto,
    // Appearance
    val themeMode: ThemeMode = ThemeMode.Dark,
    val adaptiveAtmosphere: Boolean = true,
    val atmosphereIntensity: Float = 1f,
    val gradientIntensity: Float = 1f,
    val cornerStyle: CornerStyle = CornerStyle.Soft,
    val coverSize: CoverSize = CoverSize.Medium,
    val listDensity: ListDensity = ListDensity.Comfortable,
    val textScale: Float = 1f,
    val motion: MotionLevel = MotionLevel.Full,
    val miniPlayerStyle: MiniPlayerStyle = MiniPlayerStyle.Floating,
    val miniPlayerSwipe: Boolean = true,
    val nowPlayingButtons: Set<NowPlayingButton> = setOf(NowPlayingButton.Lyrics, NowPlayingButton.Details, NowPlayingButton.Queue, NowPlayingButton.Equalizer),
    val homeSections: List<HomeSectionPref> = HomeSections.Default,
    // Library
    val minTrackDurationSec: Int = 0,
    val excludedFolders: Set<String> = emptySet(),
    val hideWhatsApp: Boolean = false,
    /** Hide files whose title is just a date, a time or hashtags (saved statuses and clips). */
    val hideTimestampTitles: Boolean = false,
    val showUntagged: Boolean = true,
    val lyricsFolders: Set<String> = emptySet(),
    /** Look for lyrics on the internet (LRCLIB) when a song has none, and keep them for offline use. */
    val onlineLyrics: Boolean = false,
    /** Lyric lines react to the music: glow on calm parts, pulse and shake on strong ones. */
    val lyricsEffects: Boolean = true,
    /** Look for covers online (MusicBrainz) for albums that have none. */
    val onlineCovers: Boolean = false,
    /**
     * Whether the user already answered "look for lyrics and covers online?". Both lookups send
     * song names to outside services, so they stay off until the user says yes.
     */
    val onlineConsentAsked: Boolean = false,
    val trackSort: SortSpec<TrackSort> = SortSpec(TrackSort.Title, ascending = true),
    val albumSort: SortSpec<AlbumSort> = SortSpec(AlbumSort.Title, ascending = true),
    val artistSort: SortSpec<ArtistSort> = SortSpec(ArtistSort.Name, ascending = true),
    val albumLayout: LibraryLayout = LibraryLayout.Grid,
    val artistLayout: LibraryLayout = LibraryLayout.List,
    // Search & privacy
    val searchHistoryEnabled: Boolean = true,
    val personalization: Boolean = true,
    // AURA Together
    val displayName: String = "",
    val acceptInvites: Boolean = true,
    val approveJoins: Boolean = true,
    val togetherDemocratic: Boolean = true,
    val togetherMaxParticipants: Int = 8,
) {
    val reduceMotion: Boolean get() = motion == MotionLevel.Off
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val store get() = context.settingsStore

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun setOnboardingCompleted() = store.edit { it[Keys.ONBOARDING] = true }

    /** Every stored preference, for a backup. */
    suspend fun exportAll(): Map<String, Any> = store.data.first().asMap().mapKeys { it.key.name }

    /** Restores preferences from a backup, replacing the current values of the keys it contains. */
    suspend fun importAll(values: Map<String, Any>) = store.edit { prefs -> PreferenceValues.write(prefs, values) }

    // Playback
    suspend fun setResumeLastSession(value: Boolean) = store.edit { it[Keys.RESUME] = value }
    suspend fun setSkipSilence(value: Boolean) = store.edit { it[Keys.SKIP_SILENCE] = value }
    suspend fun setNormalizeVolume(value: Boolean) = store.edit { it[Keys.NORMALIZE_VOLUME] = value }
    suspend fun setTransitionSeconds(value: Int) = store.edit { it[Keys.TRANSITION_SECONDS] = value }
    suspend fun setAnalyzeLibrary(value: Boolean) = store.edit { it[Keys.ANALYZE_LIBRARY] = value }
    suspend fun setPauseOnDisconnect(value: Boolean) = store.edit { it[Keys.PAUSE_ON_DISCONNECT] = value }
    suspend fun setResumeOnConnect(value: Boolean) = store.edit { it[Keys.RESUME_ON_CONNECT] = value }
    suspend fun setDefaultRepeat(value: DefaultRepeat) = store.edit { it[Keys.DEFAULT_REPEAT] = value.name }
    suspend fun setKeepShuffle(value: Boolean) = store.edit { it[Keys.KEEP_SHUFFLE] = value }
    suspend fun setPreviousBehavior(value: PreviousBehavior) = store.edit { it[Keys.PREVIOUS] = value.name }
    suspend fun setOutputPreference(value: OutputPreference) = store.edit { it[Keys.OUTPUT] = value.name }

    // Appearance
    suspend fun setThemeMode(value: ThemeMode) = store.edit { it[Keys.THEME] = value.name }
    suspend fun setAdaptiveAtmosphere(value: Boolean) = store.edit { it[Keys.ATMOSPHERE] = value }
    suspend fun setAtmosphereIntensity(value: Float) = store.edit { it[Keys.ATMOSPHERE_INTENSITY] = value.coerceIn(0f, 1f) }
    suspend fun setGradientIntensity(value: Float) = store.edit { it[Keys.GRADIENT_INTENSITY] = value.coerceIn(0f, 1f) }
    suspend fun setCornerStyle(value: CornerStyle) = store.edit { it[Keys.CORNERS] = value.name }
    suspend fun setCoverSize(value: CoverSize) = store.edit { it[Keys.COVER_SIZE] = value.name }
    suspend fun setListDensity(value: ListDensity) = store.edit { it[Keys.DENSITY] = value.name }
    suspend fun setTextScale(value: Float) = store.edit { it[Keys.TEXT_SCALE] = value }
    suspend fun setMotion(value: MotionLevel) = store.edit { it[Keys.MOTION] = value.name }
    suspend fun setMiniPlayerStyle(value: MiniPlayerStyle) = store.edit { it[Keys.MINI_STYLE] = value.name }
    suspend fun setMiniPlayerSwipe(value: Boolean) = store.edit { it[Keys.MINI_SWIPE] = value }
    suspend fun setNowPlayingButtons(value: Set<NowPlayingButton>) = store.edit { prefs ->
        prefs[Keys.NOW_PLAYING_BUTTONS] = value.mapTo(HashSet()) { it.name }
    }
    suspend fun setHomeSections(value: List<HomeSectionPref>) = store.edit { it[Keys.HOME_SECTIONS] = HomeSections.encode(value) }

    // Library
    suspend fun setMinTrackDuration(seconds: Int) = store.edit { it[Keys.MIN_DURATION] = seconds }
    suspend fun setFolderExcluded(path: String, excluded: Boolean) = store.edit { prefs ->
        val current = prefs[Keys.EXCLUDED_FOLDERS].orEmpty()
        prefs[Keys.EXCLUDED_FOLDERS] = if (excluded) current + path else current - path
    }
    suspend fun setHideWhatsApp(value: Boolean) = store.edit { it[Keys.HIDE_WHATSAPP] = value }
    suspend fun setHideTimestampTitles(value: Boolean) = store.edit { it[Keys.HIDE_TIMESTAMP_TITLES] = value }
    suspend fun setShowUntagged(value: Boolean) = store.edit { it[Keys.SHOW_UNTAGGED] = value }
    suspend fun addLyricsFolder(uri: String) = store.edit { it[Keys.LYRICS_FOLDERS] = it[Keys.LYRICS_FOLDERS].orEmpty() + uri }
    suspend fun removeLyricsFolder(uri: String) = store.edit { it[Keys.LYRICS_FOLDERS] = it[Keys.LYRICS_FOLDERS].orEmpty() - uri }
    suspend fun setOnlineLyrics(value: Boolean) = store.edit { it[Keys.ONLINE_LYRICS] = value }
    suspend fun setLyricsEffects(value: Boolean) = store.edit { it[Keys.LYRICS_EFFECTS] = value }
    suspend fun setOnlineCovers(value: Boolean) = store.edit { it[Keys.ONLINE_COVERS] = value }

    suspend fun answerOnlineConsent(allow: Boolean) = store.edit {
        it[Keys.ONLINE_LYRICS] = allow
        it[Keys.ONLINE_COVERS] = allow
        it[Keys.ONLINE_CONSENT] = true
    }
    suspend fun setTrackSort(spec: SortSpec<TrackSort>) = store.edit { it[Keys.TRACK_SORT] = spec.encode() }
    suspend fun setAlbumSort(spec: SortSpec<AlbumSort>) = store.edit { it[Keys.ALBUM_SORT] = spec.encode() }
    suspend fun setArtistSort(spec: SortSpec<ArtistSort>) = store.edit { it[Keys.ARTIST_SORT] = spec.encode() }
    suspend fun setAlbumLayout(layout: LibraryLayout) = store.edit { it[Keys.ALBUM_LAYOUT] = layout.name }
    suspend fun setArtistLayout(layout: LibraryLayout) = store.edit { it[Keys.ARTIST_LAYOUT] = layout.name }

    // Search & privacy
    suspend fun setSearchHistoryEnabled(value: Boolean) = store.edit { it[Keys.SEARCH_HISTORY] = value }
    suspend fun setPersonalization(value: Boolean) = store.edit { it[Keys.PERSONALIZATION] = value }

    // AURA Together
    suspend fun setDisplayName(value: String) = store.edit { it[Keys.DISPLAY_NAME] = value.trim().take(40) }
    suspend fun setAcceptInvites(value: Boolean) = store.edit { it[Keys.ACCEPT_INVITES] = value }
    suspend fun setApproveJoins(value: Boolean) = store.edit { it[Keys.APPROVE_JOINS] = value }
    suspend fun setTogetherDemocratic(value: Boolean) = store.edit { it[Keys.TOGETHER_DEMOCRATIC] = value }
    suspend fun setTogetherMaxParticipants(value: Int) = store.edit { it[Keys.TOGETHER_MAX] = value.coerceIn(2, 16) }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingCompleted = this[Keys.ONBOARDING] ?: d.onboardingCompleted,
            resumeLastSession = this[Keys.RESUME] ?: d.resumeLastSession,
            skipSilence = this[Keys.SKIP_SILENCE] ?: d.skipSilence,
            normalizeVolume = this[Keys.NORMALIZE_VOLUME] ?: d.normalizeVolume,
            transitionSeconds = this[Keys.TRANSITION_SECONDS] ?: d.transitionSeconds,
            analyzeLibrary = this[Keys.ANALYZE_LIBRARY] ?: d.analyzeLibrary,
            pauseOnDisconnect = this[Keys.PAUSE_ON_DISCONNECT] ?: d.pauseOnDisconnect,
            resumeOnConnect = this[Keys.RESUME_ON_CONNECT] ?: d.resumeOnConnect,
            defaultRepeat = enumOrDefault(this[Keys.DEFAULT_REPEAT], d.defaultRepeat),
            keepShuffle = this[Keys.KEEP_SHUFFLE] ?: d.keepShuffle,
            previousBehavior = enumOrDefault(this[Keys.PREVIOUS], d.previousBehavior),
            outputPreference = enumOrDefault(this[Keys.OUTPUT], d.outputPreference),
            themeMode = enumOrDefault(this[Keys.THEME], d.themeMode),
            adaptiveAtmosphere = this[Keys.ATMOSPHERE] ?: d.adaptiveAtmosphere,
            atmosphereIntensity = this[Keys.ATMOSPHERE_INTENSITY] ?: d.atmosphereIntensity,
            gradientIntensity = this[Keys.GRADIENT_INTENSITY] ?: d.gradientIntensity,
            cornerStyle = enumOrDefault(this[Keys.CORNERS], d.cornerStyle),
            coverSize = enumOrDefault(this[Keys.COVER_SIZE], d.coverSize),
            listDensity = enumOrDefault(this[Keys.DENSITY], d.listDensity),
            textScale = this[Keys.TEXT_SCALE] ?: d.textScale,
            // The former boolean "reduce motion" maps onto the new three-level setting.
            motion = this[Keys.MOTION]?.let { enumOrDefault(it, d.motion) }
                ?: if (this[Keys.LEGACY_REDUCE_MOTION] == true) MotionLevel.Off else d.motion,
            miniPlayerStyle = enumOrDefault(this[Keys.MINI_STYLE], d.miniPlayerStyle),
            miniPlayerSwipe = this[Keys.MINI_SWIPE] ?: d.miniPlayerSwipe,
            nowPlayingButtons = this[Keys.NOW_PLAYING_BUTTONS]
                ?.mapNotNullTo(LinkedHashSet()) { name -> NowPlayingButton.entries.firstOrNull { it.name == name } }
                ?: d.nowPlayingButtons,
            homeSections = HomeSections.decode(this[Keys.HOME_SECTIONS]),
            minTrackDurationSec = this[Keys.MIN_DURATION] ?: d.minTrackDurationSec,
            excludedFolders = this[Keys.EXCLUDED_FOLDERS] ?: d.excludedFolders,
            hideWhatsApp = this[Keys.HIDE_WHATSAPP] ?: d.hideWhatsApp,
            hideTimestampTitles = this[Keys.HIDE_TIMESTAMP_TITLES] ?: d.hideTimestampTitles,
            showUntagged = this[Keys.SHOW_UNTAGGED] ?: d.showUntagged,
            lyricsFolders = this[Keys.LYRICS_FOLDERS] ?: d.lyricsFolders,
            onlineLyrics = this[Keys.ONLINE_LYRICS] ?: d.onlineLyrics,
            lyricsEffects = this[Keys.LYRICS_EFFECTS] ?: d.lyricsEffects,
            onlineCovers = this[Keys.ONLINE_COVERS] ?: d.onlineCovers,
            onlineConsentAsked = this[Keys.ONLINE_CONSENT] ?: d.onlineConsentAsked,
            trackSort = decode(this[Keys.TRACK_SORT], d.trackSort),
            albumSort = decode(this[Keys.ALBUM_SORT], d.albumSort),
            artistSort = decode(this[Keys.ARTIST_SORT], d.artistSort),
            albumLayout = enumOrDefault(this[Keys.ALBUM_LAYOUT], d.albumLayout),
            artistLayout = enumOrDefault(this[Keys.ARTIST_LAYOUT], d.artistLayout),
            searchHistoryEnabled = this[Keys.SEARCH_HISTORY] ?: d.searchHistoryEnabled,
            personalization = this[Keys.PERSONALIZATION] ?: d.personalization,
            displayName = this[Keys.DISPLAY_NAME] ?: d.displayName,
            acceptInvites = this[Keys.ACCEPT_INVITES] ?: d.acceptInvites,
            approveJoins = this[Keys.APPROVE_JOINS] ?: d.approveJoins,
            togetherDemocratic = this[Keys.TOGETHER_DEMOCRATIC] ?: d.togetherDemocratic,
            togetherMaxParticipants = this[Keys.TOGETHER_MAX] ?: d.togetherMaxParticipants,
        )
    }

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val RESUME = booleanPreferencesKey("resume_last_session")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val NORMALIZE_VOLUME = booleanPreferencesKey("normalize_volume")
        val TRANSITION_SECONDS = intPreferencesKey("transition_seconds")
        val ANALYZE_LIBRARY = booleanPreferencesKey("analyze_library")
        val PAUSE_ON_DISCONNECT = booleanPreferencesKey("pause_on_disconnect")
        val RESUME_ON_CONNECT = booleanPreferencesKey("resume_on_connect")
        val DEFAULT_REPEAT = stringPreferencesKey("default_repeat")
        val KEEP_SHUFFLE = booleanPreferencesKey("keep_shuffle")
        val PREVIOUS = stringPreferencesKey("previous_behavior")
        val OUTPUT = stringPreferencesKey("output_preference")
        val THEME = stringPreferencesKey("theme_mode")
        val ATMOSPHERE = booleanPreferencesKey("adaptive_atmosphere")
        val ATMOSPHERE_INTENSITY = floatPreferencesKey("atmosphere_intensity")
        val GRADIENT_INTENSITY = floatPreferencesKey("gradient_intensity")
        val CORNERS = stringPreferencesKey("corner_style")
        val COVER_SIZE = stringPreferencesKey("cover_size")
        val DENSITY = stringPreferencesKey("list_density")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val MOTION = stringPreferencesKey("motion_level")
        val LEGACY_REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val MINI_STYLE = stringPreferencesKey("mini_player_style")
        val MINI_SWIPE = booleanPreferencesKey("mini_player_swipe")
        val NOW_PLAYING_BUTTONS = stringSetPreferencesKey("now_playing_buttons")
        val HOME_SECTIONS = stringPreferencesKey("home_sections")
        val MIN_DURATION = intPreferencesKey("min_track_duration_sec")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
        val HIDE_WHATSAPP = booleanPreferencesKey("hide_whatsapp")
        val HIDE_TIMESTAMP_TITLES = booleanPreferencesKey("hide_timestamp_titles")
        val SHOW_UNTAGGED = booleanPreferencesKey("show_untagged")
        val LYRICS_FOLDERS = stringSetPreferencesKey("lyrics_folders")
        val ONLINE_LYRICS = booleanPreferencesKey("online_lyrics")
        val LYRICS_EFFECTS = booleanPreferencesKey("lyrics_effects")
        val ONLINE_COVERS = booleanPreferencesKey("online_covers")
        val ONLINE_CONSENT = booleanPreferencesKey("online_consent_asked")
        val TRACK_SORT = stringPreferencesKey("track_sort")
        val ALBUM_SORT = stringPreferencesKey("album_sort")
        val ARTIST_SORT = stringPreferencesKey("artist_sort")
        val ALBUM_LAYOUT = stringPreferencesKey("album_layout")
        val ARTIST_LAYOUT = stringPreferencesKey("artist_layout")
        val SEARCH_HISTORY = booleanPreferencesKey("search_history_enabled")
        val PERSONALIZATION = booleanPreferencesKey("personalization")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val ACCEPT_INVITES = booleanPreferencesKey("accept_invites")
        val APPROVE_JOINS = booleanPreferencesKey("approve_joins")
        val TOGETHER_DEMOCRATIC = booleanPreferencesKey("together_democratic")
        val TOGETHER_MAX = intPreferencesKey("together_max_participants")
    }
}

private fun <T : Enum<T>> SortSpec<T>.encode(): String = "${key.name}:${if (ascending) "asc" else "desc"}"

private inline fun <reified T : Enum<T>> decode(raw: String?, default: SortSpec<T>): SortSpec<T> {
    val (name, direction) = raw?.split(':')?.takeIf { it.size == 2 } ?: return default
    val key = enumValues<T>().firstOrNull { it.name == name } ?: return default
    return SortSpec(key, ascending = direction == "asc")
}

private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: default
