package com.aura.music.core.model

enum class ThemeMode { System, Dark, Light }

/** Full motion; reduced (shorter transitions, no decorative loops); or none at all. */
enum class MotionLevel { Full, Reduced, Off }

enum class CornerStyle(val scale: Float) { Sharp(0.45f), Soft(1f), Round(1.4f) }

enum class CoverSize(val albumCellDp: Int, val artistCellDp: Int) {
    Small(116, 92),
    Medium(152, 112),
    Large(196, 148),
}

enum class ListDensity { Comfortable, Compact }

enum class MiniPlayerStyle { Floating, Docked }

/** Optional buttons shown beneath the transport controls of Now Playing. */
enum class NowPlayingButton { Lyrics, Queue, Details, SleepTimer, Equalizer }

enum class HomeSection { Continue, Shortcuts, Mixes, RecentTracks, RecentAlbums, TopArtists, RecentlyAdded }

data class HomeSectionPref(val section: HomeSection, val visible: Boolean)

/** Repeat mode applied whenever a new list starts playing. */
enum class DefaultRepeat { Remember, Off, All, One }

enum class PreviousBehavior { RestartFirst, AlwaysPrevious }

/** Output the player should prefer when several are connected; Auto lets Android decide. */
enum class OutputPreference { Auto, Speaker, Wired, Bluetooth, Usb }

object TextScale {
    val options = listOf(0.9f, 1f, 1.15f, 1.3f)
}

object HomeSections {
    val Default: List<HomeSectionPref> = listOf(
        HomeSectionPref(HomeSection.Continue, true),
        HomeSectionPref(HomeSection.Shortcuts, true),
        HomeSectionPref(HomeSection.Mixes, true),
        HomeSectionPref(HomeSection.RecentTracks, true),
        HomeSectionPref(HomeSection.RecentAlbums, true),
        HomeSectionPref(HomeSection.TopArtists, true),
        HomeSectionPref(HomeSection.RecentlyAdded, false),
    )

    fun encode(sections: List<HomeSectionPref>): String =
        sections.joinToString(",") { "${it.section.name}:${if (it.visible) 1 else 0}" }

    /** Unknown entries are dropped and sections added in newer versions are appended. */
    fun decode(raw: String?): List<HomeSectionPref> {
        if (raw.isNullOrBlank()) return Default
        val parsed = raw.split(',').mapNotNull { entry ->
            val (name, flag) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            HomeSection.entries.firstOrNull { it.name == name }?.let { HomeSectionPref(it, flag == "1") }
        }.distinctBy { it.section }
        val missing = Default.filter { default -> parsed.none { it.section == default.section } }
        return parsed + missing
    }
}
