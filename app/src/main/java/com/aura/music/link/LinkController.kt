package com.aura.music.link

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.aura.music.core.common.ApplicationScope
import com.aura.music.core.model.Track
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.search.PersonalSignals
import com.aura.music.data.search.SearchCorpus
import com.aura.music.link.crypto.DeviceIdentity
import com.aura.music.playback.PlaybackVolume
import com.aura.music.playback.controller.PlayerConnection
import com.aura.music.playback.controller.RepeatMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A computer paired with this phone, as the screens show it. */
data class LinkedComputer(
    val device: PairedDevice,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val paused: Boolean = false,
    /** What the computer lets this phone do. */
    val granted: LinkPermissions? = null,
    val capabilities: List<String> = emptyList(),
    val remoteState: RemotePlayerState? = null,
    /** [SystemClock.elapsedRealtime] when [remoteState] arrived. */
    val receivedAt: Long = 0,
)

sealed interface PairingStage {
    data object Connecting : PairingStage
    data class Confirm(val offer: PairingOffer) : PairingStage
    data object WaitingForComputer : PairingStage
    data class Done(val name: String) : PairingStage
    data class Failed(val message: String) : PairingStage
}

data class LinkUiState(
    val deviceName: String = "",
    val fingerprint: String = "",
    val computers: List<LinkedComputer> = emptyList(),
    val pairing: PairingStage? = null,
    /** Who controlled this phone last (shown so control never changes hands silently). */
    val controlledBy: String? = null,
    val message: String? = null,
)

/**
 * AURA Link on the phone. Pairs with computers (QR code or typed code), keeps an encrypted
 * connection to each paired computer while the app runs, lets them control this phone within the
 * permissions the user chose here, and lets this phone control them.
 *
 * The phone always dials the computer (Android doesn't keep a server running in the background);
 * the one connection carries both directions.
 */
@Singleton
class LinkController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: LinkStore,
    private val player: PlayerConnection,
    private val library: LibraryRepository,
    private val imageLoader: ImageLoader,
    private val volume: PlaybackVolume,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val capabilities = listOf(LinkCapabilities.PLAYER, LinkCapabilities.REMOTE_CONTROL, LinkCapabilities.LIBRARY_SEARCH, LinkCapabilities.ARTWORK, LinkCapabilities.FILES)
    val discovery = LinkDiscovery(context)

    private val _state = MutableStateFlow(LinkUiState())
    val state: StateFlow<LinkUiState> = _state.asStateFlow()

    private val sessions = ConcurrentHashMap<String, LinkSession>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val started = AtomicBoolean(false)
    private val nextRequest = AtomicLong(1)
    private val pendingSearches = ConcurrentHashMap<Long, CompletableDeferred<List<RemoteTrack>>>()
    private val remoteArt = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()
    private var pendingAnswer: CompletableDeferred<Boolean>? = null
    private var corpus: Pair<Any, SearchCorpus>? = null
    @Volatile private var revision = 0L
    private var lastSent: RemotePlayerState? = null
    private var lastSentAt = 0L

    /** File transfers with linked computers (copies into the receiver's library). */
    val transfers = PhoneTransfers(
        context, scope, store,
        sessionFor = { id -> sessions[id] },
        dial = { device -> runCatching { dial(device) }.getOrNull() },
        onReceived = { library.refresh() },
    )

    /** Sends [tracks] to a connected computer (it asks its user, then the phone sends them). */
    fun sendFiles(deviceId: String, tracks: List<Track>) = transfers.send(deviceId, tracks)

    /** Starts reconnecting to paired computers and publishing this phone's player. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(deviceName = store.deviceName, fingerprint = DeviceIdentity.fingerprint(store.identity.publicKey)) }
            refresh()
            reconnectLoop()
        }
        // The player (MediaController) may only be touched from the main thread.
        scope.launch(Dispatchers.Main) {
            combine(player.state, player.positions(1_000), volume.userVolume) { _, _, _ -> }.collect { publishState() }
        }
    }

    // ---------------------------------------------------------------- pairing

    /** A scanned QR code; returns false when it isn't an AURA computer's code. */
    fun pairWithQr(text: String): Boolean {
        val payload = PairingPayload.parse(text) ?: return false
        pair(payload.hosts, payload.port, PairMethod.Qr, payload.secret)
        return true
    }

    fun pairWithCode(host: String, port: Int, code: String) = pair(listOf(host), port, PairMethod.Code, Pairing.codeSecret(code))

    fun answerOffer(accept: Boolean) {
        pendingAnswer?.complete(accept)
    }

    fun clearPairing() = _state.update { it.copy(pairing = null) }

    private fun pair(hosts: List<String>, port: Int, method: PairMethod, secret: ByteArray) {
        _state.update { it.copy(pairing = PairingStage.Connecting) }
        scope.launch(Dispatchers.IO) {
            val socket = hosts.firstNotNullOfOrNull { host -> runCatching { Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) } to host }.getOrNull() }
            if (socket == null) {
                fail("Não foi possível falar com o computador. Confira se o celular e o computador estão na mesma rede Wi-Fi (ou no hotspot) e se o Windows permitiu o AURA na rede.")
                return@launch
            }
            val (s, host) = socket
            try {
                s.soTimeout = (OFFER_TIMEOUT_MS + 20_000).toInt()
                val paired = Pairing.initiate(FrameStream(s.getInputStream(), s.getOutputStream()), method, secret, store.local(capabilities)) { offer ->
                    val answer = CompletableDeferred<Boolean>()
                    pendingAnswer = answer
                    _state.update { it.copy(pairing = PairingStage.Confirm(offer)) }
                    val accepted = runBlocking { withTimeoutOrNull(OFFER_TIMEOUT_MS) { answer.await() } } ?: false
                    pendingAnswer = null
                    if (accepted) _state.update { it.copy(pairing = PairingStage.WaitingForComputer) }
                    accepted
                }
                store.upsert(paired.copy(lastAddress = "$host:$port", lastSeen = System.currentTimeMillis()))
                _state.update { it.copy(pairing = PairingStage.Done(paired.name)) }
                refresh()
                connect(store.devices().first { it.deviceId == paired.deviceId })
            } catch (e: LinkRejectedException) {
                fail(
                    when (e.reason) {
                        RejectReason.WrongCode -> "Código incorreto. Confira o número na tela do computador e tente de novo."
                        RejectReason.Expired, RejectReason.TooManyAttempts -> "O código expirou ou foi cancelado no computador. Gere outro em Dispositivos, no computador."
                        RejectReason.Busy -> "O computador está vinculando outro aparelho. Tente de novo em instantes."
                        RejectReason.Declined -> "Vínculo recusado."
                        RejectReason.BadVersion -> "As versões do AURA não são compatíveis. Atualize os dois."
                        else -> "Não foi possível vincular."
                    },
                )
            } catch (e: Exception) {
                fail("A conexão caiu durante o vínculo. Tente de novo.")
            } finally {
                runCatching { s.close() }
            }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(pairing = PairingStage.Failed(message)) }

    // ---------------------------------------------------------------- managing computers

    fun renameThisPhone(name: String) {
        scope.launch(Dispatchers.IO) {
            store.rename(name)
            _state.update { it.copy(deviceName = store.deviceName) }
        }
    }

    fun rename(deviceId: String, name: String) = edit(deviceId) { it.copy(name = name.trim().take(40).ifEmpty { it.name }) }

    fun setPermissions(deviceId: String, permissions: LinkPermissions) {
        edit(deviceId) { it.copy(permissions = permissions) }
        sessions[deviceId]?.let { s -> scope.launch(Dispatchers.IO) { runCatching { s.send(capabilitiesMessage(permissions)) } } }
    }

    /** Disconnects and stops reconnecting until [resume]. */
    fun pause(deviceId: String) {
        scope.launch(Dispatchers.IO) {
            store.setPaused(deviceId, true)
            sessions.remove(deviceId)?.disconnect()
            refresh()
        }
    }

    fun resume(deviceId: String) {
        scope.launch(Dispatchers.IO) {
            store.setPaused(deviceId, false)
            refresh()
            store.devices().firstOrNull { it.deviceId == deviceId }?.let { connect(it) }
        }
    }

    /** Unpairs: tells the computer (if connected) and forgets its key. */
    fun revoke(deviceId: String) {
        scope.launch(Dispatchers.IO) {
            sessions.remove(deviceId)?.disconnect(bye = true, revoked = true)
            store.remove(deviceId)
            store.setPaused(deviceId, false)
            refresh()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun edit(deviceId: String, transform: (PairedDevice) -> PairedDevice) {
        scope.launch(Dispatchers.IO) {
            store.update(deviceId, transform)
            refresh()
        }
    }

    // ---------------------------------------------------------------- controlling a computer

    fun command(deviceId: String, action: RemoteAction) {
        val session = sessions[deviceId] ?: return
        scope.launch(Dispatchers.IO) { runCatching { session.command(action) } }
    }

    fun requestState(deviceId: String) {
        val session = sessions[deviceId] ?: return
        scope.launch(Dispatchers.IO) { runCatching { session.send(LinkMessage.StateRequest) } }
    }

    suspend fun search(deviceId: String, query: String): List<RemoteTrack>? {
        val session = sessions[deviceId] ?: return null
        val id = nextRequest.getAndIncrement()
        val answer = CompletableDeferred<List<RemoteTrack>>()
        pendingSearches[id] = answer
        return try {
            withContext(Dispatchers.IO) { session.send(LinkMessage.Search(id, query, 60)) }
            withTimeoutOrNull(8_000) { answer.await() }
        } catch (e: Exception) {
            null
        } finally {
            pendingSearches.remove(id)
        }
    }

    suspend fun remoteArtwork(deviceId: String, trackId: String): ByteArray? {
        val session = sessions[deviceId] ?: return null
        val key = "$deviceId/$trackId"
        val answer = remoteArt.getOrPut(key) {
            CompletableDeferred<ByteArray?>().also { scope.launch(Dispatchers.IO) { runCatching { session.send(LinkMessage.ArtworkRequest(trackId)) } } }
        }
        return withTimeoutOrNull(8_000) { answer.await() }.also { if (it == null) remoteArt.remove(key) }
    }

    // ---------------------------------------------------------------- connections

    private val visibleScreens = java.util.concurrent.atomic.AtomicInteger(0)

    /** Screens that list computers call this, so network discovery runs only while it's useful. */
    fun screenVisible(visible: Boolean) {
        if (visible) {
            visibleScreens.incrementAndGet()
            discovery.start()
        } else {
            visibleScreens.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }

    private suspend fun reconnectLoop() {
        while (scope.isActive) {
            val waiting = store.devices().filter { !sessions.containsKey(it.deviceId) && !store.isPaused(it.deviceId) }
            // Look for computers on the network only while something needs it (saves battery).
            if (waiting.isNotEmpty() || visibleScreens.get() > 0) discovery.start() else discovery.stop()
            waiting.forEach { connect(it) }
            heartbeat()
            delay(RETRY_MS)
        }
    }

    /**
     * Opens an authenticated connection to [device] (any of its known addresses), not yet told what
     * it's for. Throws [LinkRejectedException] when the computer refuses us.
     */
    private fun dial(device: PairedDevice): Pair<AuthenticatedLink, Socket>? {
        val shortId = LinkProtocol.shortId(device.deviceId)
        val candidates = buildList {
            discovery.found.value.values.filter { it.shortId == shortId }.forEach { add(it.host to it.port) }
            device.lastAddress?.let { a -> a.substringBeforeLast(':').takeIf { it.isNotBlank() }?.let { add(it to (a.substringAfterLast(':').toIntOrNull() ?: DEFAULT_PORT)) } }
        }.distinct()
        for ((host, port) in candidates) {
            val socket = runCatching { Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) } }.getOrNull() ?: continue
            try {
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                socket.tcpNoDelay = true
                val link = Handshake.connect(FrameStream(socket.getInputStream(), socket.getOutputStream()), store.local(capabilities), device)
                store.update(device.deviceId) { it.copy(lastAddress = "$host:$port", lastSeen = System.currentTimeMillis()) }
                return link to socket
            } catch (e: LinkRejectedException) {
                runCatching { socket.close() }
                throw e
            } catch (e: IOException) {
                runCatching { socket.close() }
            }
        }
        return null
    }

    private fun connect(device: PairedDevice) {
        if (sessions.containsKey(device.deviceId) || !connecting.add(device.deviceId)) return
        refresh()
        scope.launch(Dispatchers.IO) {
            try {
                val (link, socket) = dial(device) ?: return@launch
                try {
                    Channels.announce(link, ChannelHello.Control)
                    socket.soTimeout = 0
                    val session = LinkSession(link, onMessage = ::onMessage, onClosed = ::onClosed, closeTransport = socket::close)
                    sessions[device.deviceId] = session
                    session.start()
                    session.send(capabilitiesMessage(device.permissions))
                    session.send(LinkMessage.State(snapshot()))
                } catch (e: IOException) {
                    runCatching { socket.close() }
                }
            } catch (e: LinkRejectedException) {
                if (e.reason == RejectReason.UnknownDevice) {
                    // The computer no longer knows this phone: the link was removed there.
                    store.remove(device.deviceId)
                    _state.update { it.copy(message = "“${device.name}” removeu o vínculo com este celular.") }
                }
            } finally {
                connecting.remove(device.deviceId)
                refresh()
            }
        }
    }

    private fun capabilitiesMessage(granted: LinkPermissions) = LinkMessage.Capabilities(store.deviceName, "android", capabilities, granted)

    private fun onMessage(session: LinkSession, message: LinkMessage) {
        val deviceId = session.peer.deviceId
        when (message) {
            is LinkMessage.Capabilities -> updateComputer(deviceId) { it.copy(granted = message.granted, capabilities = message.capabilities) }
            is LinkMessage.State -> updateComputer(deviceId) { it.copy(remoteState = message.state, receivedAt = SystemClock.elapsedRealtime()) }
            LinkMessage.StateRequest -> scope.launch(Dispatchers.Main) {
                val snap = snapshot()
                withContext(Dispatchers.IO) { runCatching { session.send(LinkMessage.State(snap)) } }
            }
            is LinkMessage.Command -> scope.launch(Dispatchers.Main) { runCommand(session, message) }
            is LinkMessage.Search -> scope.launch(Dispatchers.Default) {
                val allowed = store.devices().firstOrNull { it.deviceId == deviceId }?.permissions?.browseLibrary == true
                val reply = if (allowed) LinkMessage.SearchResults(message.requestId, searchLibrary(message.query, message.limit.coerceIn(1, 100)))
                else LinkMessage.SearchResults(message.requestId, emptyList(), CommandError.NotAllowed)
                withContext(Dispatchers.IO) { runCatching { session.send(reply) } }
            }
            is LinkMessage.ArtworkRequest -> scope.launch(Dispatchers.IO) {
                val permissions = store.devices().firstOrNull { it.deviceId == deviceId }?.permissions
                val track = message.trackId.toLongOrNull()?.let { id -> library.awaitIndex()?.track(id) }
                val bytes = if (permissions != null && (permissions.controlPlayback || permissions.browseLibrary) && track != null) coverJpeg(track) else null
                runCatching { session.send(LinkMessage.Artwork(message.trackId, bytes?.let { Base64.getEncoder().encodeToString(it) })) }
            }
            is LinkMessage.SearchResults -> pendingSearches[message.requestId]?.complete(message.tracks)
            is LinkMessage.Artwork -> remoteArt["$deviceId/${message.trackId}"]?.complete(message.jpeg?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() })
            is LinkMessage.CommandResult -> if (!message.ok && message.error == CommandError.NotAllowed) {
                _state.update { it.copy(message = "“${session.peer.name}” não permite esse controle. Ajuste as permissões no computador.") }
            }
            is LinkMessage.Bye -> if (message.revoked) {
                scope.launch(Dispatchers.IO) {
                    store.remove(deviceId)
                    _state.update { it.copy(message = "“${session.peer.name}” removeu o vínculo com este celular.") }
                    refresh()
                }
            }
            is LinkMessage.TransferOfferMessage, is LinkMessage.TransferAnswer, is LinkMessage.TransferCancel, is LinkMessage.TransferPull ->
                transfers.onControl(session, message)
            is LinkMessage.Ping, is LinkMessage.Pong -> Unit
        }
    }

    private suspend fun runCommand(session: LinkSession, message: LinkMessage.Command) {
        val permissions = withContext(Dispatchers.IO) { store.devices().firstOrNull { it.deviceId == session.peer.deviceId }?.permissions }
        val allowed = permissions != null && permissions.controlPlayback && (!message.action.needsLibrary || permissions.browseLibrary)
        val ok = allowed && apply(message.action)
        if (allowed) _state.update { it.copy(controlledBy = session.peer.name) }
        val result = LinkMessage.CommandResult(
            message.id, ok,
            when {
                !allowed -> CommandError.NotAllowed
                !ok -> CommandError.NotFound
                else -> null
            },
        )
        withContext(Dispatchers.IO) { runCatching { session.send(result) } }
        if (ok) {
            delay(150) // let the player apply it before reporting
            publishState(force = true)
        }
    }

    /** Applies a command from a linked computer to this phone's player (main thread). */
    private suspend fun apply(action: RemoteAction): Boolean {
        val s = player.state.value
        fun indexOf(uid: Long) = s.queue.indexOfFirst { uidOf(it.key) == uid }
        when (action) {
            RemoteAction.Play -> if (!s.isPlaying) player.togglePlayPause()
            RemoteAction.Pause -> if (s.isPlaying) player.togglePlayPause()
            RemoteAction.Next -> player.skipNext()
            RemoteAction.Previous -> player.skipPrevious()
            is RemoteAction.Seek -> player.seekTo(action.positionMs.coerceAtLeast(0))
            is RemoteAction.SetVolume -> volume.set(PlaybackVolume.Source.User, action.volume)
            is RemoteAction.SetShuffle -> if (s.shuffle != action.enabled) player.toggleShuffle()
            is RemoteAction.SetRepeat -> {
                val target = when (action.mode) { RemoteRepeat.Off -> RepeatMode.Off; RemoteRepeat.All -> RepeatMode.All; RemoteRepeat.One -> RepeatMode.One }
                repeat(3) { if (player.state.value.repeatMode != target) { player.cycleRepeat(); delay(80) } }
            }
            is RemoteAction.JumpTo -> player.skipToQueueItem(indexOf(action.uid).takeIf { it >= 0 } ?: return false)
            is RemoteAction.Remove -> player.removeQueueItem(indexOf(action.uid).takeIf { it >= 0 } ?: return false)
            is RemoteAction.Move -> player.moveQueueItem(indexOf(action.uid).takeIf { it >= 0 } ?: return false, action.toIndex.coerceIn(0, (s.queue.size - 1).coerceAtLeast(0)))
            RemoteAction.ClearUpcoming -> for (i in s.queue.lastIndex downTo s.currentIndex + 1) player.removeQueueItem(i)
            is RemoteAction.PlayTracks -> {
                val tracks = resolve(action.ids).takeIf { it.isNotEmpty() } ?: return false
                player.play(tracks, action.start.coerceIn(0, tracks.lastIndex))
            }
            is RemoteAction.PlayNext -> player.playNext(resolve(action.ids).takeIf { it.isNotEmpty() } ?: return false)
            is RemoteAction.AddToQueue -> player.addToQueue(resolve(action.ids).takeIf { it.isNotEmpty() } ?: return false)
        }
        return true
    }

    private suspend fun resolve(ids: List<String>): List<Track> {
        val index = library.awaitIndex() ?: return emptyList()
        return ids.mapNotNull { it.toLongOrNull()?.let(index::track) }
    }

    private suspend fun searchLibrary(query: String, limit: Int): List<RemoteTrack> {
        val index = library.awaitIndex() ?: return emptyList()
        val c = corpus?.takeIf { it.first === index }?.second ?: SearchCorpus(index, emptyList()).also { corpus = index to it }
        val tracks = if (query.isBlank()) index.tracks.take(limit) else c.search(query, PersonalSignals.None).tracks.take(limit)
        return tracks.map(::remote)
    }

    private fun onClosed(session: LinkSession, cause: Throwable?) {
        sessions.remove(session.peer.deviceId, session)
        refresh()
    }

    private fun heartbeat() {
        val limit = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(DEAD_AFTER_MS)
        sessions.values.forEach { s ->
            if (s.lastReceivedAt < limit) s.disconnect(bye = false) else runCatching { s.send(LinkMessage.Ping(SystemClock.elapsedRealtime())) }
        }
    }

    // ---------------------------------------------------------------- this phone's player

    private fun snapshot(): RemotePlayerState {
        val s = player.state.value
        val position = lastPosition
        return RemotePlayerState(
            revision = ++revision,
            deviceName = store.deviceName,
            isPlaying = s.isPlaying,
            positionMs = position.first,
            sampledAt = SystemClock.elapsedRealtime(),
            durationMs = position.second.takeIf { it > 0 } ?: s.current?.durationMs ?: 0,
            current = s.current?.let(::remote),
            queue = s.queue.withIndex().filter { (i, _) -> i >= s.currentIndex - 20 && i <= s.currentIndex + 200 }.map { (_, q) -> RemoteQueueItem(uidOf(q.key), remote(q.track)) },
            index = s.queue.withIndex().filter { (i, _) -> i >= s.currentIndex - 20 && i <= s.currentIndex + 200 }.indexOfFirst { it.index == s.currentIndex },
            shuffle = s.shuffle,
            repeat = when (s.repeatMode) { RepeatMode.Off -> RemoteRepeat.Off; RepeatMode.All -> RemoteRepeat.All; RepeatMode.One -> RemoteRepeat.One },
            volume = volume.userVolume.value,
            controlledBy = _state.value.controlledBy,
        )
    }

    /** Latest (position, duration), kept by [publishState]'s collector. */
    @Volatile private var lastPosition: Pair<Long, Long> = 0L to 0L

    private fun publishState(force: Boolean = false) {
        if (sessions.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            lastPosition = player.withController { currentPosition to duration.coerceAtLeast(0) } ?: lastPosition
            val snap = snapshot()
            val last = lastSent
            val drift = last != null && last.isPlaying && abs(last.positionMs + (SystemClock.elapsedRealtime() - lastSentAt) - snap.positionMs) > 1_500
            val changed = last == null || last.copy(revision = 0, positionMs = 0, sampledAt = 0) != snap.copy(revision = 0, positionMs = 0, sampledAt = 0)
            if (!force && !changed && !drift && !(last != null && !last.isPlaying && last.positionMs != snap.positionMs)) return@launch
            lastSent = snap
            lastSentAt = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) { sessions.values.forEach { s -> runCatching { s.send(LinkMessage.State(snap)) } } }
        }
    }

    private suspend fun coverJpeg(track: Track): ByteArray? {
        val request = ImageRequest.Builder(context).data(track.artwork).size(Size(COVER_EDGE, COVER_EDGE)).allowHardware(false).build()
        val bitmap: Bitmap = (imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        val scale = COVER_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val sized = if (scale < 1f) bitmap.scale((bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1)) else bitmap
        return ByteArrayOutputStream().use { out -> sized.compress(Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray() }
    }

    private fun remote(track: Track) = RemoteTrack(track.id.toString(), track.title, track.artist, track.album, track.durationMs)

    private fun uidOf(key: String): Long = key.hashCode().toLong() and 0xFFFFFFFFL

    private fun refresh() {
        scope.launch(Dispatchers.IO) {
            val previous = _state.value.computers.associateBy { it.device.deviceId }
            val computers = store.devices().map { d ->
                val old = previous[d.deviceId]
                val connected = sessions[d.deviceId]?.isOpen == true
                LinkedComputer(
                    device = d,
                    connected = connected,
                    connecting = !connected && d.deviceId in connecting,
                    paused = store.isPaused(d.deviceId),
                    granted = if (connected) old?.granted else null,
                    capabilities = if (connected) old?.capabilities.orEmpty() else emptyList(),
                    remoteState = if (connected) old?.remoteState else null,
                    receivedAt = old?.receivedAt ?: 0,
                )
            }
            _state.update { it.copy(computers = computers, deviceName = store.deviceName) }
        }
    }

    private fun updateComputer(deviceId: String, transform: (LinkedComputer) -> LinkedComputer) =
        _state.update { s -> s.copy(computers = s.computers.map { if (it.device.deviceId == deviceId) transform(it) else it }) }

    companion object {
        const val DEFAULT_PORT = LinkProtocol.DEFAULT_PORT
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val OFFER_TIMEOUT_MS = 120_000L
        private const val RETRY_MS = 12_000L
        private const val DEAD_AFTER_MS = 35_000L
        private const val COVER_EDGE = 320

    }
}
