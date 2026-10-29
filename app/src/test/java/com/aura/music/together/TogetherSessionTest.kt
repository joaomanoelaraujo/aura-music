package com.aura.music.together

import com.aura.music.together.guest.GuestPlayer
import com.aura.music.together.guest.GuestSession
import com.aura.music.together.guest.GuestStatus
import com.aura.music.together.host.HostConfig
import com.aura.music.together.host.HostPlayback
import com.aura.music.together.host.HostPlayer
import com.aura.music.together.host.HostSession
import com.aura.music.together.host.Placement
import com.aura.music.together.host.RelayConfig
import com.aura.music.together.transfer.StreamCache
import com.aura.music.together.transfer.TrackFile
import com.aura.music.together.protocol.Command
import com.aura.music.together.protocol.ControlMode
import com.aura.music.together.protocol.Invite
import com.aura.music.together.protocol.RejectReason
import com.aura.music.together.protocol.RepeatSetting
import com.aura.music.together.protocol.SecureChannel
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.protocol.Message
import com.aura.music.together.protocol.Wire
import com.aura.music.together.transport.RelayEndpoint
import com.aura.music.together.transport.SecureLink
import com.aura.music.together.transport.TcpConnection
import java.io.File
import java.net.ServerSocket
import kotlin.random.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host and guests talk over real loopback TCP sockets with AES-GCM, using simulated players. */
class TogetherSessionTest {

    private fun now() = System.nanoTime() / 1_000_000

    private val songs = listOf(
        TrackRef("Saudade", "Henrique Mendonça", "Ao Vivo", 200_000),
        TrackRef("Sinal Fechado", "Paulinho da Viola", "Foi um Rio", 180_000),
        TrackRef("Águas de Março", "Elis Regina", "Elis & Tom", 210_000),
    )

    /** A host player whose position advances in real time. */
    private inner class FakeHost(private val library: Set<String> = songs.map { it.title }.toSet()) : HostPlayer {
        var queue = songs.toMutableList()
        val received = HashMap<TrackRef, File>()
        var index = 0
        var playing = false
        private var base = 0L
        private var since = now()
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        val served = AtomicInteger()

        fun position() = if (playing) base + (now() - since) else base

        override suspend fun read() = HostPlayback(queue.toList(), index, playing, position(), false, RepeatSetting.Off)

        override suspend fun apply(command: Command) {
            val pos = position()
            when (command) {
                Command.Play -> playing = true
                Command.Pause -> playing = false
                is Command.Seek -> base = command.positionMs.also { since = now() }.let { it }
                Command.Next -> { index = (index + 1).coerceAtMost(queue.lastIndex); base = 0 }
                Command.Previous -> { index = (index - 1).coerceAtLeast(0); base = 0 }
                is Command.SkipTo -> { index = command.index; base = 0 }
                is Command.AddToQueue -> queue.addAll(command.tracks)
                is Command.PlayNext -> queue.addAll(index + 1, command.tracks)
                is Command.PlayNow -> place(command.track, Placement.Now)
                is Command.MoveInQueue -> queue.add(command.to, queue.removeAt(command.from))
                is Command.RemoveFromQueue -> queue.removeAt(command.index)
                else -> Unit
            }
            if (command !is Command.Seek && command !is Command.Next && command !is Command.Previous && command !is Command.SkipTo && command !is Command.PlayNow) base = pos
            since = now()
            changes.tryEmit(Unit)
        }

        override suspend fun open(ref: TrackRef): TrackFile? {
            received[ref]?.let { return TrackFile.of(it) }
            if (ref.title !in library) return null
            return counting(fileOf(ref), served)
        }

        override suspend fun artwork(ref: TrackRef): ByteArray? =
            received[ref]?.let { StreamCache.artOf(it).takeIf(File::exists)?.readBytes() } ?: coverOf(ref).takeIf { ref.title in library }

        override suspend fun has(ref: TrackRef) = ref.title in library

        /** Whether the last song a guest sent was queued before it had fully arrived. */
        var queuedEarly = false

        override suspend fun addReceived(ref: TrackRef, file: File, placement: Placement) {
            queuedEarly = !file.exists()
            received[ref] = file
            place(ref, placement)
            changes.tryEmit(Unit)
        }

        fun place(ref: TrackRef, placement: Placement) {
            when (placement) {
                Placement.End -> queue.add(ref)
                Placement.Next -> queue.add(index + 1, ref)
                Placement.Now -> {
                    queue.add(index + 1, ref)
                    index += 1
                    base = 0
                    since = now()
                    playing = true
                }
            }
        }
    }

    /** A song file that counts what's read from it; [delayMs] per read mimics a slow phone or network. */
    private fun counting(bytes: ByteArray, served: AtomicInteger, delayMs: Long = 0) = TrackFile(bytes.size.toLong()) { offset, length ->
        if (delayMs > 0) Thread.sleep(delayMs)
        served.addAndGet(length)
        bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
    }

    /** A song's cover as its owner would send it. */
    private fun coverOf(ref: TrackRef) = Random(ref.title.hashCode() + 1).nextBytes(20_000)

    /** The host's file for a song: big enough to need several request windows and chunks. */
    private fun fileOf(ref: TrackRef) = Random(ref.title.hashCode()).nextBytes(700_000 + ref.title.length)

    /** A guest player with its own clock, offset from the host's, and adjustable speed. */
    private inner class FakeGuest(private val library: Set<String>, private val clockOffset: Long, private val uploadDelayMs: Long = 0) : GuestPlayer {
        var loaded: TrackRef? = null
        var playing = false
        var speed = 1f
        var seeks = 0
        var speedChanges = 0
        private var base = 0L
        private var since = localNow()

        fun localNow() = now() + clockOffset

        var streamed: File? = null
        val uploaded = AtomicInteger()

        override suspend fun has(ref: TrackRef) = ref.title in library

        override suspend fun open(ref: TrackRef) = if (ref.title in library) counting(fileOf(ref), uploaded, uploadDelayMs) else null

        override suspend fun artwork(ref: TrackRef) = if (ref.title in library) coverOf(ref) else null

        /** Whether the last received song started before it had fully arrived. */
        var startedEarly = false

        override suspend fun loadFile(ref: TrackRef, file: File, positionMs: Long, play: Boolean): Boolean {
            streamed = file
            startedEarly = !file.exists()
            loaded = ref
            base = positionMs
            since = localNow()
            playing = play
            return true
        }

        override suspend fun load(ref: TrackRef, positionMs: Long, play: Boolean): Boolean {
            if (ref.title !in library) {
                loaded = null
                playing = false
                return false
            }
            streamed = null
            loaded = ref
            base = positionMs
            since = localNow()
            playing = play
            return true
        }

        override suspend fun setPlaying(playing: Boolean) {
            base = position()
            since = localNow()
            this.playing = playing
        }

        override suspend fun seekTo(positionMs: Long) {
            seeks++
            base = positionMs
            since = localNow()
        }

        override suspend fun setSpeed(speed: Float) {
            speedChanges++
            base = position()
            since = localNow()
            this.speed = speed
        }

        fun position(): Long = if (playing) base + ((localNow() - since) * speed).toLong() else base

        override suspend fun positionMs(): Long? = if (loaded == null) null else position()

        override suspend fun isPlaying() = playing
    }

    private val sessions = mutableListOf<() -> Unit>()

    @get:Rule
    val temp = TemporaryFolder()

    @After
    fun tearDown() = sessions.forEach { it() }

    private fun startHost(
        player: HostPlayer,
        approve: Boolean = true,
        mode: ControlMode = ControlMode.Democratic,
        approvals: AtomicInteger = AtomicInteger(),
        port: Int? = 0,
        relay: RelayEndpoint? = null,
        cache: StreamCache? = null,
    ): Pair<HostSession, Invite> {
        val key = SecureChannel.newKey()
        val config = HostConfig(
            "session_test", key, "Jam", "João", mode = mode, port = port,
            relay = relay?.let { RelayConfig(it, "t".repeat(40)) },
        )
        val host = HostSession(config, player, ::now, { approvals.incrementAndGet(); approve }, cache)
        val bound = host.start()
        sessions += { host.stop() }
        val address = if (bound > 0) "127.0.0.1" else null
        return host to Invite("session_test", address, bound.coerceAtLeast(0), key, "João", "Jam", relay = relay != null)
    }

    private fun join(
        invite: Invite,
        player: FakeGuest,
        name: String,
        relay: RelayEndpoint? = null,
        cache: StreamCache? = null,
    ): GuestSession {
        val guest = GuestSession(invite, name, player, player::localNow, relay = relay, cache = cache)
        guest.start()
        sessions += { guest.leave() }
        return guest
    }

    /** Songs play while they arrive: wait for the transfer to end before comparing bytes. */
    private suspend fun finished(file: File): File {
        until(10_000) { file.exists() }
        return file
    }

    private suspend fun until(timeoutMs: Long = 5_000, condition: () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(20)
    }

    @Test
    fun `guest joins after approval and follows play, pause, seek and track changes`() = runBlocking {
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer)
        val guestPlayer = FakeGuest(songs.map { it.title }.toSet(), clockOffset = 5_000)
        val guest = join(invite, guestPlayer, "Maria")

        until { guest.ui.value.status == GuestStatus.Connected }
        assertEquals(listOf("João", "Maria"), guest.ui.value.state!!.participants.map { it.name })

        host.hostCommand(Command.Play)
        until { guestPlayer.playing && guestPlayer.loaded == songs[0] }

        host.hostCommand(Command.Seek(60_000))
        until { abs(guestPlayer.position() - hostPlayer.position()) < 300 && hostPlayer.position() > 59_000 }

        delay(1_500)
        val drift = abs(guestPlayer.position() - hostPlayer.position())
        assertTrue("drift after sync was $drift ms (guest clock is 5 s off)", drift < 120)
        assertTrue("speed was reconfigured ${guestPlayer.speedChanges} times", guestPlayer.speedChanges <= 4)

        host.hostCommand(Command.Next)
        until { guestPlayer.loaded == songs[1] }

        host.hostCommand(Command.Pause)
        until { !guestPlayer.playing }
    }

    @Test
    fun `guest commands are applied by the host and reach everyone`() = runBlocking {
        val hostPlayer = FakeHost()
        val (_, invite) = startHost(hostPlayer)
        val a = FakeGuest(songs.map { it.title }.toSet(), 0)
        val b = FakeGuest(songs.map { it.title }.toSet(), -2_000)
        val ga = join(invite, a, "Ana")
        val gb = join(invite, b, "Bia")
        until { ga.ui.value.status == GuestStatus.Connected && gb.ui.value.status == GuestStatus.Connected }

        ga.send(Command.Play)
        until { hostPlayer.playing && b.playing }

        // Both press "next" at the same time: the host orders them, nobody ends up elsewhere.
        ga.send(Command.Next)
        gb.send(Command.Next)
        until { hostPlayer.index == 2 }
        until { a.loaded == songs[2] && b.loaded == songs[2] }
        until { ga.ui.value.state?.version == gb.ui.value.state?.version }
        assertEquals(ga.ui.value.state!!.index, gb.ui.value.state!!.index)
    }

    @Test
    fun `local pause on a guest becomes a command instead of diverging`() = runBlocking {
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer)
        val guestPlayer = FakeGuest(songs.map { it.title }.toSet(), 0)
        val guest = join(invite, guestPlayer, "Maria")
        until { guest.ui.value.status == GuestStatus.Connected }
        host.hostCommand(Command.Play)
        until { guestPlayer.playing }
        delay(600)

        guestPlayer.setPlaying(false) // e.g. the guest's headset button
        until { !hostPlayer.playing }
        delay(1_000)
        assertFalse(guestPlayer.playing)
    }

    @Test
    fun `host control mode denies guests until they are allowed`() = runBlocking {
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer, mode = ControlMode.HostControl)
        val guestPlayer = FakeGuest(songs.map { it.title }.toSet(), 0)
        val guest = join(invite, guestPlayer, "Maria")
        until { guest.ui.value.status == GuestStatus.Connected }

        guest.send(Command.Play)
        until { guest.ui.value.lastDenied > 0 }
        assertFalse(hostPlayer.playing)

        host.setCanControl(guest.ui.value.participantId!!, true)
        until { guest.ui.value.state?.participants?.any { it.name == "Maria" && it.canControl } == true }
        guest.send(Command.Play)
        until { hostPlayer.playing }
    }

    @Test
    fun `declined and kicked guests are told why`() = runBlocking {
        val (_, declinedInvite) = startHost(FakeHost(), approve = false)
        val declined = join(declinedInvite, FakeGuest(emptySet(), 0), "Zé")
        until { declined.ui.value.status == GuestStatus.Ended }
        assertEquals(RejectReason.Declined, declined.ui.value.endReason)

        val (host, invite) = startHost(FakeHost())
        val guest = join(invite, FakeGuest(emptySet(), 0), "Maria")
        until { guest.ui.value.status == GuestStatus.Connected }
        host.kick(guest.ui.value.participantId!!)
        until { guest.ui.value.status == GuestStatus.Ended }
        assertEquals(RejectReason.Kicked, guest.ui.value.endReason)
    }

    @Test
    fun `ending the session tells every guest it ended`() = runBlocking {
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer)
        val guestPlayer = FakeGuest(songs.map { it.title }.toSet(), 0)
        val guest = join(invite, guestPlayer, "Maria")
        until { guest.ui.value.status == GuestStatus.Connected }
        host.hostCommand(Command.Play)
        until { guestPlayer.playing }
        delay(1_000) // pings in flight, as in real use
        host.stop()
        until { guest.ui.value.status == GuestStatus.Ended }
        assertEquals(RejectReason.Ended, guest.ui.value.endReason)
    }

    @Test
    fun `missing files are reported without breaking the session`() = runBlocking {
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer)
        val guestPlayer = FakeGuest(setOf("Sinal Fechado"), 0)
        val guest = join(invite, guestPlayer, "Maria")
        until { guest.ui.value.status == GuestStatus.Connected }
        host.hostCommand(Command.Play)
        until { guest.ui.value.missingTrack }
        host.hostCommand(Command.Next)
        until { !guest.ui.value.missingTrack && guestPlayer.playing }
    }

    @Test
    fun `a wrong key can neither join nor control`() = runBlocking {
        val hostPlayer = FakeHost()
        val approvals = AtomicInteger()
        val (_, invite) = startHost(hostPlayer, approvals = approvals)
        val forged = invite.copy(key = SecureChannel.newKey())
        val intruder = join(forged, FakeGuest(songs.map { it.title }.toSet(), 0), "Intruso")
        intruder.send(Command.Play)
        delay(1_500)
        assertTrue(intruder.ui.value.status != GuestStatus.Connected)
        assertEquals(0, approvals.get())
        assertFalse(hostPlayer.playing)
    }

    @Test
    fun `a returning participant reconnects without asking again`() = runBlocking {
        val hostPlayer = FakeHost()
        val approvals = AtomicInteger()
        val (host, invite) = startHost(hostPlayer, approvals = approvals)
        val guestPlayer = FakeGuest(songs.map { it.title }.toSet(), 0)
        val guest = join(invite, guestPlayer, "Maria")
        until { guest.ui.value.status == GuestStatus.Connected }
        host.hostCommand(Command.Play)
        until { guestPlayer.playing }

        guest.dropConnectionForTest()
        until { guest.ui.value.status == GuestStatus.Reconnecting }
        host.hostCommand(Command.Next)
        until(10_000) { guest.ui.value.status == GuestStatus.Connected && guestPlayer.loaded == songs[1] }
        assertEquals(1, approvals.get())
    }

    @Test
    fun `guests outside the network join through the relay and reconnect through it`() = runBlocking {
        val relay = FakeRelay()
        val endpoint = RelayEndpoint("wss://relay.test/relay/v1", relay)
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer, port = null, relay = endpoint)
        assertEquals(null, invite.host)
        val guestPlayer = FakeGuest(songs.map { it.title }.toSet(), clockOffset = -3_000)
        until { relay.hostOnline }
        val guest = join(invite, guestPlayer, "Maria", endpoint)

        until { guest.ui.value.status == GuestStatus.Connected }
        guest.send(Command.Play)
        until { hostPlayer.playing && guestPlayer.playing && guestPlayer.loaded == songs[0] }
        host.hostCommand(Command.Seek(30_000))
        until { abs(guestPlayer.position() - hostPlayer.position()) < 300 && hostPlayer.position() > 29_000 }

        guest.dropConnectionForTest()
        until { guest.ui.value.status == GuestStatus.Reconnecting }
        host.hostCommand(Command.Next)
        until(10_000) { guest.ui.value.status == GuestStatus.Connected && guestPlayer.loaded == songs[1] }
        assertEquals(2, relay.guestOpens.get())
    }

    @Test
    fun `an unreachable local address falls back to the relay while nearby guests stay direct`() = runBlocking {
        val relay = FakeRelay()
        val endpoint = RelayEndpoint("wss://relay.test/relay/v1", relay)
        val hostPlayer = FakeHost()
        val (_, invite) = startHost(hostPlayer, relay = endpoint)
        until { relay.hostOnline }
        val near = FakeGuest(songs.map { it.title }.toSet(), 0)
        val far = FakeGuest(songs.map { it.title }.toSet(), 7_000)
        val nearGuest = join(invite, near, "Ana", endpoint)
        // A port nobody listens on stands in for a LAN address that isn't reachable from mobile data.
        val closedPort = ServerSocket(0).use { it.localPort }
        val farGuest = join(invite.copy(port = closedPort), far, "Bia", endpoint)

        until { nearGuest.ui.value.status == GuestStatus.Connected && farGuest.ui.value.status == GuestStatus.Connected }
        assertEquals(1, relay.guestOpens.get())
        until { farGuest.ui.value.state!!.participants.map { it.name }.sorted() == listOf("Ana", "Bia", "João") }

        farGuest.send(Command.Play)
        until { near.playing && far.playing }
        delay(1_500)
        val drift = abs(far.position() - hostPlayer.position())
        assertTrue("drift through the relay was $drift ms", drift < 120)
    }

    @Test
    fun `invites without a relay never touch it`() = runBlocking {
        val relay = FakeRelay()
        val (_, invite) = startHost(FakeHost())
        val guest = join(invite, FakeGuest(emptySet(), 0), "Ana", RelayEndpoint("wss://relay.test/relay/v1", relay))
        until { guest.ui.value.status == GuestStatus.Connected }
        assertEquals(0, relay.guestOpens.get())
    }
    @Test
    fun `a song missing on the guest is received from the host and played in sync`() = runBlocking {
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer)
        val guestPlayer = FakeGuest(emptySet(), clockOffset = 2_000)
        val cache = StreamCache(temp.newFolder("cache"))
        val guest = join(invite, guestPlayer, "Maria", cache = cache)
        until { guest.ui.value.status == GuestStatus.Connected }
        host.hostCommand(Command.Seek(20_000))
        host.hostCommand(Command.Play)

        until(10_000) { guestPlayer.streamed != null && guestPlayer.playing && !guest.ui.value.missingTrack }
        assertEquals(songs[0], guestPlayer.loaded)
        assertTrue("received copy is identical", finished(guestPlayer.streamed!!).readBytes().contentEquals(fileOf(songs[0])))
        delay(1_500)
        val drift = abs(guestPlayer.position() - hostPlayer.position())
        assertTrue("drift of a streamed song was $drift ms", drift < 120)

        // The next song was fetched ahead, so the change of track doesn't wait for a transfer.
        until(10_000) { cache.complete(songs[1]) != null }
        host.hostCommand(Command.Next)
        until { guestPlayer.loaded == songs[1] && guestPlayer.streamed != null && guestPlayer.playing }

        guest.leave()
        until { !cache.dir.exists() }
    }

    @Test
    fun `songs are streamed through the relay too, and only what the guest lacks`() = runBlocking {
        val relay = FakeRelay()
        val endpoint = RelayEndpoint("wss://relay.test/relay/v1", relay)
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer, port = null, relay = endpoint)
        until { relay.hostOnline }
        val guestPlayer = FakeGuest(setOf("Saudade"), 0)
        val cache = StreamCache(temp.newFolder("cache"))
        val guest = join(invite, guestPlayer, "Bia", endpoint, cache)
        until { guest.ui.value.status == GuestStatus.Connected }
        host.hostCommand(Command.Play)
        until { guestPlayer.playing && guestPlayer.loaded == songs[0] }
        assertEquals(null, guestPlayer.streamed)

        // Only the next song, which this guest lacks, is transferred.
        until(10_000) { cache.complete(songs[1]) != null }
        delay(300)
        assertTrue("served ${hostPlayer.served.get()} bytes", hostPlayer.served.get() < 2 * 700_000)
        host.hostCommand(Command.Next)
        until { guestPlayer.loaded == songs[1] && guestPlayer.streamed != null }
    }

    @Test
    fun `guests cannot fetch songs outside the queue`() = runBlocking {
        val hostPlayer = FakeHost()
        hostPlayer.queue = songs.take(1).toMutableList()
        val (_, invite) = startHost(hostPlayer)
        val guestPlayer = FakeGuest(emptySet(), 0)
        val cache = StreamCache(temp.newFolder("cache"))
        val guest = join(invite, guestPlayer, "Ana", cache = cache)
        until { guest.ui.value.status == GuestStatus.Connected }
        // Songs play while they arrive: count what was served only once the transfer is over.
        until(10_000) { cache.complete(songs[0]) != null }
        val before = hostPlayer.served.get()

        // A guest asking for anything else gets nothing: the host only serves its current queue.
        val outsider = TrackRef("Outra", "Alguém", null, 100_000)
        val raw = TcpConnection.connect("127.0.0.1", invite.port, 2_000)
        val link = SecureLink(raw, SecureChannel(invite.key, invite.sessionId))
        link.send(Message.Hello(Wire.PROTOCOL, "Ana", guest.ui.value.participantId))
        while (link.receive(2_000) !is Message.Welcome) Unit
        link.send(Message.Fetch(outsider, 0, 1_000))
        var answer: Message
        do answer = link.receive(2_000) while (answer !is Message.Unavailable)
        assertEquals(outsider, answer.track)
        assertEquals(before, hostPlayer.served.get())
        raw.close()
    }
    @Test
    fun `a guest's own song is sent to the host with its cover and played by everyone`() = runBlocking {
        val mine = TrackRef("Canção da Maria", "Maria", "Demos", 190_000)
        val hostPlayer = FakeHost()
        val hostCache = StreamCache(temp.newFolder("host"))
        val relay = FakeRelay()
        val endpoint = RelayEndpoint("wss://relay.test/relay/v1", relay)
        val (host, invite) = startHost(hostPlayer, relay = endpoint, cache = hostCache)
        until { relay.hostOnline }
        val owner = FakeGuest(songs.map { it.title }.toSet() + mine.title, 0)
        val other = FakeGuest(songs.map { it.title }.toSet(), 3_000)
        val ownerSession = join(invite, owner, "Maria", cache = StreamCache(temp.newFolder("owner")))
        val otherCache = StreamCache(temp.newFolder("other"))
        // The other guest is "on mobile data": it reaches the host only through the relay.
        val otherSession = join(invite.copy(port = ServerSocket(0).use { it.localPort }), other, "Bia", endpoint, otherCache)
        until { ownerSession.ui.value.status == GuestStatus.Connected && otherSession.ui.value.status == GuestStatus.Connected }

        ownerSession.send(Command.AddToQueue(listOf(mine)))
        // While the song travels it shows as incoming; on a fast link it may already be queued
        // before that state goes out, so only its arrival in the queue is required.
        until(10_000) { otherSession.ui.value.state?.queue?.contains(mine) == true }
        assertTrue("host received an identical copy", finished(hostPlayer.received[mine]!!).readBytes().contentEquals(fileOf(mine)))
        assertTrue("host received the cover", finished(StreamCache.artOf(hostPlayer.received[mine]!!)).readBytes().contentEquals(coverOf(mine)))
        assertTrue(otherSession.ui.value.state!!.incoming.isEmpty())

        host.hostCommand(Command.SkipTo(hostPlayer.queue.indexOf(mine)))
        host.hostCommand(Command.Play)
        // The owner plays its own file; the other guest gets the copy (and cover) from the host.
        until { owner.loaded == mine && owner.playing && owner.streamed == null }
        until(10_000) { other.loaded == mine && other.playing && other.streamed != null }
        assertTrue(finished(other.streamed!!).readBytes().contentEquals(fileOf(mine)))
        assertTrue(finished(StreamCache.artOf(other.streamed!!)).readBytes().contentEquals(coverOf(mine)))
        assertEquals("sent once, to the host only", fileOf(mine).size, owner.uploaded.get())
        delay(1_500)
        val drift = abs(other.position() - hostPlayer.position())
        assertTrue("drift of a guest's song was $drift ms", drift < 120)
    }

    @Test
    fun `songs the host already has are not uploaded`() = runBlocking {
        val hostPlayer = FakeHost()
        val (_, invite) = startHost(hostPlayer, cache = StreamCache(temp.newFolder("host")))
        val owner = FakeGuest(songs.map { it.title }.toSet(), 0)
        val session = join(invite, owner, "Maria")
        until { session.ui.value.status == GuestStatus.Connected }
        session.send(Command.AddToQueue(listOf(songs[2])))
        until { hostPlayer.queue.count { it == songs[2] } == 2 }
        delay(300)
        assertEquals(0, owner.uploaded.get())
    }

    @Test
    fun `in host control mode a guest without permission cannot add songs`() = runBlocking {
        val mine = TrackRef("Canção da Maria", "Maria", "Demos", 190_000)
        val hostPlayer = FakeHost()
        val (_, invite) = startHost(hostPlayer, mode = ControlMode.HostControl, cache = StreamCache(temp.newFolder("host")))
        val owner = FakeGuest(setOf(mine.title), 0)
        val session = join(invite, owner, "Maria")
        until { session.ui.value.status == GuestStatus.Connected }
        session.send(Command.AddToQueue(listOf(mine)))
        until { session.ui.value.lastDenied > 0 }
        delay(300)
        assertEquals(0, owner.uploaded.get())
        assertTrue(mine !in hostPlayer.queue)
    }
    @Test
    fun `a song a guest picks replaces what everyone hears`() = runBlocking {
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer, cache = StreamCache(temp.newFolder("host")))
        val picker = FakeGuest(songs.map { it.title }.toSet(), 0)
        val listener = FakeGuest(songs.map { it.title }.toSet(), 1_500)
        val a = join(invite, picker, "Ana")
        val b = join(invite, listener, "Bia")
        until { a.ui.value.status == GuestStatus.Connected && b.ui.value.status == GuestStatus.Connected }
        host.hostCommand(Command.Play)
        until { listener.loaded == songs[0] && listener.playing }

        a.send(Command.PlayNow(songs[2]))
        until { hostPlayer.queue[hostPlayer.index] == songs[2] && hostPlayer.playing }
        until { picker.loaded == songs[2] && listener.loaded == songs[2] && listener.playing }
        assertEquals("inserted right after the song that was playing", 1, hostPlayer.index)
    }

    @Test
    fun `a guest's own song picked to play now plays for everyone once the host has it`() = runBlocking {
        val mine = TrackRef("Canção da Maria", "Maria", "Demos", 190_000)
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer, cache = StreamCache(temp.newFolder("host")))
        val owner = FakeGuest(songs.map { it.title }.toSet() + mine.title, 0)
        val listener = FakeGuest(songs.map { it.title }.toSet(), 2_000)
        val a = join(invite, owner, "Maria", cache = StreamCache(temp.newFolder("owner")))
        val b = join(invite, listener, "Bia", cache = StreamCache(temp.newFolder("listener")))
        until { a.ui.value.status == GuestStatus.Connected && b.ui.value.status == GuestStatus.Connected }
        host.hostCommand(Command.Play)
        until { listener.loaded == songs[0] && listener.playing }

        a.send(Command.PlayNow(mine))
        until(10_000) { hostPlayer.queue.getOrNull(hostPlayer.index) == mine && hostPlayer.playing }
        until { owner.loaded == mine && owner.streamed == null && owner.playing }
        until(10_000) { listener.loaded == mine && listener.streamed != null && listener.playing }
        delay(1_500)
        val drift = abs(listener.position() - hostPlayer.position())
        assertTrue("drift was $drift ms", drift < 120)
    }

    @Test
    fun `play next from a guest goes right after the current song without interrupting it`() = runBlocking {
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer, cache = StreamCache(temp.newFolder("host")))
        val guest = FakeGuest(songs.map { it.title }.toSet(), 0)
        val session = join(invite, guest, "Ana")
        until { session.ui.value.status == GuestStatus.Connected }
        host.hostCommand(Command.Play)
        until { guest.loaded == songs[0] && guest.playing }
        session.send(Command.PlayNext(listOf(songs[2])))
        until { hostPlayer.queue.getOrNull(1) == songs[2] }
        assertEquals(0, hostPlayer.index)
        assertEquals(songs[0], guest.loaded)
    }
    @Test
    fun `a song this phone lacks starts playing before it has fully arrived`() = runBlocking {
        val mine = TrackRef("Canção da Maria", "Maria", "Demos", 190_000)
        val hostPlayer = FakeHost()
        val (host, invite) = startHost(hostPlayer, cache = StreamCache(temp.newFolder("host")))
        val owner = FakeGuest(songs.map { it.title }.toSet() + mine.title, 0)
        val listener = FakeGuest(emptySet(), 0)
        val a = join(invite, owner, "Maria")
        val b = join(invite, listener, "Bia", cache = StreamCache(temp.newFolder("listener")))
        until { a.ui.value.status == GuestStatus.Connected && b.ui.value.status == GuestStatus.Connected }
        host.hostCommand(Command.Play)
        until(10_000) { listener.loaded == songs[0] && listener.playing }
        assertTrue("the host's song played while arriving", listener.startedEarly)

        // A guest's own song: the host queues it on the first chunk and the others follow on theirs.
        a.send(Command.PlayNow(mine))
        until(10_000) { hostPlayer.queue.getOrNull(hostPlayer.index) == mine }
        assertTrue("host switched before the song had fully arrived", hostPlayer.queuedEarly)
        until(10_000) { listener.loaded == mine && listener.playing }
        assertTrue(listener.startedEarly)
        assertTrue(finished(listener.streamed!!).readBytes().contentEquals(fileOf(mine)))
    }

    @Test
    fun `reactions reach everyone, only allowed emojis, and nobody can flood them`() = runBlocking {
        val (host, invite) = startHost(FakeHost())
        val a = join(invite, FakeGuest(emptySet(), 0), "Ana")
        val b = join(invite, FakeGuest(emptySet(), 0), "Bia")
        until { a.ui.value.status == GuestStatus.Connected && b.ui.value.status == GuestStatus.Connected }
        val seenByB = java.util.Collections.synchronizedList(ArrayList<Message.Reaction>())
        val seenByHost = java.util.Collections.synchronizedList(ArrayList<Message.Reaction>())
        val jobs = listOf(
            launch(kotlinx.coroutines.Dispatchers.Default) { b.reactions.collect { seenByB += it } },
            launch(kotlinx.coroutines.Dispatchers.Default) { host.reactions.collect { seenByHost += it } },
        )
        delay(200)
        a.react("🔥")
        a.react("🔥") // too soon: dropped
        a.react("<script>") // not an allowed reaction: dropped
        until { seenByB.size == 1 && seenByHost.size == 1 }
        assertEquals(Message.Reaction("Ana", "🔥"), seenByB.single())
        host.hostReact("🎉")
        until { seenByB.size == 2 }
        assertEquals(Message.Reaction("João", "🎉"), seenByB.last())
        delay(800)
        a.react("❤️")
        until { seenByB.size == 3 }
        delay(300)
        assertEquals(3, seenByB.size)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `everyone sees a guest's song arriving, with its progress, until it joins the queue`() = runBlocking {
        val mine = TrackRef("Canção da Maria", "Maria", "Demos", 190_000)
        val hostPlayer = FakeHost()
        val (_, invite) = startHost(hostPlayer, cache = StreamCache(temp.newFolder("host")))
        // A slow sender: each chunk takes a while to read, so the transfer is visible.
        val owner = FakeGuest(songs.map { it.title }.toSet() + mine.title, 0, uploadDelayMs = 400)
        val other = FakeGuest(songs.map { it.title }.toSet(), 0)
        val a = join(invite, owner, "Maria")
        val b = join(invite, other, "Bia")
        until { a.ui.value.status == GuestStatus.Connected && b.ui.value.status == GuestStatus.Connected }
        a.send(Command.AddToQueue(listOf(mine)))
        until { b.ui.value.state?.incoming?.any { it.track == mine && it.from == "Maria" } == true }
        until(15_000) { b.ui.value.state?.queue?.contains(mine) == true }
        assertTrue(b.ui.value.state!!.incoming.none { it.track == mine })
    }
}
