package com.aura.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.aura.music.playback.eq.KaraokeAudioProcessor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Karaoke mode (voice removed): a momentary mode, off again the next time the app starts. */
@Singleton
@OptIn(UnstableApi::class)
class KaraokeController @Inject constructor() {
    /** Goes into the player's audio sink, before the equalizer. */
    val processor = KaraokeAudioProcessor()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun toggle() = set(!_enabled.value)

    fun set(on: Boolean) {
        _enabled.value = on
        processor.enabled = on
    }
}
