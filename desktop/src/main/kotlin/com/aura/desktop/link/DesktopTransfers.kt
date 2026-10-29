package com.aura.desktop.link

import com.aura.music.core.model.Track
import com.aura.music.link.AuthenticatedLink
import com.aura.music.link.ChannelHello
import com.aura.music.link.FileNames
import com.aura.music.link.LinkMessage
import com.aura.music.link.LinkPermissions
import com.aura.music.link.LinkSession
import com.aura.music.link.StagingSink
import com.aura.music.link.TransferAbortedException
import com.aura.music.link.TransferChannel
import com.aura.music.link.TransferFile
import com.aura.music.link.TransferKind
import com.aura.music.link.TransferOffer
import com.aura.music.link.TransferRefusal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class TransferStatus { Preparing, WaitingAnswer, WaitingConnection, Running, Done, Failed, Cancelled, Refused }

data class TransferJob(
    val id: String,
    val deviceId: String,
    val peerName: String,
    val outgoing: Boolean,
    val offer: TransferOffer,
    val status: TransferStatus,
    val doneBytes: Long = 0,
    val error: String? = null,
    /** Files that arrived (incoming) or were already there (duplicates). */
    val published: List<String> = emptyList(),
) {
    val totalBytes: Long get() = offer.totalBytes
    val active: Boolean get() = status in setOf(TransferStatus.Preparing, TransferStatus.WaitingAnswer, TransferStatus.WaitingConnection, TransferStatus.Running)
}

/** Files a linked device wants to send here, waiting for the user's answer. */
data class IncomingOffer(val deviceId: String, val peerName: String, val offer: TransferOffer)

/**
 * File transfers on the computer. Outgoing: the user picks songs, the phone accepts and dials a data
 * connection to fetch them. Incoming: the phone offers, the user (or a standing permission)
 * accepts, the phone dials and sends. Received files land in the user's chosen folder, which is part
 * of the library, only after their SHA-256 matched.
 */
class DesktopTransfers(
    private val scope: CoroutineScope,
    private val staging: Path,
    private val receiveDir: () -> Path,
    private val permissions: (String) -> LinkPermissions?,
    private val onReceived: (List<Path>) -> Unit,
) {
    private val _jobs = MutableStateFlow<List<TransferJob>>(emptyList())
    val jobs: StateFlow<List<TransferJob>> = _jobs.asStateFlow()

    private val _incoming = MutableStateFlow<IncomingOffer?>(null)
    val incoming: StateFlow<IncomingOffer?> = _incoming.asStateFlow()

    private val sources = ConcurrentHashMap<String, List<Path>>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private var answer: CompletableDeferred<Pair<Boolean, Boolean>>? = null

    // ---------------------------------------------------------------- outgoing

    /** Offers [tracks] (their files) to the device behind [session]. */
    fun send(session: LinkSession, tracks: List<Track>) {
        val id = "t-" + UUID.randomUUID().toString()
        val paths = tracks.map { Path.of(it.uri) }.filter(Files::isRegularFile).distinct()
        if (paths.isEmpty()) return
        val placeholder = TransferOffer(id, paths.map { TransferFile(it.fileName.toString(), Files.size(it), "0".repeat(64)) })
        add(TransferJob(id, session.peer.deviceId, session.peer.name, outgoing = true, offer = placeholder, status = TransferStatus.Preparing))
        scope.launch(Dispatchers.IO) {
            val byPath = tracks.associateBy { Path.of(it.uri) }
            val files = paths.map { p ->
                val t = byPath[p]
                TransferFile(
                    name = p.fileName.toString(),
                    sizeBytes = Files.size(p),
                    sha256 = Files.newInputStream(p).use(TransferChannel::sha256),
                    kind = TransferKind.Audio,
                    title = t?.title, artist = t?.artist, album = t?.album, durationMs = t?.durationMs ?: 0,
                )
            }
            val offer = TransferOffer(id, files)
            sources[id] = paths
            update(id) { it.copy(offer = offer, status = TransferStatus.WaitingAnswer) }
            if (id in cancelled) return@launch
            runCatching { session.send(LinkMessage.TransferOfferMessage(offer)) }
                .onFailure { update(id) { j -> j.copy(status = TransferStatus.Failed, error = "O aparelho desconectou") } }
        }
    }

    /** Offers a failed outgoing transfer again; the phone resumes what it already received. */
    fun retry(id: String, session: LinkSession?) {
        val job = _jobs.value.firstOrNull { it.id == id && it.outgoing } ?: return
        if (session == null || sources[id] == null) return
        cancelled -= id
        update(id) { it.copy(status = TransferStatus.WaitingAnswer, error = null) }
        scope.launch(Dispatchers.IO) { runCatching { session.send(LinkMessage.TransferOfferMessage(job.offer)) } }
    }

    fun cancel(id: String, session: LinkSession?) {
        cancelled += id
        update(id) { if (it.active) it.copy(status = TransferStatus.Cancelled) else it }
        scope.launch(Dispatchers.IO) { session?.let { s -> runCatching { s.send(LinkMessage.TransferCancel(id)) } } }
        if (_incoming.value?.offer?.id == id) answerIncoming(false, false)
    }

    fun clearFinished() = _jobs.update { list -> list.filter { it.active } }

    // ---------------------------------------------------------------- control messages

    fun onControl(session: LinkSession, message: LinkMessage) {
        val deviceId = session.peer.deviceId
        when (message) {
            is LinkMessage.TransferOfferMessage -> scope.launch(Dispatchers.IO) { onOffer(session, message.offer) }
            is LinkMessage.TransferAnswer -> update(message.id) { job ->
                if (job.deviceId != deviceId || !job.outgoing) job
                else if (message.accepted) job.copy(status = TransferStatus.WaitingConnection)
                else job.copy(status = TransferStatus.Refused, error = refusalText(message.refusal))
            }
            is LinkMessage.TransferCancel -> {
                cancelled += message.id
                update(message.id) { if (it.deviceId == deviceId && it.active) it.copy(status = TransferStatus.Cancelled, error = "Cancelado no outro aparelho") else it }
                if (_incoming.value?.offer?.id == message.id) answerIncoming(false, false)
            }
            else -> Unit
        }
    }

    private fun onOffer(session: LinkSession, offer: TransferOffer) {
        val deviceId = session.peer.deviceId
        fun refuse(reason: TransferRefusal) = runCatching { session.send(LinkMessage.TransferAnswer(offer.id, false, reason)) }
        if (runCatching { offer.validate() }.isFailure) return run { refuse(TransferRefusal.Invalid) }
        val permission = permissions(deviceId) ?: return run { refuse(TransferRefusal.NotAllowed) }
        val dir = receiveDir()
        Files.createDirectories(dir)
        if (Files.getFileStore(dir).usableSpace < offer.totalBytes + SPACE_MARGIN) return run { refuse(TransferRefusal.NoSpace) }
        // A retry of a transfer we already accepted resumes without asking again.
        val known = _jobs.value.firstOrNull { it.id == offer.id && !it.outgoing && it.deviceId == deviceId }
        val accepted = when {
            known != null -> true
            permission.receiveFilesWithoutAsking -> true
            else -> askUser(IncomingOffer(deviceId, session.peer.name, offer))
        }
        if (!accepted) return run { refuse(TransferRefusal.Declined) }
        if (known == null) add(TransferJob(offer.id, deviceId, session.peer.name, outgoing = false, offer = offer, status = TransferStatus.WaitingConnection))
        else update(offer.id) { it.copy(status = TransferStatus.WaitingConnection, error = null) }
        cancelled -= offer.id
        runCatching { session.send(LinkMessage.TransferAnswer(offer.id, true)) }
    }

    private fun askUser(offer: IncomingOffer): Boolean {
        val deferred = CompletableDeferred<Pair<Boolean, Boolean>>()
        answer = deferred
        _incoming.value = offer
        val (accept, always) = runBlocking { withTimeoutOrNull(ANSWER_TIMEOUT_MS) { deferred.await() } } ?: (false to false)
        _incoming.value = null
        answer = null
        if (accept && always) alwaysAccept(offer.deviceId)
        return accept
    }

    /** Set by the service: turns on "accept without asking" for a device. */
    var alwaysAccept: (String) -> Unit = {}

    fun answerIncoming(accept: Boolean, always: Boolean) {
        answer?.complete(accept to always)
    }

    // ---------------------------------------------------------------- data connections (phones dial)

    /** Runs a transfer on a data connection the phone opened. Blocking; called on the connection thread. */
    fun onData(link: AuthenticatedLink, hello: ChannelHello.Data) {
        val job = _jobs.value.firstOrNull { it.id == hello.transferId && it.deviceId == link.peer.deviceId } ?: return
        // The dialer says whether it sends; that must match the job's direction.
        if (hello.senderIsDialer == !job.outgoing) {
            if (job.outgoing) sendFiles(link, job) else receiveFiles(link, job)
        }
    }

    private fun sendFiles(link: AuthenticatedLink, job: TransferJob) {
        val paths = sources[job.id] ?: return
        update(job.id) { it.copy(status = TransferStatus.Running, error = null) }
        try {
            TransferChannel.send(link, job.offer, { i, offset -> Files.newInputStream(paths[i]).apply { skipNBytes(offset) } }, { p ->
                update(job.id) { it.copy(doneBytes = p.doneBytes) }
            }, { job.id in cancelled })
            update(job.id) { it.copy(status = TransferStatus.Done, doneBytes = it.totalBytes) }
        } catch (e: TransferAbortedException) {
            update(job.id) { if (it.status == TransferStatus.Cancelled) it else it.copy(status = TransferStatus.Failed, error = abortText(e.reason)) }
        } catch (e: Exception) {
            update(job.id) { if (it.status == TransferStatus.Cancelled) it else it.copy(status = TransferStatus.Failed, error = "A conexão caiu. Dá para tentar de novo: continua de onde parou.") }
        }
    }

    private fun receiveFiles(link: AuthenticatedLink, job: TransferJob) {
        val dir = receiveDir()
        Files.createDirectories(dir)
        val published = mutableListOf<Path>()
        val sink = StagingSink(staging.toFile(), job.offer) { index, verified ->
            val path = publish(dir, job.offer.files[index], verified)
            if (path == null) {
                "Não foi possível salvar o arquivo"
            } else {
                published.add(path)
                null
            }
        }
        update(job.id) { it.copy(status = TransferStatus.Running, error = null) }
        try {
            TransferChannel.receive(link, job.offer, sink, { p -> update(job.id) { it.copy(doneBytes = p.doneBytes) } }, { job.id in cancelled })
            sink.cleanup()
            update(job.id) { it.copy(status = TransferStatus.Done, doneBytes = it.totalBytes, published = published.map(Path::toString)) }
            onReceived(published)
        } catch (e: TransferAbortedException) {
            if (job.id in cancelled) sink.cleanup()
            update(job.id) { if (it.status == TransferStatus.Cancelled) it else it.copy(status = TransferStatus.Failed, error = abortText(e.reason)) }
            if (published.isNotEmpty()) onReceived(published)
        } catch (e: Exception) {
            update(job.id) { if (it.status == TransferStatus.Cancelled) it else it.copy(status = TransferStatus.Failed, error = "A conexão caiu. O aparelho pode tentar de novo e continua de onde parou.") }
            if (published.isNotEmpty()) onReceived(published)
        }
    }

    /**
     * Puts a verified file in [dir] under a safe name, never overwriting: an identical file already
     * there counts as received; a different one with the same name gets "(2)", "(3)"…
     */
    private fun publish(dir: Path, file: TransferFile, verified: File): Path? {
        val name = FileNames.sanitize(file.name).ifEmpty { "musica" }
        var n = 1
        while (true) {
            val candidate = dir.resolve(if (n == 1) name else FileNames.numbered(name, n))
            if (!candidate.normalize().startsWith(dir.normalize())) return null
            if (!Files.exists(candidate)) {
                val temp = dir.resolve(".aura-" + UUID.randomUUID() + ".tmp")
                return try {
                    Files.copy(verified.toPath(), temp)
                    Files.move(temp, candidate, StandardCopyOption.ATOMIC_MOVE)
                    verified.delete()
                    candidate
                } catch (e: Exception) {
                    Files.deleteIfExists(temp)
                    null
                }
            }
            if (Files.size(candidate) == file.sizeBytes && Files.newInputStream(candidate).use(TransferChannel::sha256) == file.sha256) {
                verified.delete()
                return candidate // the same song is already here
            }
            n++
        }
    }

    private fun add(job: TransferJob) = _jobs.update { listOf(job) + it.filterNot { j -> j.id == job.id } }

    private fun update(id: String, transform: (TransferJob) -> TransferJob) = _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }

    companion object {
        private const val SPACE_MARGIN = 64L * 1024 * 1024
        private const val ANSWER_TIMEOUT_MS = 120_000L

        fun refusalText(r: TransferRefusal?) = when (r) {
            TransferRefusal.Declined -> "Recusado no outro aparelho"
            TransferRefusal.NotAllowed -> "O outro aparelho não aceita arquivos deste"
            TransferRefusal.NoSpace -> "Sem espaço no outro aparelho"
            TransferRefusal.TooLarge -> "Arquivo grande demais"
            TransferRefusal.Invalid -> "Oferta inválida"
            TransferRefusal.Busy, null -> "O outro aparelho está ocupado"
        }

        fun abortText(reason: String) = if (reason == "cancelled") "Cancelado" else reason
    }
}
