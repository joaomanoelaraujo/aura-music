package com.aura.music.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.aura.music.core.common.ApplicationScope
import com.aura.music.playback.eq.EqBands
import com.aura.music.playback.eq.EqPreset
import com.aura.music.playback.eq.EqualizerAudioProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class EqualizerBand(val index: Int, val centerHz: Int)

/** What the equalizer offers. Always available: it is AURA's own, not the device's. */
data class EqualizerInfo(
    val bands: List<EqualizerBand>,
    val minLevel: Int,
    val maxLevel: Int,
    val presets: List<EqPreset>,
)

data class EqualizerSettings(
    val enabled: Boolean = false,
    /** Index in [EqPreset], or -1 for custom [levels]. */
    val preset: Int = -1,
    /** Band levels in millibels (1/100 dB); empty means flat. */
    val levels: List<Int> = emptyList(),
)

private val Context.effectsStore: DataStore<Preferences> by preferencesDataStore(name = "audio_effects")

/**
 * AURA's 10-band equalizer. It processes the player's audio itself ([EqualizerAudioProcessor]),
 * so it works on any phone, even without the device's audio effects and without a player running
 * when it's being set up. Settings persist and apply instantly.
 */
@Singleton
@OptIn(UnstableApi::class)
class EqualizerController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    /** Goes into the player's audio sink when the playback service builds the player. */
    val processor = EqualizerAudioProcessor()

    private val _info = MutableStateFlow(
        EqualizerInfo(
            bands = EqBands.centersHz.mapIndexed { i, hz -> EqualizerBand(i, hz) },
            minLevel = (EqBands.MIN_DB * 100).toInt(),
            maxLevel = (EqBands.MAX_DB * 100).toInt(),
            presets = EqPreset.entries,
        ),
    )
    val info: StateFlow<EqualizerInfo?> = _info.asStateFlow()

    val settings: Flow<EqualizerSettings> = context.effectsStore.data.map { prefs ->
        EqualizerSettings(
            enabled = prefs[ENABLED] ?: false,
            preset = prefs[PRESET] ?: -1,
            levels = prefs[LEVELS]?.split(',')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == EqBands.count }.orEmpty(),
        )
    }.distinctUntilChanged()

    init {
        scope.launch {
            settings.collect { s ->
                val gains = FloatArray(EqBands.count) { (s.levels.getOrNull(it) ?: 0) / 100f }
                processor.update(gains, s.enabled)
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) = context.effectsStore.edit { it[ENABLED] = enabled }

    /** Applies a preset; its levels are stored so the sliders show them and can be tweaked. */
    suspend fun usePreset(index: Int) {
        val preset = EqPreset.entries.getOrNull(index) ?: return
        context.effectsStore.edit {
            it[PRESET] = index
            it[LEVELS] = preset.gainsDb.joinToString(",") { db -> (db * 100).toInt().toString() }
            it[ENABLED] = true
        }
    }

    suspend fun setBandLevel(band: Int, level: Int) {
        val current = settings.first().levels.takeIf { it.size == EqBands.count } ?: List(EqBands.count) { 0 }
        if (band !in current.indices) return
        val updated = current.toMutableList().also { it[band] = level.coerceIn(_info.value.minLevel, _info.value.maxLevel) }
        context.effectsStore.edit {
            it[PRESET] = -1
            it[LEVELS] = updated.joinToString(",")
        }
    }

    /** Equalizer settings, for a backup. */
    suspend fun exportAll(): Map<String, Any> = context.effectsStore.data.first().asMap().mapKeys { it.key.name }

    suspend fun importAll(values: Map<String, Any>) = context.effectsStore.edit { prefs -> com.aura.music.data.local.PreferenceValues.write(prefs, values) }

    suspend fun reset() = context.effectsStore.edit {
        it[PRESET] = EqPreset.Flat.ordinal
        it.remove(LEVELS)
    }

    private companion object {
        // New keys: settings of the old device equalizer had other bands and presets.
        val ENABLED = booleanPreferencesKey("aura_eq_enabled")
        val PRESET = intPreferencesKey("aura_eq_preset")
        val LEVELS = stringPreferencesKey("aura_eq_levels")
    }
}
