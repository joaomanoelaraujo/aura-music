package com.aura.music.link

import com.aura.music.link.crypto.LinkSecurityException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Capability names exchanged after connecting, so newer features degrade gracefully. */
object LinkCapabilities {
    const val PLAYER = "player.v1"
    const val REMOTE_CONTROL = "remote.control.v1"
    const val LIBRARY_SEARCH = "library.search.v1"
    const val ARTWORK = "artwork.v1"
    const val FILES = "files.v1"
}

/** A song as another device sees it: an opaque id valid only on the device that owns the file. */
@Serializable
data class RemoteTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
)

@Serializable
data class RemoteQueueItem(val uid: Long, val track: RemoteTrack)

@Serializable
enum class RemoteRepeat { Off, All, One }

/**
 * What a device is playing, pushed by that device (it is authoritative). [positionMs] was the
 * position at the sender's monotonic time [sampledAt]; the receiver extrapolates while playing.
 */
@Serializable
data class RemotePlayerState(
    val revision: Long,
    val deviceName: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val sampledAt: Long,
    val durationMs: Long,
    val current: RemoteTrack? = null,
    val queue: List<RemoteQueueItem> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RemoteRepeat = RemoteRepeat.Off,
    /** The player app's own volume (0..1), never the system volume. */
    val volume: Float = 1f,
    /** Name of the device currently controlling this one, if any (shown on both sides). */
    val controlledBy: String? = null,
)

@Serializable
sealed interface RemoteAction {
    @Serializable @SerialName("play") data object Play : RemoteAction
    @Serializable @SerialName("pause") data object Pause : RemoteAction
    @Serializable @SerialName("next") data object Next : RemoteAction
    @Serializable @SerialName("previous") data object Previous : RemoteAction
    @Serializable @SerialName("seek") data class Seek(val positionMs: Long) : RemoteAction
    @Serializable @SerialName("volume") data class SetVolume(val volume: Float) : RemoteAction
    @Serializable @SerialName("shuffle") data class SetShuffle(val enabled: Boolean) : RemoteAction
    @Serializable @SerialName("repeat") data class SetRepeat(val mode: RemoteRepeat) : RemoteAction
    @Serializable @SerialName("jump") data class JumpTo(val uid: Long) : RemoteAction
    @Serializable @SerialName("remove") data class Remove(val uid: Long) : RemoteAction
    @Serializable @SerialName("move") data class Move(val uid: Long, val toIndex: Int) : RemoteAction
    @Serializable @SerialName("clearUpcoming") data object ClearUpcoming : RemoteAction

    /** Songs from the controlled device's own library (ids from a [LinkMessage.SearchResults]). */
    @Serializable @SerialName("playTracks") data class PlayTracks(val ids: List<String>, val start: Int) : RemoteAction
    @Serializable @SerialName("playNext") data class PlayNext(val ids: List<String>) : RemoteAction
    @Serializable @SerialName("addToQueue") data class AddToQueue(val ids: List<String>) : RemoteAction

    /** Which permission an action needs on the receiving device. */
    val needsLibrary: Boolean get() = this is PlayTracks || this is PlayNext || this is AddToQueue
}

enum class CommandError { NotAllowed, NotFound, Unsupported, Failed }

@Serializable
sealed interface LinkMessage {
    /** First message after the handshake, both ways. */
    @Serializable @SerialName("caps") data class Capabilities(
        val deviceName: String,
        val appVersion: String,
        val capabilities: List<String>,
        /** What the sender allows the receiver to do (so its UI can hide what won't work). */
        val granted: LinkPermissions,
    ) : LinkMessage

    @Serializable @SerialName("state") data class State(val state: RemotePlayerState) : LinkMessage

    /** Asks the receiver to send its state (after connecting, or when a view opens). */
    @Serializable @SerialName("stateRequest") data object StateRequest : LinkMessage

    /** [id] increases per sender; the receiver ignores ids it already handled. */
    @Serializable @SerialName("cmd") data class Command(val id: Long, val action: RemoteAction) : LinkMessage
    @Serializable @SerialName("cmdResult") data class CommandResult(val id: Long, val ok: Boolean, val error: CommandError? = null) : LinkMessage

    @Serializable @SerialName("search") data class Search(val requestId: Long, val query: String, val limit: Int = 50) : LinkMessage
    @Serializable @SerialName("searchResults") data class SearchResults(val requestId: Long, val tracks: List<RemoteTrack>, val error: CommandError? = null) : LinkMessage

    /** Cover of a track the receiver owns, as a small JPEG (base64; covers are kept small). */
    @Serializable @SerialName("artRequest") data class ArtworkRequest(val trackId: String) : LinkMessage
    @Serializable @SerialName("art") data class Artwork(val trackId: String, val jpeg: String?) : LinkMessage

    @Serializable @SerialName("ping") data class Ping(val sentAt: Long) : LinkMessage
    @Serializable @SerialName("pong") data class Pong(val sentAt: Long) : LinkMessage

    /** Proposes sending files; the receiver answers with [TransferAnswer] after asking its user. */
    @Serializable @SerialName("transferOffer") data class TransferOfferMessage(val offer: TransferOffer) : LinkMessage

    @Serializable @SerialName("transferAnswer") data class TransferAnswer(val id: String, val accepted: Boolean, val refusal: TransferRefusal? = null) : LinkMessage

    /** Either side gives up on a transfer (before or during the copy). */
    @Serializable @SerialName("transferCancel") data class TransferCancel(val id: String) : LinkMessage

    /**
     * The receiver asks the sender to (re)open the data connection; sent by a computer receiving
     * from a phone, since phones dial. [id] is the transfer.
     */
    @Serializable @SerialName("transferPull") data class TransferPull(val id: String) : LinkMessage

    /** The sender is closing on purpose ([revoked]: it unpaired us; forget it too). */
    @Serializable @SerialName("bye") data class Bye(val revoked: Boolean = false) : LinkMessage
}

/**
 * A live, encrypted connection to one paired device. Reads on a background thread and hands each
 * message to [onMessage]; [send] can be called from any thread. Commands carrying an id already
 * handled are dropped, so a resend after a hiccup is never applied twice.
 */
class LinkSession(
    private val link: AuthenticatedLink,
    private val onMessage: (LinkSession, LinkMessage) -> Unit,
    private val onClosed: (LinkSession, Throwable?) -> Unit,
    private val closeTransport: () -> Unit,
) : Closeable {
    val peer: PairedDevice get() = link.peer

    private val closed = AtomicBoolean(false)
    private val nextCommandId = AtomicLong(1)
    private val handled = object : LinkedHashMap<Long, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 512
    }

    @Volatile var lastReceivedAt: Long = System.nanoTime()
        private set

    @Volatile var peerCapabilities: LinkMessage.Capabilities? = null
        private set

    private val reader = Thread({ readLoop() }, "aura-link-${link.peer.name.take(12)}").apply { isDaemon = true }

    fun start() = reader.start()

    val isOpen: Boolean get() = !closed.get()

    fun send(message: LinkMessage) {
        if (closed.get()) throw IOException("Link closed")
        val bytes = LinkProtocol.json.encodeToString(LinkMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        try {
            link.stream.write(link.cipher.seal(bytes))
        } catch (e: IOException) {
            close(e)
            throw e
        }
    }

    /** Sends [action] with a fresh id; returns the id to match the [LinkMessage.CommandResult]. */
    fun command(action: RemoteAction): Long {
        val id = nextCommandId.getAndIncrement()
        send(LinkMessage.Command(id, action))
        return id
    }

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (!closed.get()) {
                val plain = link.cipher.open(link.stream.read())
                lastReceivedAt = System.nanoTime()
                val message = try {
                    LinkProtocol.json.decodeFromString(LinkMessage.serializer(), String(plain, Charsets.UTF_8))
                } catch (e: Exception) {
                    continue // an unknown message from a newer version: ignore it
                }
                when (message) {
                    is LinkMessage.Ping -> runCatching { send(LinkMessage.Pong(message.sentAt)) }
                    is LinkMessage.Pong -> Unit
                    is LinkMessage.Capabilities -> {
                        peerCapabilities = message
                        onMessage(this, message)
                    }
                    is LinkMessage.Command -> {
                        val fresh = synchronized(handled) { handled.put(message.id, true) == null }
                        if (fresh) onMessage(this, message)
                    }
                    is LinkMessage.Bye -> {
                        onMessage(this, message)
                        break
                    }
                    else -> onMessage(this, message)
                }
            }
        } catch (e: LinkSecurityException) {
            failure = e
        } catch (e: IOException) {
            if (!closed.get()) failure = e
        } catch (e: Exception) {
            failure = e
        }
        close(failure)
    }

    /** Ends the link; [bye] tells the peer first ([revoked] when unpairing). */
    fun disconnect(bye: Boolean = true, revoked: Boolean = false) {
        if (bye && !closed.get()) runCatching { send(LinkMessage.Bye(revoked)) }
        close(null)
    }

    private fun close(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        runCatching(closeTransport)
        onClosed(this, cause)
    }

    override fun close() = disconnect()
}
