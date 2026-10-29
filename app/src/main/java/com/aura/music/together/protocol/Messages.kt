package com.aura.music.together.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

/**
 * Identifies a song across devices without sending files or paths: normalized tags plus duration
 * (and size as a tie-breaker). Each guest maps it to a file of its own library.
 */
@Serializable
data class TrackRef(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long,
    val sizeBytes: Long? = null,
)

enum class ControlMode { Democratic, HostControl }

enum class RepeatSetting { Off, All, One }

@Serializable
data class Participant(
    val id: String,
    val name: String,
    val isHost: Boolean = false,
    val canControl: Boolean = true,
)

/**
 * The authoritative session state, owned by the host. [positionMs] was the playback position at
 * host time [atHostMs]; guests extrapolate from it using their estimated clock offset.
 */
@Serializable
data class SessionState(
    val version: Long,
    val sessionName: String,
    val mode: ControlMode,
    val queue: List<TrackRef>,
    val index: Int,
    val isPlaying: Boolean,
    val positionMs: Long,
    val atHostMs: Long,
    val shuffle: Boolean,
    val repeat: RepeatSetting,
    val participants: List<Participant>,
    val maxParticipants: Int,
    /** Songs guests added that the host is still receiving; they join [queue] once complete. */
    val incoming: List<IncomingTrack> = emptyList(),
) {
    val current: TrackRef? get() = queue.getOrNull(index)

    /** Where playback should be at host time [hostNowMs]. */
    fun expectedPositionAt(hostNowMs: Long): Long {
        if (!isPlaying) return positionMs
        val duration = current?.durationMs ?: Long.MAX_VALUE
        return (positionMs + (hostNowMs - atHostMs)).coerceIn(0, duration)
    }
}

@Serializable
data class IncomingTrack(val track: TrackRef, val from: String, val progress: Float)

/** What a participant asks the host to do. The host validates, orders and applies it. */
@Serializable
sealed interface Command {
    @Serializable @SerialName("play") data object Play : Command
    @Serializable @SerialName("pause") data object Pause : Command
    @Serializable @SerialName("seek") data class Seek(val positionMs: Long) : Command
    @Serializable @SerialName("next") data object Next : Command
    @Serializable @SerialName("previous") data object Previous : Command
    @Serializable @SerialName("skipTo") data class SkipTo(val index: Int) : Command
    @Serializable @SerialName("add") data class AddToQueue(val tracks: List<TrackRef>) : Command

    /** Right after the current song, ahead of the rest of the queue. */
    @Serializable @SerialName("playNext") data class PlayNext(val tracks: List<TrackRef>) : Command

    /** Replaces what is playing: goes right after the current song and starts now. */
    @Serializable @SerialName("playNow") data class PlayNow(val track: TrackRef) : Command
    @Serializable @SerialName("move") data class MoveInQueue(val from: Int, val to: Int) : Command
    @Serializable @SerialName("remove") data class RemoveFromQueue(val index: Int) : Command
    @Serializable @SerialName("shuffle") data class SetShuffle(val enabled: Boolean) : Command
    @Serializable @SerialName("repeat") data class SetRepeat(val mode: RepeatSetting) : Command
}

/** The reactions a jam accepts: a fixed set, so nothing odd can be shown on everyone's screen. */
object Reactions {
    val allowed = listOf("❤️", "🔥", "😍", "👏", "🎉", "😂")

    /** Minimum time between two reactions of the same person. */
    const val MIN_INTERVAL_MS = 700L
}

enum class RejectReason { Declined, Full, BadVersion, Ended, Kicked, NotAllowed }

/** Everything that travels on the wire, in both directions. */
@Serializable
sealed interface Message {
    // Guest → host
    @Serializable @SerialName("hello") data class Hello(val protocol: Int, val name: String, val participantId: String?) : Message
    @Serializable @SerialName("cmd") data class Cmd(val requestId: Long, val command: Command) : Message
    @Serializable @SerialName("ping") data class Ping(val guestSentMs: Long) : Message
    @Serializable @SerialName("leave") data object Leave : Message

    /** A quick emoji reaction to what's playing; the host passes it on to everyone. */
    @Serializable @SerialName("react") data class React(val emoji: String) : Message

    // Host → guest
    @Serializable @SerialName("welcome") data class Welcome(val participantId: String, val state: SessionState) : Message
    @Serializable @SerialName("waiting") data object WaitingApproval : Message
    @Serializable @SerialName("state") data class State(val state: SessionState) : Message
    @Serializable @SerialName("pong") data class Pong(val guestSentMs: Long, val hostMs: Long) : Message
    @Serializable @SerialName("denied") data class Denied(val requestId: Long, val reason: RejectReason) : Message
    @Serializable @SerialName("rejected") data class Rejected(val reason: RejectReason) : Message

    /** Someone in the jam reacted. */
    @Serializable @SerialName("reaction") data class Reaction(val from: String, val emoji: String) : Message

    // Either direction: guests fetch the host's songs they lack, the host fetches songs guests add.

    /** Asks for bytes of a song this device doesn't have, to play a temporary copy. */
    @Serializable @SerialName("fetch") data class Fetch(val track: TrackRef, val offset: Long, val length: Int) : Message

    /** Asks for a song's cover, as a small JPEG. */
    @Serializable @SerialName("fetchArt") data class FetchArt(val track: TrackRef) : Message

    /** Part of a song answering a [Fetch]; [data] travels as raw bytes after the JSON header. */
    @Serializable @SerialName("chunk") data class Chunk(
        val track: TrackRef,
        val offset: Long,
        val totalBytes: Long,
        @Transient override val data: ByteArray = ByteArray(0),
    ) : Message, Binary {
        override fun withData(bytes: ByteArray) = copy(data = bytes)

        override fun equals(other: Any?) = other is Chunk && track == other.track && offset == other.offset &&
            totalBytes == other.totalBytes && data.contentEquals(other.data)

        override fun hashCode() = 31 * track.hashCode() + offset.hashCode()
    }

    /** A song's cover answering [FetchArt]; empty when it has none. */
    @Serializable @SerialName("art") data class Art(
        val track: TrackRef,
        @Transient override val data: ByteArray = ByteArray(0),
    ) : Message, Binary {
        override fun withData(bytes: ByteArray) = copy(data = bytes)

        override fun equals(other: Any?) = other is Art && track == other.track && data.contentEquals(other.data)

        override fun hashCode() = track.hashCode()
    }

    /** The other side can't send [track] (not offered, or the file can't be read). */
    @Serializable @SerialName("unavailable") data class Unavailable(val track: TrackRef) : Message
}

/** A message carrying raw bytes, which would triple in size as a JSON array. */
interface Binary {
    val data: ByteArray

    fun withData(bytes: ByteArray): Message
}

/**
 * Messages are JSON, except [Binary] ones (song chunks, covers), sent as a binary frame `[0x00][u32 header length][JSON header][raw bytes]`. JSON never starts with 0x00.
 */
object Wire {
    const val PROTOCOL = 5
    private const val BINARY: Byte = 0

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "t"
    }

    fun encode(message: Message): ByteArray {
        val header = json.encodeToString(Message.serializer(), message).toByteArray(Charsets.UTF_8)
        if (message !is Binary) return header
        return ByteBuffer.allocate(5 + header.size + message.data.size)
            .put(BINARY).putInt(header.size).put(header).put(message.data).array()
    }

    fun decode(bytes: ByteArray): Message {
        if (bytes.isEmpty() || bytes[0] != BINARY) return json.decodeFromString(Message.serializer(), String(bytes, Charsets.UTF_8))
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get()
        val headerSize = buffer.int
        require(headerSize in 1..bytes.size - 5) { "Bad binary frame" }
        val header = json.decodeFromString(Message.serializer(), String(bytes, 5, headerSize, Charsets.UTF_8))
        require(header is Binary) { "Unexpected binary message" }
        return header.withData(bytes.copyOfRange(5 + headerSize, bytes.size))
    }
}
