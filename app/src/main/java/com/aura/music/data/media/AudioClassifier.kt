package com.aura.music.data.media

import com.aura.music.core.model.AudioKind
import java.util.Locale

/** Everything the classifier may look at for one file. Nothing here ever leaves the device. */
data class AudioSignals(
    val relativePath: String,
    val displayName: String,
    val mimeType: String?,
    val durationMs: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int,
    val bitrate: Int?,
    val isMusic: Boolean,
    val isPodcast: Boolean = false,
    val isRecording: Boolean = false,
    val isAudiobook: Boolean = false,
    /** Ringtone, alarm or notification sound according to MediaStore. */
    val isSystemSound: Boolean = false,
)

/**
 * Local, rule-based classifier that separates music from voice notes, recordings and other audio.
 *
 * It combines independent signals (folder, file-name patterns, MIME type, MediaStore flags,
 * tag richness and, only as a weak hint, duration). No single signal decides on its own except
 * the unambiguous ones (WhatsApp's voice-note folder and `PTT-` names), so a song shared through
 * WhatsApp can still be recognised as music while a long voice note never is.
 */
object AudioClassifier {

    /** Returns null for system sounds (ringtones, alarms, notifications), which are not shown at all. */
    fun classify(s: AudioSignals): AudioKind? {
        val path = s.relativePath.lowercase(Locale.ROOT)
        val name = s.displayName.lowercase(Locale.ROOT)
        val musicScore = musicScore(s)
        val strongTags = s.artist != null && (hasRealAlbum(s) || s.trackNumber > 0 || s.year != null)

        if (s.isSystemSound && !s.isMusic) return null

        val whatsApp = "whatsapp" in path || WA_NAME.containsMatchIn(name)
        if (whatsApp) {
            if (VOICE_NOTES_DIR in path || PTT_NAME.containsMatchIn(name)) return AudioKind.WhatsAppVoice
            // Audio files forwarded through WhatsApp: songs keep their tags, voice-like files don't.
            if (strongTags && musicScore >= MUSIC_THRESHOLD && !isVoiceCodec(s)) return AudioKind.Music
            if (isOpus(s) && s.artist == null && s.album == null) return AudioKind.WhatsAppVoice
            return AudioKind.WhatsAppAudio
        }

        if (VOICE_DIRS.any { it in path } || PTT_NAME.containsMatchIn(name)) return AudioKind.VoiceMessage

        if (s.isPodcast || s.isAudiobook || "podcast" in path || s.genre?.lowercase(Locale.ROOT)?.contains("podcast") == true) {
            return AudioKind.Podcast
        }

        val recordingHint = s.isRecording ||
            pathSegments(path).any { it in RECORDING_DIRS } ||
            RECORDING_NAME.containsMatchIn(name)
        if (recordingHint || isVoiceCodec(s)) {
            return if (strongTags && musicScore >= MUSIC_THRESHOLD + 1 && !isVoiceCodec(s)) AudioKind.Music else AudioKind.Recording
        }

        if (MESSENGER_DIRS.any { it in path }) {
            return if (strongTags && musicScore >= MUSIC_THRESHOLD) AudioKind.Music else AudioKind.Other
        }

        return when {
            s.isMusic -> AudioKind.Music
            musicScore >= MUSIC_THRESHOLD + 1 && s.artist != null -> AudioKind.Music
            else -> AudioKind.Other
        }
    }

    /** Whether the file carries real artist/album tags (MediaStore falls back to the folder name for albums). */
    fun hasTags(s: AudioSignals): Boolean = s.artist != null || hasRealAlbum(s)

    /** Counts music-like traits. Duration contributes at most one point and never decides alone. */
    internal fun musicScore(s: AudioSignals): Int {
        var score = 0
        if (s.artist != null) score += 2
        if (hasRealAlbum(s)) score += 1
        if (s.albumArtist != null) score += 1
        if (s.year != null) score += 1
        if (s.trackNumber > 0) score += 1
        if (s.genre != null) score += 1
        if ((s.bitrate ?: 0) >= 112_000) score += 1
        if (s.mimeType in MUSIC_MIMES) score += 1
        if (s.durationMs in 90_000..900_000) score += 1
        if (!s.title.isNullOrBlank() && !sameAsFileName(s.title, s.displayName)) score += 1
        return score
    }

    private fun hasRealAlbum(s: AudioSignals): Boolean {
        val album = s.album ?: return false
        val folder = s.relativePath.trimEnd('/').substringAfterLast('/')
        return !album.equals(folder, ignoreCase = true)
    }

    private fun sameAsFileName(title: String, displayName: String): Boolean =
        title.trim().equals(displayName.substringBeforeLast('.').trim(), ignoreCase = true)

    private fun isOpus(s: AudioSignals): Boolean =
        s.mimeType == "audio/ogg" || s.mimeType == "audio/opus" || s.displayName.endsWith(".opus", ignoreCase = true)

    /** Speech-only codecs used by voice recorders and phone calls. */
    private fun isVoiceCodec(s: AudioSignals): Boolean =
        s.mimeType in VOICE_MIMES || s.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT) in VOICE_EXTENSIONS

    private fun pathSegments(path: String): List<String> = path.split('/').filter(String::isNotBlank)

    private const val MUSIC_THRESHOLD = 5
    private const val VOICE_NOTES_DIR = "voice notes"

    private val PTT_NAME = Regex("""^ptt-\d{8}-wa\d+""")
    private val WA_NAME = Regex("""^(aud|ptt)-\d{8}-wa\d+""")
    private val RECORDING_NAME = Regex(
        """^(recording|rec|call|voice|voz|grava[cç][aã]o|audio[_ ]?record|nova grava[cç][aã]o|memo)[ _-]?\d""",
    )
    private val VOICE_DIRS = listOf("telegram voice", "/voice notes", "signal/voice")
    private val MESSENGER_DIRS = listOf("telegram", "signal", "messenger")
    private val RECORDING_DIRS = setOf(
        "recordings", "recording", "recorder", "soundrecorder", "sound_recorder", "sound recorder", "voice recorder",
        "callrecord", "call", "call recordings", "gravações", "gravacoes", "gravador", "voicerecorder",
    )
    private val MUSIC_MIMES = setOf("audio/mpeg", "audio/mp4", "audio/flac", "audio/x-flac", "audio/mp4a-latm", "audio/x-wav", "audio/wav", "audio/aac")
    private val VOICE_MIMES = setOf("audio/amr", "audio/amr-wb", "audio/3gpp", "audio/3gpp2")
    private val VOICE_EXTENSIONS = setOf("amr", "awb", "3gp", "3ga")
}
