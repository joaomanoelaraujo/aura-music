package com.aura.desktop.link

import com.aura.desktop.audio.AudioEngine
import com.aura.desktop.audio.RealtimeSink
import com.aura.desktop.data.LibraryRepository
import com.aura.desktop.data.SettingsStore
import com.aura.desktop.library.ArtworkStore
import com.aura.desktop.playback.PlayerController
import com.aura.desktop.playback.Repeat
import com.aura.music.link.RemoteAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.util.concurrent.Executors

/**
 * Manual end-to-end harness: the real desktop AURA Link service and player (silent output) without
 * a window, for testing against the Android app in an emulator (the computer is 10.0.2.2 there).
 *
 * Writes the pairing code and a status line per second to [log]; reads commands from [commands]
 * (one per line): `phone play|pause|next|previous|seek <ms>|volume <0..1>`, `local play|pause`,
 * `pair` (new code), `revoke`, `stop`.
 */
fun main(args: Array<String>) {
    val dataDir = Path.of(args[0])
    val media = Path.of(args[1])
    val log = File(args[2])
    val commands = File(args[3])
    Files.createDirectories(dataDir)
    log.writeText("")
    commands.writeText("")
    fun out(line: String) = synchronized(log) { log.appendText("${LocalTime.now().withNano(0)} $line\n") }

    val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "harness-main") }
    val main = thread.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + main)
    fun onMain(block: () -> Unit) = thread.submit(block).get()

    val settings = SettingsStore(scope, dataDir.resolve("settings.json"))
    val artwork = ArtworkStore(dataDir.resolve("art"))
    val library = LibraryRepository(scope, settings, artwork, snapshotPath = dataDir.resolve("library.json"))
    val engine = AudioEngine(RealtimeSink(speed = 1.0))
    val player = PlayerController(engine, scope, main, 0.8f)
    val devices = PairedDeviceStore(scope, dataDir.resolve("link/devices.json"))
    val receiveDir = dataDir.resolve("received")
    val transfers = DesktopTransfers(
        scope, staging = dataDir.resolve("staging"), receiveDir = { receiveDir },
        permissions = { id -> devices.find(id)?.permissions },
        onReceived = { files -> out("RECEIVED ${files.joinToString { "${it.fileName} (${Files.size(it)} bytes)" }}") },
    )
    val link = LinkService(
        scope, main, LocalIdentityStore(dataDir.resolve("link")), devices,
        DesktopPlayerBridge(player, library, artwork), appVersion = "harness", advertise = false, transfers = transfers,
    )
    // Jams: invites carry 10.0.2.2, the address an Android emulator uses to reach this computer.
    val together = com.aura.desktop.together.DesktopTogether(scope, main, player, library, artwork, dataDir.resolve("together"), localAddress = { "10.0.2.2" })
    onMain {
        library.start()
        library.addFolder(media)
    }
    while (library.state.value.index.tracks.isEmpty() || library.state.value.scanning != null) Thread.sleep(100)
    onMain {
        player.setRepeat(Repeat.All)
        player.playTracks(library.state.value.index.tracks, 0)
        link.start()
        link.openPairing()
        scope.launch { player.state.collect { link.publishState() } }
        scope.launch { player.position.collect { link.publishState() } }
    }
    out("READY port=${link.state.value.port} code=${link.state.value.pairing?.code} tracks=${library.state.value.index.tracks.size}")

    var running = true
    var lastStatus = ""
    while (running) {
        val s = link.state.value
        together.requests.value.firstOrNull()?.let { req ->
            out("JAM REQUEST from='${req.name}' -> accepting")
            together.answer(req, true)
        }
        transfers.incoming.value?.let { offer ->
            out("INCOMING from='${offer.peerName}' files=${offer.offer.files.map { it.name }} -> accepting")
            transfers.answerIncoming(accept = true, always = false)
            Thread.sleep(300)
        }
        s.offer?.let { offer ->
            out("OFFER from='${offer.name}' platform=${offer.platform} verification=${offer.verificationCode} -> accepting")
            link.answerOffer(true)
            Thread.sleep(300)
        }
        val p = player.state.value
        val peers = s.peers.joinToString("; ") { peer ->
            val r = peer.remoteState
            "${peer.device.name}[${if (peer.connected) "on" else "off"}${if (peer.paused) ",paused" else ""}] granted=${peer.granted} " +
                (r?.let { "plays='${it.current?.title}' playing=${it.isPlaying} pos=${it.positionMs} vol=${it.volume} queue=${it.queue.size}" } ?: "no-state")
        }
        val jobs = transfers.jobs.value.joinToString { j -> "${if (j.outgoing) "OUT" else "IN"}:${j.status}:${j.doneBytes}/${j.totalBytes}${j.error?.let { e -> " ($e)" } ?: ""}" }
        val jam = when (val r = together.role.value) {
            is com.aura.desktop.together.JamRole.Hosting -> "HOST participants=${r.state?.participants?.map { it.name }} index=${r.state?.index} playing=${r.state?.isPlaying}"
            is com.aura.desktop.together.JamRole.Guest -> "GUEST ${r.ui.status} plays='${r.ui.state?.current?.title}' missing=${r.ui.missingTrack} rtt=${r.ui.rttMs} drift=${r.ui.averageDriftMs} end=${r.ui.endReason}"
            else -> "idle"
        }
        val status = "jam=[$jam] transfers=[$jobs] desktop playing=${p.isPlaying} track='${p.current?.title}' vol=${p.volume} controlledBy=${s.controlledBy} | pairing=${s.pairing?.code} | msg=${s.message} | $peers"
        if (status != lastStatus) {
            out("STATUS $status")
            lastStatus = status
        }
        val lines = synchronized(commands) { commands.readLines().also { if (it.isNotEmpty()) commands.writeText("") } }
        for (line in lines.map(String::trim).filter(String::isNotEmpty)) {
            out("CMD $line")
            val parts = line.split(' ')
            val phone = s.peers.firstOrNull { it.connected }?.device?.deviceId
            when (parts[0]) {
                "phone" -> if (phone == null) out("no phone connected") else link.command(
                    phone,
                    when (parts.getOrNull(1)) {
                        "play" -> RemoteAction.Play
                        "pause" -> RemoteAction.Pause
                        "next" -> RemoteAction.Next
                        "previous" -> RemoteAction.Previous
                        "seek" -> RemoteAction.Seek(parts[2].toLong())
                        "volume" -> RemoteAction.SetVolume(parts[2].toFloat())
                        else -> { out("unknown"); continue }
                    },
                )
                "send" -> if (phone == null) out("no phone connected") else {
                    val track = library.state.value.index.tracks.first { it.title.contains(parts.drop(1).joinToString(" "), ignoreCase = true) }
                    onMain { link.sendFiles(phone, listOf(track)) }
                    out("SENDING '${track.title}' (${track.sizeBytes} bytes)")
                }
                "jamhost" -> onMain {
                    together.startHosting(com.aura.desktop.together.DesktopTogether.HostOptions("Jam do PC", "PC de Teste", approve = true))
                    out("JAM LINK ${(together.role.value as? com.aura.desktop.together.JamRole.Hosting)?.link}")
                }
                "jamjoin" -> onMain { out("JOIN ${together.join(parts.drop(1).joinToString(" "), "PC de Teste", receiveMissing = false)}") }
                "jamleave" -> onMain { together.leave() }
                "local" -> onMain { if ((parts.getOrNull(1) == "play") != player.state.value.isPlaying) player.togglePlayPause() }
                "pair" -> onMain { link.openPairing() }.also { out("CODE ${link.state.value.pairing?.code}") }
                "revoke" -> s.peers.firstOrNull()?.let { onMain { link.revoke(it.device.deviceId) } }
                "stop" -> running = false
            }
        }
        Thread.sleep(250)
    }
    link.stop()
    engine.close()
    thread.shutdownNow()
    out("STOPPED")
    kotlin.system.exitProcess(0)
}

