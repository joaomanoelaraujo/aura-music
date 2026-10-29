package com.aura.desktop.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays one file at a time. A single thread owns the decoder and feeds the sink; callers only post
 * commands. Pause and seek also act on the sink right away from the calling thread, so they feel
 * instant even while the playback thread is blocked writing.
 *
 * The engine knows nothing about queues: when a file finishes it reports it and the player decides.
 */
class AudioEngine(
    private val sink: AudioSink = JavaSoundSink(),
    private val openDecoder: (String) -> FfmpegDecoder = { FfmpegDecoder(it, sink.sampleRate) },
) : AutoCloseable {

    data class State(
        val source: String? = null,
        val isPlaying: Boolean = false,
        val durationMs: Long = 0,
    )

    interface Listener {
        fun onEnded(source: String)

        fun onError(source: String, message: String)
    }

    private sealed interface Command {
        data class Load(val source: String, val startMs: Long, val play: Boolean) : Command
        data object Play : Command
        data object Pause : Command
        data class Seek(val positionMs: Long) : Command
        data object Stop : Command
        data object Quit : Command
    }

    private val commands = LinkedBlockingQueue<Command>()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile var listener: Listener? = null

    /** Linear gain applied to the samples: the app's own volume, never the system's. */
    @Volatile var volume: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f) }

    // Position bookkeeping, written by the playback thread and read by anyone.
    @Volatile private var baseMs = 0L
    @Volatile private var writtenFrames = 0L

    /** Set by callers on seek/load until the playback thread catches up, so the UI never jumps. */
    @Volatile private var pendingPositionMs = -1L

    private val thread = Thread(::run, "aura-audio").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
        start()
    }

    val positionMs: Long
        get() {
            pendingPositionMs.takeIf { it >= 0 }?.let { return it }
            val queuedFrames = sink.queuedBytes() / FfmpegDecoder.FRAME_BYTES
            val heard = (writtenFrames - queuedFrames).coerceAtLeast(0)
            return baseMs + heard * 1000 / sink.sampleRate
        }

    fun load(source: String, startMs: Long = 0, play: Boolean = true) {
        pendingPositionMs = startMs.coerceAtLeast(0)
        sink.flush()
        commands.put(Command.Load(source, startMs.coerceAtLeast(0), play))
    }

    fun play() {
        // While paused, the playback thread may sit blocked in write() on the stopped sink, so the
        // sink has to be restarted from here; the thread then picks up the command.
        sink.start()
        commands.put(Command.Play)
    }

    fun pause() {
        sink.stop()
        _state.update { it.copy(isPlaying = false) }
        commands.put(Command.Pause)
    }

    fun seekTo(positionMs: Long) {
        pendingPositionMs = positionMs.coerceAtLeast(0)
        sink.flush()
        commands.put(Command.Seek(positionMs.coerceAtLeast(0)))
    }

    fun stop() {
        sink.stop()
        sink.flush()
        commands.put(Command.Stop)
    }

    override fun close() {
        commands.put(Command.Quit)
        sink.stop()
        sink.flush()
        thread.join(2_000)
    }

    // ---------------------------------------------------------------- playback thread

    private var decoder: FfmpegDecoder? = null
    private var source: String? = null
    private var playing = false
    private var finished = false

    private fun run() {
        try {
            sink.open()
        } catch (e: Exception) {
            // No audio device: keep accepting commands and report each load as a failure.
            loopWithoutOutput(e.message ?: "Nenhuma saída de áudio disponível")
            return
        }
        while (true) {
            val idle = decoder == null || !playing || finished
            val command = if (idle) commands.take() else commands.poll()
            if (command != null) {
                if (command == Command.Quit) break
                handle(command)
                continue
            }
            pump()
        }
        decoder?.close()
        sink.close()
    }

    private fun loopWithoutOutput(reason: String) {
        while (true) {
            when (val command = commands.take()) {
                Command.Quit -> return
                is Command.Load -> listener?.onError(command.source, reason)
                else -> Unit
            }
        }
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Load -> load(command)
            Command.Play -> if (decoder != null) {
                if (finished) restartAt(0)
                playing = true
                sink.start()
                publish()
            }
            Command.Pause -> {
                playing = false
                sink.stop()
                publish()
            }
            is Command.Seek -> decoder?.let { d ->
                d.seek(command.positionMs)
                finished = false
                resetPosition(command.positionMs)
                if (playing) sink.start()
            } ?: run { pendingPositionMs = -1 }
            Command.Stop -> {
                decoder?.close()
                decoder = null
                source = null
                playing = false
                finished = false
                resetPosition(0)
                publish()
            }
            Command.Quit -> Unit
        }
    }

    private fun load(command: Command.Load) {
        decoder?.close()
        decoder = null
        source = command.source
        finished = false
        sink.stop()
        val opened = try {
            openDecoder(command.source)
        } catch (e: Exception) {
            playing = false
            resetPosition(0)
            _state.value = State(source = command.source, isPlaying = false, durationMs = 0)
            listener?.onError(command.source, e.message ?: "Não foi possível tocar este arquivo")
            return
        }
        decoder = opened
        if (command.startMs > 0) opened.seek(command.startMs)
        resetPosition(command.startMs)
        playing = command.play
        if (playing) sink.start()
        publish()
    }

    private fun restartAt(positionMs: Long) {
        decoder?.seek(positionMs)
        finished = false
        resetPosition(positionMs)
    }

    private fun resetPosition(positionMs: Long) {
        sink.flush()
        baseMs = positionMs
        writtenFrames = 0
        pendingPositionMs = -1
    }

    /** Decodes one block and writes it, giving up early if a command arrives. */
    private fun pump() {
        val d = decoder ?: return
        val pcm = try {
            d.read()
        } catch (e: Exception) {
            null.also { listener?.onError(source.orEmpty(), e.message ?: "Erro ao ler o arquivo") }
        }
        if (pcm == null) {
            awaitDrain()
            return
        }
        if (pcm.isEmpty()) return
        applyVolume(pcm)
        var offset = 0
        while (offset < pcm.size) {
            if (commands.isNotEmpty()) return
            val length = minOf(WRITE_SLICE, pcm.size - offset)
            val written = sink.write(pcm, offset, length)
            if (written <= 0) return
            offset += written
            writtenFrames += written / FfmpegDecoder.FRAME_BYTES
        }
    }

    /** Lets the last samples play out, then reports the end (unless a command interrupts). */
    private fun awaitDrain() {
        while (sink.queuedBytes() > 0) {
            if (commands.isNotEmpty()) return
            TimeUnit.MILLISECONDS.sleep(10)
        }
        finished = true
        playing = false
        sink.stop()
        publish()
        source?.let { listener?.onEnded(it) }
    }

    private fun applyVolume(pcm: ByteArray) {
        val gain = volume
        if (gain >= 0.999f) return
        var i = 0
        while (i < pcm.size - 1) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val scaled = (sample * gain).toInt()
            pcm[i] = scaled.toByte()
            pcm[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
    }

    private fun publish() {
        _state.value = State(source = source, isPlaying = playing, durationMs = decoder?.durationMs ?: 0)
    }

    private companion object {
        const val WRITE_SLICE = 8 * 1024
    }
}
