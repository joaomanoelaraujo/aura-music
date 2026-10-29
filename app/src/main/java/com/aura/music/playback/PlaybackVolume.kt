package com.aura.music.playback

import androidx.media3.common.Player
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that sets the player's volume. Several features scale it at once (even volume,
 * smooth transitions, the sleep timer's fade); each owns a factor and the player gets their
 * product, so none undoes another. Call from the main thread.
 */
@Singleton
class PlaybackVolume @Inject constructor() {
    enum class Source { Normalization, Transition, SleepTimer }

    private val factors = EnumMap<Source, Float>(Source::class.java)
    private var player: Player? = null

    fun attach(player: Player) {
        this.player = player
        apply()
    }

    fun detach() {
        player?.volume = 1f
        player = null
        factors.clear()
    }

    fun set(source: Source, factor: Float) {
        factors[source] = factor.coerceIn(0f, 1f)
        apply()
    }

    private fun apply() {
        player?.volume = factors.values.fold(1f) { total, f -> total * f }
    }
}
