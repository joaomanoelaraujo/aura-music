package com.aura.desktop.together

import com.aura.desktop.TestMedia
import com.aura.desktop.audio.AudioEngine
import com.aura.desktop.audio.RealtimeSink
import com.aura.desktop.data.LibraryRepository
import com.aura.desktop.data.SettingsStore
import com.aura.desktop.library.ArtworkStore
import com.aura.desktop.playback.PlayerController
import com.aura.music.together.guest.GuestPlayer
import com.aura.music.together.guest.GuestSession
import com.aura.music.together.guest.GuestStatus
import com.aura.music.together.host.HostConfig
import com.aura.music.together.host.HostPlayback
import com.aura.music.together.host.HostPlayer
import com.aura.music.together.host.HostSession
import com.aura.music.together.host.Placement
import com.aura.music.together.protocol.Command
import com.aura.music.together.protocol.Invite
import com.aura.music.together.protocol.RepeatSetting
import com.aura.music.together.protocol.SecureChannel
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.transfer.TrackFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * AURA Together on the computer against the shared session code (the same the Android app runs),
 * over loopback TCP: the desktop hosting a guest, and the desktop as a guest of a simulated phone.
 */
class DesktopTogetherTest {

    @get:Rule val temp = TemporaryFolder()

    private val mainThread = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }
    private val main = mainThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val engine = AudioEngine(RealtimeSink(speed = 0.5))
    private val player = PlayerController(engine, scope, main, 1f)
    private val cleanups = mutableListOf<() -> Unit>()
    private lateinit var library: LibraryRepository
    private lateinit var together: DesktopTogether

    @After
    fun tearDown() {
        cleanups.forEach { runCatching(it) }
        runCatching { together.leave() }
        scope.cancel()
        engine.close()
        mainThread.shutdownNow()
    }

    private fun onMain(block: () -> Unit) = mainThread.submit(block).get()

    private fun waitFor(timeoutMs: Long = 15_000, what: String = "", condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out $what; role=${together.role.value}" }
            Thread.sleep(25)
        }
    }

    private fun setUp() {
        val dir = temp.newFolder().toPath()
        val settings = SettingsStore(scope, dir.resolve("settings.json"))
        val artwork = ArtworkStore(dir.resolve("art"))
        library = LibraryRepository(scope, settings, artwork, snapshotPath = dir.resolve("library.json"))
        onMain {
            library.start()
            library.addFolder(TestMedia.root)
        }
        waitFor(what = "library") { library.state.value.index.tracks.size == 6 && library.state.value.scanning == null }
        together = DesktopTogether(scope, main, player, library, artwork, dir.resolve("together"), relayUrl = null, localAddress = { "127.0.0.1" })
    }

    private fun ref(title: String): TrackRef {
        val t = library.state.value.index.tracks.first { it.title == title }
        return com.aura.music.together.sync.TrackMatcher.ref(t)
    }

    /** A guest device that has the same songs and just remembers what it was told to play. */
    private class FakeGuestPlayer(val titles: Set<String>) : GuestPlayer {
        val loaded = MutableStateFlow<TrackRef?>(null)
        @Volatile var playing = false
        @Volatile var position = 0L
        override suspend fun load(ref: TrackRef, positionMs: Long, play: Boolean): Boolean {
            if (ref.title !in titles) return false
            loaded.value = ref
            position = positionMs
            playing = play
            return true
        }
        override suspend fun loadFile(ref: TrackRef, file: File, positionMs: Long, play: Boolean) = false
        override suspend fun has(ref: TrackRef) = ref.title in titles
        override suspend fun setPlaying(playing: Boolean) { this.playing = playing }
        override suspend fun seekTo(positionMs: Long) { position = positionMs }
        override suspend fun setSpeed(speed: Float) = Unit
        override suspend fun positionMs(): Long? = position
        override suspend fun isPlaying() = playing
        override suspend fun open(ref: TrackRef): TrackFile? = null
        override suspend fun artwork(ref: TrackRef): ByteArray? = null
    }

    @Test
    fun theComputerHostsAndAGuestFollowsAndControlsIt() {
        setUp()
        onMain { assertTrue(together.startHosting(DesktopTogether.HostOptions("Jam de teste", "PC", approve = true))) }
        val link = (together.role.value as JamRole.Hosting).link
        val invite = Invite.parse(link)!!
        assertEquals("127.0.0.1", invite.host)

        val fakePlayer = FakeGuestPlayer(library.state.value.index.tracks.map { it.title }.toSet())
        val guest = GuestSession(invite, "Celular", fakePlayer, { System.nanoTime() / 1_000_000 })
        cleanups += { guest.leave() }
        guest.start()
        // The host's user is asked first.
        waitFor(what = "join request") { together.requests.value.isNotEmpty() }
        assertEquals("Celular", together.requests.value.single().name)
        together.answer(together.requests.value.single(), accept = true)
        waitFor(what = "guest connected") { guest.ui.value.status == GuestStatus.Connected }

        // The computer plays: the guest loads its own copy of the same song.
        val tracks = library.state.value.index.tracks
        val target = tracks.indexOfFirst { it.title == "Noite de Verão" }
        onMain { player.playTracks(tracks, target) }
        waitFor(what = "guest loads") { fakePlayer.loaded.value?.title == "Noite de Verão" && fakePlayer.playing }

        // The guest pauses the computer.
        guest.send(Command.Pause)
        waitFor(what = "desktop paused") { !player.state.value.isPlaying }
        waitFor(what = "guest paused") { !fakePlayer.playing }

        // Revoked links stop newcomers.
        onMain { together.revokeInvites() }
        val late = GuestSession(invite, "Atrasado", FakeGuestPlayer(emptySet()), { System.nanoTime() / 1_000_000 })
        cleanups += { late.leave() }
        late.start()
        waitFor(what = "late refused") { late.ui.value.status == GuestStatus.Ended }
        assertEquals(GuestStatus.Connected, guest.ui.value.status)
    }

    /** A phone hosting a jam: it plays [queue] and accepts commands (like the Android adapter). */
    private class FakePhone(var queue: List<TrackRef>) : HostPlayer {
        @Volatile var index = 0
        @Volatile var playing = true
        val changesFlow = MutableStateFlow(0)
        val commands = CopyOnWriteArrayList<Command>()
        override val changes: Flow<Unit> = changesFlow.map { }
        override suspend fun read() = HostPlayback(queue, index, playing, 1_000, false, RepeatSetting.Off)
        override suspend fun apply(command: Command) {
            commands += command
            when (command) {
                Command.Pause -> playing = false
                Command.Play -> playing = true
                Command.Next -> index = (index + 1).coerceAtMost(queue.lastIndex)
                else -> Unit
            }
            changesFlow.value++
        }
        override suspend fun has(ref: TrackRef) = true
        override suspend fun addReceived(ref: TrackRef, file: File, placement: Placement) = Unit

        /** Songs this phone can send, by ref (only those in its queue, like the real adapters). */
        val files = HashMap<TrackRef, File>()
        override suspend fun open(ref: TrackRef): TrackFile? = files[ref]?.takeIf { ref in queue }?.let { f ->
            TrackFile(f.length()) { offset, length ->
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(offset)
                    val buffer = ByteArray(minOf(length.toLong(), f.length() - offset).toInt().coerceAtLeast(0))
                    raf.readFully(buffer)
                    buffer
                }
            }
        }
        override suspend fun artwork(ref: TrackRef): ByteArray? = null
    }

    @Test
    fun theComputerJoinsAPhonesJamPlaysItsOwnCopyAndShowsMissingSongs() {
        setUp()
        val missing = TrackRef("Música que só o celular tem", "Alguém", null, 200_000)
        val phone = FakePhone(listOf(ref("Água Clara"), missing))
        val key = SecureChannel.newKey()
        val host = HostSession(
            HostConfig("phone_jam_1", key, "Jam do celular", "Celular", requireApproval = false, port = 0, inviteId = "inv_phone"),
            phone, { System.nanoTime() / 1_000_000 }, { true },
        )
        val port = host.start()
        cleanups += { host.stop() }
        val link = Invite("phone_jam_1", "127.0.0.1", port, key, "Celular", "Jam do celular", inviteId = "inv_phone")
            .toLink("https://aura-convites.johnmanoelaraujo.workers.dev/aura/j/")

        onMain { assertTrue(together.join(link, "PC", receiveMissing = false)) }
        waitFor(what = "joined") { (together.role.value as? JamRole.Guest)?.ui?.status == GuestStatus.Connected }
        // Local Sync: this computer plays its own copy of the host's song.
        waitFor(what = "local copy") { player.state.value.current?.title == "Água Clara" && player.state.value.isPlaying }

        // A local "pause" goes to the host as a command; the host decides.
        onMain { player.togglePlayPause() }
        waitFor(what = "command reached host") { Command.Pause in phone.commands }
        waitFor(what = "paused by host state") { !player.state.value.isPlaying }

        // The host moves on to a song this computer doesn't have: shown as missing, nothing else plays.
        phone.index = 1
        phone.playing = true
        phone.changesFlow.value++
        waitFor(what = "missing") { (together.role.value as JamRole.Guest).ui.missingTrack }
        assertEquals(null, player.state.value.current)

        onMain { together.leave() }
        assertEquals(JamRole.Idle, together.role.value)
        assertEquals(null, player.redirect)
    }

    /** A silent stereo WAV of [seconds] (44.1 kHz, 16 bits): ~176 KB per second, several windows long. */
    private fun silentWav(name: String, seconds: Int): File {
        val pcm = 44_100 * 4 * seconds
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(44_100); putInt(44_100 * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(pcm)
        }.array()
        return File(temp.newFolder(), name).apply { writeBytes(header + ByteArray(pcm)) }
    }

    @Test
    fun theComputerReceivesSongAfterSongItDoesntHave() {
        setUp()
        // Several songs in a row that only the phone has, each a few transfer windows long: the
        // computer must receive and play every one of them, not just the first.
        val songs = (1..4).map { i ->
            val file = silentWav("so_no_celular_$i.wav", 3)
            TrackRef("Só no celular $i", "Alguém", null, 3_000, file.length()) to file
        }
        val phone = FakePhone(songs.map { it.first })
        songs.forEach { (ref, file) -> phone.files[ref] = file }
        val key = SecureChannel.newKey()
        val host = HostSession(
            HostConfig("phone_jam_2", key, "Jam do celular", "Celular", requireApproval = false, port = 0, inviteId = "inv_phone2"),
            phone, { System.nanoTime() / 1_000_000 }, { true },
        )
        val port = host.start()
        cleanups += { host.stop() }
        val link = Invite("phone_jam_2", "127.0.0.1", port, key, "Celular", "Jam do celular", inviteId = "inv_phone2")
            .toLink("https://aura-convites.johnmanoelaraujo.workers.dev/aura/j/")

        onMain { assertTrue(together.join(link, "PC", receiveMissing = true)) }
        waitFor(what = "joined") { (together.role.value as? JamRole.Guest)?.ui?.status == GuestStatus.Connected }
        songs.forEachIndexed { i, (ref, _) ->
            if (i > 0) {
                phone.index = i
                phone.changesFlow.value++
            }
            waitFor(what = "song ${i + 1} received and playing") {
                val guest = together.role.value as JamRole.Guest
                player.state.value.current?.title == ref.title && player.state.value.isPlaying && !guest.ui.missingTrack
            }
        }
        onMain { together.leave() }
    }
}
