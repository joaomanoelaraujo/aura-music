package com.aura.music.data.media

import com.aura.music.core.model.AudioKind
import com.aura.music.core.model.Track

/** A user decision about one file or one folder. A null [kind] keeps the automatic classification. */
data class ClassificationOverride(val kind: AudioKind?, val hidden: Boolean)

data class FolderRule(val path: String, val kind: AudioKind?, val hidden: Boolean)

/** Song details the user corrected; null fields keep the file's own tags. */
data class MetadataOverride(val title: String?, val artist: String?)

/** Everything besides the scan itself that decides where each file appears. */
data class LibraryRules(
    val overrides: Map<Long, ClassificationOverride> = emptyMap(),
    val folderRules: List<FolderRule> = emptyList(),
    val excludedFolders: Set<String> = emptySet(),
    val hideWhatsApp: Boolean = false,
    /** Hide files titled only with a date, a time or hashtags ([TitlePatterns]). */
    val hideTimestampTitles: Boolean = false,
    val showUntagged: Boolean = true,
    val metadata: Map<Long, MetadataOverride> = emptyMap(),
)

/**
 * Turns a raw scan into a [LibraryIndex]. Pure and fast, so user corrections are applied
 * instantly without querying MediaStore again. Precedence: per-file override > folder rule >
 * automatic classification. Excluded folders are skipped entirely; nothing is ever deleted.
 */
object LibraryAssembler {

    fun assemble(scanned: List<ScannedAudio>, rules: LibraryRules): LibraryIndex {
        val music = ArrayList<Track>(scanned.size)
        val audios = ArrayList<Track>()
        val hidden = ArrayList<Track>()
        val folderRules = rules.folderRules.sortedByDescending { it.path.length }

        for (item in scanned) {
            val folder = item.track.folder
            if (rules.excludedFolders.any { folder.startsWith(it) }) continue

            val override = rules.overrides[item.track.id]
            val folderRule = folderRules.firstOrNull { folder.startsWith(it.path) }
            val userKind = override?.kind ?: folderRule?.kind
            val auto = AudioClassifier.classify(item.signals)
            val hasTags = AudioClassifier.hasTags(item.signals)
            val kind = when {
                userKind != null -> userKind
                auto == null -> continue
                auto == AudioKind.Music && !hasTags && !rules.showUntagged -> AudioKind.Other
                else -> auto
            }
            val fix = rules.metadata[item.track.id]
            val track = item.track.copy(
                kind = kind,
                hasTags = hasTags || fix != null,
                title = fix?.title ?: item.track.title,
                artist = fix?.artist ?: item.track.artist,
            )
            when {
                override?.hidden == true || (override == null && folderRule?.hidden == true) -> hidden += track
                kind.isWhatsApp && rules.hideWhatsApp && userKind == null -> Unit
                rules.hideTimestampTitles && userKind == null && TitlePatterns.isTimestampOrHashtags(item.track.title) -> Unit
                kind == AudioKind.Music -> music += track
                else -> audios += track
            }
        }
        return LibraryIndex(music, audios, hidden)
    }
}
