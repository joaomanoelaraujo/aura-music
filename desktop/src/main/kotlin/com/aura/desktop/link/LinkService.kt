package com.aura.desktop.link

import com.aura.music.link.FrameStream
import com.aura.music.link.Handshake
import com.aura.music.link.HandshakeMessage
import com.aura.music.link.LinkCapabilities
import com.aura.music.link.LinkMessage
import com.aura.music.link.LinkPermissions
import com.aura.music.link.LinkProtocol
import com.aura.music.link.LinkRejectedException
import com.aura.music.link.LinkSession
import com.aura.music.link.LocalDevice
import com.aura.music.link.PairedDevice
import com.aura.music.link.Pairing
import com.aura.music.link.PairingOffer
import com.aura.music.link.Platform
import com.aura.music.link.RejectReason
import com.aura.music.link.RemoteAction
import com.aura.music.link.RemotePlayerState
import com.aura.music.link.RemoteTrack
import com.aura.music.link.crypto.LinkSecurityException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** The local player, as AURA Link exposes it to paired devices. */
interface LocalPlayerBridge {
    /** Current state, built fresh each call (position sampled now). */
    fun snapshot(deviceName: String, controlledBy: String?): RemotePlayerState

    /** Applies [action] on the UI thread; returns false when it doesn't apply (e.g. unknown id). */
    suspend fun apply(action: RemoteAction): Boolean

    suspend fun search(query: String, limit: Int): List<RemoteTrack>

    suspend fun artwork(trackId: String): ByteArray?
}

/** One paired device as the UI shows it. */
data class PeerStatus(
    val device: PairedDevice,
    val connected: Boolean = false,
    val paused: Boolean = false,
    val address: String? = null,
    /** What that device lets us do (from its capabilities message). */
    val granted: LinkPermissions? = null,
    val capabilities: List<String> = emptyList(),
    val remoteState: RemotePlayerState? = null,
    /** Local monotonic time (ms) when [remoteState] arrived, to extrapolate the position. */
    val stateReceivedAt: Long = 0,
)

data class PairingWindow(
    val qrPayload: String,
    val code: String,
    val expiresAt: Long,
    val attemptsLeft: Int,
    val addresses: List<String>,
    val port: Int,
)

data class LinkUiState(
    val deviceName: String = "",
    val deviceId: String = "",
    val fingerprint: String = "",
    val listening: Boolean = false,
    val port: Int = 0,
    val pairing: PairingWindow? = null,
    /** A device that passed the code check and waits for the user's yes/no. */
    val offer: PairingOffer? = null,
    val peers: List<PeerStatus> = emptyList(),
    /** Who last controlled this computer remotely (shown in the player). */
    val controlledBy: String? = null,
    val message: String? = null,
)

/**
 * AURA Link on the computer: accepts connections from paired devices (phones connect here), runs
 * the pairing window, enforces each device's permissions, keeps connections alive and announces
 * itself on the local network. Starts listening only once there is something to listen for.
 */
class LinkService(
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val identityStore: LocalIdentityStore,
    private val devices: PairedDeviceStore,
    private val bridge: LocalPlayerBridge,
    private val appVersion: String,
    private val preferredPort: Int = DEFAULT_PORT,
    private val advertise: Boolean = true,
    private val now: () -> Long = System::currentTimeMillis,
    /** File transfers; built by the caller, which knows where received files go. */
    val transfers: DesktopTransfers? = null,
) {
    private var info: LocalDeviceInfo
    private val local: LocalDevice

    init {
        val defaultName = "AURA no " + (System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "computador")
        val (loadedInfo, identity) = identityStore.load(defaultName.take(40))
        info = loadedInfo
        local = LocalDevice(info.deviceId, info.name, currentPlatform(), identity, listOfNotNull(LinkCapabilities.PLAYER, LinkCapabilities.REMOTE_CONTROL, LinkCapabilities.LIBRARY_SEARCH, LinkCapabilities.ARTWORK, LinkCapabilities.FILES.takeIf { transfers != null }))
    }

    private val _state = MutableStateFlow(
        LinkUiState(deviceName = info.name, deviceId = info.deviceId, fingerprint = com.aura.music.link.crypto.DeviceIdentity.fingerprint(local.identity.publicKey)),
    )
    val state: StateFlow<LinkUiState> = _state.asStateFlow()

    private val sessions = ConcurrentHashMap<String, LinkSession>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private var server: ServerSocket? = null
    private var mdns: JmDNS? = null
    private var qrSecret: ByteArray? = null
    private var code: String? = null
    private var pendingAnswer: CompletableDeferred<Boolean>? = null
    private val pairingBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val nextRequest = AtomicLong(1)
    private val pendingSearches = ConcurrentHashMap<Long, CompletableDeferred<List<RemoteTrack>>>()
    private val remoteArt = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()
    private var lastSentState: RemotePlayerState? = null
    private var lastSentAt = 0L

    init {
        transfers?.alwaysAccept = { id -> devices.find(id)?.let { setPermissions(id, it.permissions.copy(receiveFilesWithoutAsking = true)) } }
    }

    /** Sends [tracks] to a connected device (it asks its user, then fetches them). */
    fun sendFiles(deviceId: String, tracks: List<com.aura.music.core.model.Track>) {
        val session = sessions[deviceId] ?: return
        transfers?.send(session, tracks)
    }

    fun cancelTransfer(id: String) {
        val job = transfers?.jobs?.value?.firstOrNull { it.id == id } ?: return
        transfers.cancel(id, sessions[job.deviceId])
    }

    fun retryTransfer(id: String) {
        val job = transfers?.jobs?.value?.firstOrNull { it.id == id } ?: return
        transfers.retry(id, sessions[job.deviceId])
    }

    /** Starts listening if any device is paired (otherwise waits for [openPairing]). */
    fun start() {
        refreshPeers()
        if (devices.value.value.isNotEmpty()) ensureListening()
        scope.launch { heartbeat() }
        scope.launch { devices.value.collect { refreshPeers() } }
    }

    fun stop() {
        sessions.values.forEach { it.disconnect() }
        runCatching { mdns?.unregisterAllServices(); mdns?.close() }
        runCatching { server?.close() }
    }

    // ---------------------------------------------------------------- pairing window

    fun openPairing() {
        ensureListening()
        val secret = Pairing.newQrSecret()
        val newCode = Pairing.newCode()
        qrSecret = secret
        code = newCode
        val port = server?.localPort ?: return
        val addresses = localAddresses()
        val payload = com.aura.music.link.PairingPayload(info.name, addresses, port, secret).encode()
        _state.update {
            it.copy(pairing = PairingWindow(payload, newCode, now() + PAIRING_WINDOW_MS, MAX_ATTEMPTS, addresses, port), message = null)
        }
        readvertise()
        scope.launch {
            delay(PAIRING_WINDOW_MS)
            if (_state.value.pairing?.code == newCode) closePairing("O código expirou. Gere outro para vincular.")
        }
    }

    fun closePairing(message: String? = null) {
        qrSecret = null
        code = null
        pendingAnswer?.complete(false)
        _state.update { it.copy(pairing = null, offer = null, message = message) }
        readvertise()
    }

    /** The user's answer to [LinkUiState.offer]. */
    fun answerOffer(accept: Boolean) {
        pendingAnswer?.complete(accept)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---------------------------------------------------------------- managing devices

    fun renameThisDevice(name: String) {
        info = identityStore.rename(info, name)
        _state.update { it.copy(deviceName = info.name) }
        readvertise()
    }

    fun rename(deviceId: String, name: String) = devices.rename(deviceId, name)

    fun setPermissions(deviceId: String, permissions: LinkPermissions) {
        devices.setPermissions(deviceId, permissions)
        // Tell the device right away so its interface matches.
        sessions[deviceId]?.let { s -> runCatching { s.send(capabilities(permissions)) } }
    }

    /** Disconnects for now and refuses reconnections until [resume]. */
    fun pause(deviceId: String) {
        paused += deviceId
        sessions.remove(deviceId)?.disconnect()
        refreshPeers()
    }

    fun resume(deviceId: String) {
        paused -= deviceId
        refreshPeers()
    }

    /** Unpairs: the device is told (if connected), its key forgotten, and new connections refused. */
    fun revoke(deviceId: String) {
        sessions.remove(deviceId)?.disconnect(bye = true, revoked = true)
        paused -= deviceId
        devices.remove(deviceId)
        refreshPeers()
    }

    // ---------------------------------------------------------------- controlling another device

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

    // ---------------------------------------------------------------- network

    private fun ensureListening() {
        if (server != null) return
        val socket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) } }
            .getOrElse { ServerSocket(0) }
        server = socket
        _state.update { it.copy(listening = true, port = socket.localPort) }
        Thread({ acceptLoop(socket) }, "aura-link-accept").apply { isDaemon = true }.start()
        if (advertise) scope.launch(Dispatchers.IO) { startMdns(socket.localPort) }
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (!listener.isClosed) {
            val socket = runCatching { listener.accept() }.getOrNull() ?: continue
            Thread({ handle(socket) }, "aura-link-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val stream = FrameStream(socket.getInputStream(), socket.getOutputStream())
            if (Pairing.isPairingAttempt(stream)) pair(socket, stream) else reconnect(socket, stream)
        } catch (e: Exception) {
            runCatching { socket.close() }
        }
    }

    private fun pair(socket: Socket, stream: FrameStream) {
        if (!pairingBusy.compareAndSet(false, true)) {
            runCatching { stream.write(LinkProtocol.json.encodeToString(HandshakeMessage.serializer(), HandshakeMessage.Reject(RejectReason.Busy)).toByteArray()) }
            socket.close()
            return
        }
        try {
            socket.soTimeout = OFFER_TIMEOUT_MS + HANDSHAKE_TIMEOUT_MS
            val paired = Pairing.respond(
                stream,
                secretFor = { method ->
                    if (_state.value.pairing == null) null
                    else if (method == com.aura.music.link.PairMethod.Qr) qrSecret else code?.let(Pairing::codeSecret)
                },
                me = local.copy(name = info.name),
                onWrongCode = {
                    val left = (_state.value.pairing?.attemptsLeft ?: 1) - 1
                    if (left <= 0) closePairing("Muitas tentativas com o código errado. Gere um novo código.")
                    else _state.update { s -> s.copy(pairing = s.pairing?.copy(attemptsLeft = left)) }
                },
                decide = { offer -> awaitUserAnswer(offer) },
            )
            val address = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress
            devices.add(paired.copy(lastAddress = address, lastSeen = now()))
            devices.flush()
            closePairing("“${paired.name}” foi vinculado.")
        } catch (e: LinkRejectedException) {
            if (e.reason == RejectReason.Declined) _state.update { it.copy(offer = null, message = "Vínculo recusado.") }
        } catch (e: Exception) {
            _state.update { it.copy(offer = null) }
        } finally {
            pairingBusy.set(false)
            runCatching { socket.close() }
        }
    }

    private fun awaitUserAnswer(offer: PairingOffer): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingAnswer = answer
        _state.update { it.copy(offer = offer) }
        val accepted = kotlinx.coroutines.runBlocking { withTimeoutOrNull(OFFER_TIMEOUT_MS.toLong()) { answer.await() } } ?: false
        pendingAnswer = null
        _state.update { it.copy(offer = null) }
        return accepted
    }

    private fun reconnect(socket: Socket, stream: FrameStream) {
        // Peek at who is calling, to answer "paused" without touching keys.
        val first = stream.read()
        stream.pushBack(first)
        val hello = runCatching { LinkProtocol.json.decodeFromString(HandshakeMessage.serializer(), String(first, Charsets.UTF_8)) }.getOrNull() as? HandshakeMessage.Hello
        if (hello != null && hello.deviceId in paused) {
            stream.write(LinkProtocol.json.encodeToString(HandshakeMessage.serializer(), HandshakeMessage.Reject(RejectReason.Paused)).toByteArray())
            socket.close()
            return
        }
        val link = try {
            Handshake.accept(stream, local.copy(name = info.name), lookup = { id -> devices.find(id) })
        } catch (e: LinkRejectedException) {
            socket.close()
            return
        } catch (e: LinkSecurityException) {
            socket.close()
            return
        }
        // What is this connection for: commands (control) or the bytes of a file transfer (data)?
        when (val hello = runCatching { com.aura.music.link.Channels.read(link) }.getOrNull()) {
            is com.aura.music.link.ChannelHello.Data -> {
                socket.soTimeout = 60_000
                try {
                    transfers?.onData(link, hello)
                } finally {
                    runCatching { socket.close() }
                }
                return
            }
            com.aura.music.link.ChannelHello.Control -> Unit
            null -> {
                socket.close()
                return
            }
        }
        socket.soTimeout = 0
        val deviceId = link.peer.deviceId
        val session = LinkSession(link, onMessage = ::onMessage, onClosed = ::onClosed, closeTransport = socket::close)
        sessions.put(deviceId, session)?.disconnect(bye = false)
        val address = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress
        devices.seen(deviceId, address)
        session.start()
        runCatching {
            session.send(capabilities(devices.find(deviceId)?.permissions ?: LinkPermissions()))
            session.send(LinkMessage.State(bridge.snapshot(info.name, _state.value.controlledBy)))
        }
        refreshPeers(address = deviceId to address)
    }

    private fun capabilities(granted: LinkPermissions) = LinkMessage.Capabilities(info.name, appVersion, local.capabilities, granted)

    private fun onMessage(session: LinkSession, message: LinkMessage) {
        val deviceId = session.peer.deviceId
        when (message) {
            is LinkMessage.Capabilities -> updatePeer(deviceId) { it.copy(granted = message.granted, capabilities = message.capabilities) }
            is LinkMessage.State -> updatePeer(deviceId) { it.copy(remoteState = message.state, stateReceivedAt = monotonicMs()) }
            LinkMessage.StateRequest -> runCatching { session.send(LinkMessage.State(bridge.snapshot(info.name, _state.value.controlledBy))) }
            is LinkMessage.Command -> scope.launch(main) { runCommand(session, message) }
            is LinkMessage.Search -> scope.launch(Dispatchers.Default) {
                val allowed = devices.find(deviceId)?.permissions?.browseLibrary == true
                val reply = if (allowed) LinkMessage.SearchResults(message.requestId, bridge.search(message.query, message.limit.coerceIn(1, 100)))
                else LinkMessage.SearchResults(message.requestId, emptyList(), com.aura.music.link.CommandError.NotAllowed)
                runCatching { session.send(reply) }
            }
            is LinkMessage.ArtworkRequest -> scope.launch(Dispatchers.IO) {
                val allowed = devices.find(deviceId)?.permissions?.let { it.controlPlayback || it.browseLibrary } == true
                val bytes = if (allowed) bridge.artwork(message.trackId) else null
                runCatching { session.send(LinkMessage.Artwork(message.trackId, bytes?.let { Base64.getEncoder().encodeToString(it) })) }
            }
            is LinkMessage.SearchResults -> pendingSearches[message.requestId]?.complete(message.tracks)
            is LinkMessage.Artwork -> remoteArt["$deviceId/${message.trackId}"]?.complete(message.jpeg?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() })
            is LinkMessage.CommandResult -> if (!message.ok && message.error == com.aura.music.link.CommandError.NotAllowed) {
                _state.update { it.copy(message = "“${session.peer.name}” não permite esse controle. Ajuste as permissões no celular.") }
            }
            is LinkMessage.Bye -> if (message.revoked) {
                devices.remove(deviceId)
                _state.update { it.copy(message = "“${session.peer.name}” removeu o vínculo com este computador.") }
            }
            is LinkMessage.TransferOfferMessage, is LinkMessage.TransferAnswer, is LinkMessage.TransferCancel, is LinkMessage.TransferPull ->
                transfers?.onControl(session, message)
            is LinkMessage.Ping, is LinkMessage.Pong -> Unit
        }
    }

    private suspend fun runCommand(session: LinkSession, message: LinkMessage.Command) {
        val permissions = devices.find(session.peer.deviceId)?.permissions
        val allowed = permissions != null && permissions.controlPlayback && (!message.action.needsLibrary || permissions.browseLibrary)
        val ok = allowed && bridge.apply(message.action)
        if (allowed) _state.update { it.copy(controlledBy = session.peer.name) }
        withContext(Dispatchers.IO) {
            runCatching {
                session.send(
                    LinkMessage.CommandResult(
                        message.id, ok,
                        when {
                            !allowed -> com.aura.music.link.CommandError.NotAllowed
                            !ok -> com.aura.music.link.CommandError.NotFound
                            else -> null
                        },
                    ),
                )
            }
        }
        if (ok) publishState(force = true)
    }

    private fun onClosed(session: LinkSession, cause: Throwable?) {
        sessions.remove(session.peer.deviceId, session)
        if (_state.value.controlledBy == session.peer.name && sessions.isEmpty()) _state.update { it.copy(controlledBy = null) }
        refreshPeers()
    }

    /** Sends our player state to connected devices when it changes (or drifted from what they extrapolate). */
    fun publishState(force: Boolean = false) {
        if (sessions.isEmpty()) return
        val snapshot = bridge.snapshot(info.name, _state.value.controlledBy)
        val last = lastSentState
        val significant = force || last == null || last.copy(revision = 0, positionMs = 0, sampledAt = 0) != snapshot.copy(revision = 0, positionMs = 0, sampledAt = 0) ||
            kotlin.math.abs(expected(last, lastSentAt) - snapshot.positionMs) > 1_500
        if (!significant) return
        lastSentState = snapshot
        lastSentAt = monotonicMs()
        sessions.values.forEach { s -> scope.launch(Dispatchers.IO) { runCatching { s.send(LinkMessage.State(snapshot)) } } }
    }

    private fun expected(state: RemotePlayerState, sentAt: Long): Long =
        if (state.isPlaying) state.positionMs + (monotonicMs() - sentAt) else state.positionMs

    private suspend fun heartbeat() {
        while (scope.isActive) {
            delay(HEARTBEAT_MS)
            val limit = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(DEAD_AFTER_MS)
            sessions.values.forEach { s ->
                if (s.lastReceivedAt < limit) s.disconnect(bye = false)
                else runCatching { withContext(Dispatchers.IO) { s.send(LinkMessage.Ping(monotonicMs())) } }
            }
            publishState()
        }
    }

    private fun refreshPeers(address: Pair<String, String?>? = null) {
        val previous = _state.value.peers.associateBy { it.device.deviceId }
        val peers = devices.value.value.map { d ->
            val old = previous[d.deviceId]
            val connected = sessions[d.deviceId]?.isOpen == true
            PeerStatus(
                device = d,
                connected = connected,
                paused = d.deviceId in paused,
                address = if (address?.first == d.deviceId) address.second else old?.address ?: d.lastAddress,
                granted = if (connected) old?.granted else null,
                capabilities = if (connected) old?.capabilities.orEmpty() else emptyList(),
                remoteState = if (connected) old?.remoteState else null,
                stateReceivedAt = old?.stateReceivedAt ?: 0,
            )
        }
        _state.update { it.copy(peers = peers) }
    }

    private fun updatePeer(deviceId: String, transform: (PeerStatus) -> PeerStatus) =
        _state.update { s -> s.copy(peers = s.peers.map { if (it.device.deviceId == deviceId) transform(it) else it }) }

    // ---------------------------------------------------------------- local network discovery

    private fun startMdns(port: Int) {
        val address = localAddresses().firstOrNull()?.let(InetAddress::getByName) ?: return
        runCatching {
            mdns = JmDNS.create(address, "aura-" + info.deviceId.take(8))
            readvertise(port)
        }
    }

    private fun readvertise(port: Int = server?.localPort ?: 0) {
        val dns = mdns ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                dns.unregisterAllServices()
                val txt = mapOf(
                    "v" to LinkProtocol.VERSION.toString(),
                    "id" to shortId(info.deviceId),
                    "n" to info.name,
                    "pair" to if (_state.value.pairing != null) "1" else "0",
                    "os" to local.platform.name,
                )
                dns.registerService(ServiceInfo.create("${LinkProtocol.SERVICE_TYPE}.local.", "AURA ${info.deviceId.take(6)}", port, 0, 0, txt))
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = LinkProtocol.DEFAULT_PORT
        const val PAIRING_WINDOW_MS = 5 * 60_000L
        const val MAX_ATTEMPTS = 5
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val OFFER_TIMEOUT_MS = 120_000
        const val HEARTBEAT_MS = 10_000L
        const val DEAD_AFTER_MS = 35_000L

        fun monotonicMs(): Long = System.nanoTime() / 1_000_000

        fun shortId(deviceId: String): String = LinkProtocol.shortId(deviceId)

        fun currentPlatform(): Platform {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("win") -> Platform.Windows
                os.contains("mac") -> Platform.MacOS
                else -> Platform.Linux
            }
        }

        /** This computer's IPv4 addresses on local networks (Wi-Fi, Ethernet, hotspot), best first. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
                .filterNot { n -> listOf("vmware", "virtualbox", "vethernet", "hyper-v", "docker", "wsl", "tap", "tun").any { n.displayName.lowercase().contains(it) } }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress }
                .sortedBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.")) 1 else 2 }
        }.getOrDefault(emptyList())
    }
}
