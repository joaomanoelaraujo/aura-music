package com.aura.music.playback

import androidx.media3.common.Player
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place that sets the player's volume. Several features scale it at once (even volume,
 * smooth transitions, the sleep timer's fade, the app volume a linked computer can set); each owns
 * a factor and the player gets their product, so none undoes another. Call from the main thread.
 *
 * This is AURA's own volume, never the phone's system volume.
 */
@Singleton
class PlaybackVolume @Inject constructor() {
    enum class Source { Normalization, Transition, SleepTimer, User }

    private val factors = EnumMap<Source, Float>(Source::class.java)
    private var player: Player? = null

    private val _userVolume = MutableStateFlow(1f)

    /** The app volume chosen by the user (or by a linked computer), 0..1. */
    val userVolume: StateFlow<Float> = _userVolume.asStateFlow()

    fun attach(player: Player) {
        this.player = player
        factors[Source.User] = _userVolume.value
        apply()
    }

    fun detach() {
        player?.volume = 1f
        player = null
        factors.clear()
    }

    fun set(source: Source, factor: Float) {
        val value = factor.coerceIn(0f, 1f)
        factors[source] = value
        if (source == Source.User) _userVolume.value = value
        apply()
    }

    private fun apply() {
        player?.volume = factors.values.fold(1f) { total, f -> total * f }
    }
}
