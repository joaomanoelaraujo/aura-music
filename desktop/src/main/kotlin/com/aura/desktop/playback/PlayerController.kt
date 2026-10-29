package com.aura.desktop.playback

import com.aura.desktop.audio.AudioEngine
import com.aura.music.core.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The player the UI talks to: owns the [PlayQueue] and drives the [AudioEngine]. All queue changes
 * happen on [dispatcher] (the UI thread in the app), so they never race each other; the engine's
 * callbacks from the audio thread are posted there too.
 */
class PlayerController(
    private val engine: AudioEngine,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    initialVolume: Float = 0.8f,
) {
    data class State(
        val queue: PlayQueue = PlayQueue(),
        val isPlaying: Boolean = false,
        val durationMs: Long = 0,
        /** App volume (0..1), separate from the system volume. */
        val volume: Float = 0.8f,
        /** A short message for the user, e.g. a file that could not be played. */
        val notice: String? = null,
    ) {
        val current: Track? get() = queue.current?.track
    }

    private val _state = MutableStateFlow(State(volume = initialVolume))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)

    /** Playback position, refreshed a few times per second while playing. */
    val position: StateFlow<Long> = _position.asStateFlow()

    /** Consecutive files that failed to open; stops skipping once the whole queue failed. */
    private var failures = 0

    init {
        engine.volume = perceptual(initialVolume)
        engine.listener = object : AudioEngine.Listener {
            override fun onEnded(source: String) {
                scope.launch(dispatcher) { if (source == _state.value.current?.uri) advance(auto = true) }
            }

            override fun onError(source: String, message: String) {
                scope.launch(dispatcher) { onFileFailed(source) }
            }
        }
        scope.launch(dispatcher) {
            engine.state.collect { s ->
                if (s.isPlaying) failures = 0
                _state.update { it.copy(isPlaying = s.isPlaying, durationMs = s.durationMs) }
            }
        }
        scope.launch(dispatcher) {
            while (isActive) {
                _position.value = engine.positionMs
                delay(if (_state.value.isPlaying) 200 else 500)
            }
        }
    }

    fun playTracks(tracks: List<Track>, start: Int) {
        if (tracks.isEmpty()) return
        if (redirected(JamAction.Play(tracks, start))) return
        failures = 0
        setQueue(_state.value.queue.play(tracks, start), play = true)
    }

    /** Puts a saved queue back, paused at [positionMs], without starting to play. */
    fun restore(tracks: List<Track>, index: Int, positionMs: Long, shuffle: Boolean, repeat: Repeat) {
        if (tracks.isEmpty()) return
        val queue = _state.value.queue.restored(tracks, index, shuffle, repeat)
        _state.update { it.copy(queue = queue) }
        val current = queue.current?.track ?: return
        engine.load(current.uri, startMs = positionMs, play = false)
        _position.value = positionMs
    }

    /**
     * While this computer is a guest in a jam, user actions go to the jam host instead of the local
     * player (the host decides; the state comes back). Returns true when the action was taken.
     */
    @Volatile var redirect: ((JamAction) -> Boolean)? = null

    sealed interface JamAction {
        data object TogglePlayPause : JamAction
        data object Next : JamAction
        data object Previous : JamAction
        data class Seek(val positionMs: Long) : JamAction
        data class Play(val tracks: List<Track>, val start: Int) : JamAction
        data class PlayNext(val tracks: List<Track>) : JamAction
        data class AddToQueue(val tracks: List<Track>) : JamAction
        /** Local-only changes (shuffle, repeat, queue edits) the guest can't make. */
        data object LocalOnly : JamAction
    }

    private fun redirected(action: JamAction) = redirect?.invoke(action) == true

    // ---- direct control used by the jam itself (never redirected)

    /** Exact engine position, for sync. */
    fun exactPositionMs(): Long = engine.positionMs

    /** Loads [track] alone at [positionMs], playing or paused (jam guest following the host). */
    fun jamLoad(track: Track, positionMs: Long, play: Boolean) {
        val queue = PlayQueue().restored(listOf(track), 0, shuffle = false, repeat = Repeat.Off)
        _state.update { it.copy(queue = queue) }
        engine.load(track.uri, startMs = positionMs, play = play)
        _position.value = positionMs
    }

    fun jamSetPlaying(playing: Boolean) {
        if (playing == _state.value.isPlaying) return
        if (playing) engine.play() else engine.pause()
    }

    fun jamSeek(positionMs: Long) {
        engine.seekTo(positionMs)
        _position.value = positionMs
    }

    fun jamStop() {
        engine.stop()
        _state.update { it.copy(queue = PlayQueue()) }
    }

    fun togglePlayPause() {
        if (redirected(JamAction.TogglePlayPause)) return
        val s = _state.value
        when {
            s.current == null -> Unit
            s.isPlaying -> engine.pause()
            engine.state.value.source == s.current?.uri -> engine.play()
            else -> load(s.current!!, play = true)
        }
    }

    fun next() {
        if (redirected(JamAction.Next)) return
        advance(auto = false)
    }

    /** Back to the start of the song, or to the previous one when near the start. */
    fun previous() {
        if (redirected(JamAction.Previous)) return
        val q = _state.value.queue
        if (q.current == null) return
        if (engine.positionMs > RESTART_THRESHOLD_MS || (q.index == 0 && q.repeat != Repeat.All)) {
            seekTo(0)
            return
        }
        setQueue(q.previous(), play = true)
    }

    fun seekTo(positionMs: Long) {
        if (redirected(JamAction.Seek(positionMs))) return
        if (_state.value.current == null) return
        engine.seekTo(positionMs)
        _position.value = positionMs.coerceAtLeast(0)
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        engine.volume = perceptual(v)
        _state.update { it.copy(volume = v) }
    }

    fun toggleShuffle() {
        if (redirected(JamAction.LocalOnly)) return
        updateQueue { it.withShuffle(!it.shuffle) }
    }

    fun setRepeat(mode: Repeat) = updateQueue { it.withRepeat(mode) }

    fun cycleRepeat() = if (redirected(JamAction.LocalOnly)) Unit else updateQueue {
        it.withRepeat(when (it.repeat) { Repeat.Off -> Repeat.All; Repeat.All -> Repeat.One; Repeat.One -> Repeat.Off })
    }

    fun addToQueue(tracks: List<Track>) {
        if (redirected(JamAction.AddToQueue(tracks))) return
        val wasEmpty = _state.value.queue.isEmpty
        updateQueue { it.addLast(tracks) }
        if (wasEmpty) _state.value.current?.let { load(it, play = false) }
        notice(if (tracks.size == 1) "Adicionada à fila" else "${tracks.size} músicas adicionadas à fila")
    }

    fun playNext(tracks: List<Track>) {
        if (redirected(JamAction.PlayNext(tracks))) return
        val wasEmpty = _state.value.queue.isEmpty
        updateQueue { it.playNext(tracks) }
        if (wasEmpty) _state.value.current?.let { load(it, play = false) }
        notice(if (tracks.size == 1) "Vai tocar a seguir" else "${tracks.size} músicas vão tocar a seguir")
    }

    fun jumpTo(position: Int) {
        val q = _state.value.queue
        if (position == q.index) return
        setQueue(q.jumpTo(position), play = true)
    }

    fun removeFromQueue(position: Int) {
        val q = _state.value.queue
        val removingCurrent = position == q.index
        val next = q.removeAt(position)
        if (!removingCurrent) {
            _state.update { it.copy(queue = next) }
            return
        }
        if (next.current == null) {
            engine.stop()
            _state.update { it.copy(queue = next) }
        } else {
            setQueue(next, play = _state.value.isPlaying)
        }
    }

    fun moveInQueue(from: Int, to: Int) = if (redirected(JamAction.LocalOnly)) Unit else updateQueue { it.move(from, to) }

    fun clearUpcoming() = if (redirected(JamAction.LocalOnly)) Unit else updateQueue { it.clearUpcoming() }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** Shows a short message to the user (the same place playback notices appear). */
    fun notify(message: String) = notice(message)

    private fun advance(auto: Boolean) {
        val q = _state.value.queue
        val next = q.next(auto)
        if (next == null) {
            // End of the queue: stay on the last song, stopped at its start.
            if (auto) q.current?.let { load(it.track, play = false) }
            return
        }
        setQueue(next, play = true, forceReload = auto && next.current == q.current)
    }

    private fun onFileFailed(source: String) {
        val s = _state.value
        val failed = s.current?.takeIf { it.uri == source } ?: return
        failures++
        notice("Não foi possível tocar “${failed.title}”")
        if (failures >= s.queue.items.size) {
            engine.stop()
            return
        }
        val next = s.queue.next(auto = false) ?: s.queue.withRepeat(Repeat.All).next(auto = false)?.withRepeat(s.queue.repeat)
        if (next != null && next.current != s.queue.current) setQueue(next, play = true)
    }

    private fun setQueue(queue: PlayQueue, play: Boolean, forceReload: Boolean = false) {
        val before = _state.value.queue.current
        _state.update { it.copy(queue = queue) }
        val now = queue.current ?: return
        if (forceReload || now.uid != before?.uid || engine.state.value.source != now.track.uri) {
            load(now.track, play)
        } else if (play) {
            engine.play()
        }
    }

    private fun updateQueue(transform: (PlayQueue) -> PlayQueue) = _state.update { it.copy(queue = transform(it.queue)) }

    private fun load(track: Track, play: Boolean) {
        engine.load(track.uri, startMs = 0, play = play)
        _position.value = 0
    }

    private fun notice(message: String) = _state.update { it.copy(notice = message) }

    companion object {
        const val RESTART_THRESHOLD_MS = 3_000L

        /** Slider position to gain: ears hear loudness roughly logarithmically. */
        fun perceptual(volume: Float): Float = volume * volume
    }
}
