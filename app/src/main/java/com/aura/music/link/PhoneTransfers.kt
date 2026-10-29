package com.aura.music.link

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.aura.music.core.model.Track
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

enum class PhoneTransferStatus { Preparing, WaitingAnswer, Running, Done, Failed, Cancelled, Refused }

data class PhoneTransfer(
    val id: String,
    val deviceId: String,
    val peerName: String,
    val outgoing: Boolean,
    val offer: TransferOffer,
    val status: PhoneTransferStatus,
    val doneBytes: Long = 0,
    val error: String? = null,
) {
    val active: Boolean get() = status == PhoneTransferStatus.Preparing || status == PhoneTransferStatus.WaitingAnswer || status == PhoneTransferStatus.Running
}

data class PhoneIncomingOffer(val deviceId: String, val peerName: String, val offer: TransferOffer)

/**
 * File transfers on the phone. Received songs are copied into `Music/AURA` through MediaStore (no
 * broad storage access, nothing overwritten) once their SHA-256 matched; sent songs are read
 * through the ContentResolver. The phone dials every data connection, whichever way files go.
 */
class PhoneTransfers(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: LinkStore,
    private val sessionFor: (String) -> LinkSession?,
    private val dial: (PairedDevice) -> Pair<AuthenticatedLink, Socket>?,
    private val onReceived: () -> Unit,
) {
    private val _jobs = MutableStateFlow<List<PhoneTransfer>>(emptyList())
    val jobs: StateFlow<List<PhoneTransfer>> = _jobs.asStateFlow()

    private val _incoming = MutableStateFlow<PhoneIncomingOffer?>(null)
    val incoming: StateFlow<PhoneIncomingOffer?> = _incoming.asStateFlow()

    private val sources = ConcurrentHashMap<String, List<Uri>>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private var answer: CompletableDeferred<Pair<Boolean, Boolean>>? = null
    private val staging = File(context.cacheDir, "link-transfers")

    // ---------------------------------------------------------------- outgoing (phone → computer)

    fun send(deviceId: String, tracks: List<Track>) {
        val session = sessionFor(deviceId) ?: return
        val id = "t-" + UUID.randomUUID().toString()
        val placeholder = TransferOffer(id, tracks.map { TransferFile(displayName(it), it.sizeBytes, "0".repeat(64)) })
        add(PhoneTransfer(id, deviceId, session.peer.name, outgoing = true, offer = placeholder, status = PhoneTransferStatus.Preparing))
        scope.launch(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val uris = tracks.map { Uri.parse(it.uri) }
                val files = tracks.mapIndexed { i, t ->
                    val hash = resolver.openInputStream(uris[i])?.use(TransferChannel::sha256) ?: error("unreadable")
                    TransferFile(displayName(t), t.sizeBytes, hash, TransferKind.Audio, t.title, t.artist, t.album, t.durationMs)
                }
                val offer = TransferOffer(id, files)
                sources[id] = uris
                update(id) { it.copy(offer = offer, status = PhoneTransferStatus.WaitingAnswer) }
                session.send(LinkMessage.TransferOfferMessage(offer))
            } catch (e: Exception) {
                update(id) { it.copy(status = PhoneTransferStatus.Failed, error = "Não foi possível ler as músicas para enviar") }
            }
        }
    }

    fun retry(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id && it.outgoing } ?: return
        val session = sessionFor(job.deviceId) ?: return
        cancelled -= id
        update(id) { it.copy(status = PhoneTransferStatus.WaitingAnswer, error = null) }
        scope.launch(Dispatchers.IO) { runCatching { session.send(LinkMessage.TransferOfferMessage(job.offer)) } }
    }

    fun cancel(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        cancelled += id
        update(id) { if (it.active) it.copy(status = PhoneTransferStatus.Cancelled) else it }
        scope.launch(Dispatchers.IO) { sessionFor(job.deviceId)?.let { s -> runCatching { s.send(LinkMessage.TransferCancel(id)) } } }
        if (_incoming.value?.offer?.id == id) answerIncoming(false, false)
    }

    fun clearFinished() = _jobs.update { list -> list.filter { it.active } }

    // ---------------------------------------------------------------- control messages

    fun onControl(session: LinkSession, message: LinkMessage) {
        val deviceId = session.peer.deviceId
        when (message) {
            is LinkMessage.TransferOfferMessage -> scope.launch(Dispatchers.IO) { onOffer(session, message.offer) }
            is LinkMessage.TransferAnswer -> {
                val job = _jobs.value.firstOrNull { it.id == message.id && it.outgoing && it.deviceId == deviceId } ?: return
                if (message.accepted) scope.launch(Dispatchers.IO) { pushFiles(job) }
                else update(job.id) { it.copy(status = PhoneTransferStatus.Refused, error = refusalText(message.refusal)) }
            }
            is LinkMessage.TransferCancel -> {
                cancelled += message.id
                update(message.id) { if (it.deviceId == deviceId && it.active) it.copy(status = PhoneTransferStatus.Cancelled, error = "Cancelado no computador") else it }
                if (_incoming.value?.offer?.id == message.id) answerIncoming(false, false)
            }
            else -> Unit
        }
    }

    private fun onOffer(session: LinkSession, offer: TransferOffer) {
        val deviceId = session.peer.deviceId
        fun refuse(reason: TransferRefusal) = runCatching { session.send(LinkMessage.TransferAnswer(offer.id, false, reason)) }
        if (runCatching { offer.validate() }.isFailure || offer.files.any { it.kind != TransferKind.Audio }) return run { refuse(TransferRefusal.Invalid) }
        val device = store.devices().firstOrNull { it.deviceId == deviceId } ?: return run { refuse(TransferRefusal.NotAllowed) }
        val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0L)
        if (free < offer.totalBytes * 2 + SPACE_MARGIN) return run { refuse(TransferRefusal.NoSpace) } // staging + final copy
        val known = _jobs.value.any { it.id == offer.id && !it.outgoing }
        val accepted = known || device.permissions.receiveFilesWithoutAsking || askUser(PhoneIncomingOffer(deviceId, session.peer.name, offer))
        if (!accepted) return run { refuse(TransferRefusal.Declined) }
        if (!known) add(PhoneTransfer(offer.id, deviceId, session.peer.name, outgoing = false, offer = offer, status = PhoneTransferStatus.Running))
        cancelled -= offer.id
        runCatching { session.send(LinkMessage.TransferAnswer(offer.id, true)) }
        pullFiles(device, offer)
    }

    private fun askUser(offer: PhoneIncomingOffer): Boolean {
        val deferred = CompletableDeferred<Pair<Boolean, Boolean>>()
        answer = deferred
        _incoming.value = offer
        val (accept, always) = runBlocking { withTimeoutOrNull(ANSWER_TIMEOUT_MS) { deferred.await() } } ?: (false to false)
        _incoming.value = null
        answer = null
        if (accept && always) store.update(offer.deviceId) { it.copy(permissions = it.permissions.copy(receiveFilesWithoutAsking = true)) }
        return accept
    }

    fun answerIncoming(accept: Boolean, always: Boolean) {
        answer?.complete(accept to always)
    }

    // ---------------------------------------------------------------- data connections

    /** Computer → phone: dial, then receive into staging and publish to MediaStore. */
    private fun pullFiles(device: PairedDevice, offer: TransferOffer) {
        val opened = dial(device) ?: run {
            update(offer.id) { it.copy(status = PhoneTransferStatus.Failed, error = "Não foi possível conectar ao computador para receber") }
            return
        }
        val (link, socket) = opened
        val sink = StagingSink(staging, offer) { index, verified -> publish(offer.files[index], verified) }
        update(offer.id) { it.copy(status = PhoneTransferStatus.Running, error = null) }
        try {
            Channels.announce(link, ChannelHello.Data(offer.id, senderIsDialer = false))
            TransferChannel.receive(link, offer, sink, { p -> update(offer.id) { it.copy(doneBytes = p.doneBytes) } }, { offer.id in cancelled })
            sink.cleanup()
            update(offer.id) { it.copy(status = PhoneTransferStatus.Done, doneBytes = it.offer.totalBytes) }
        } catch (e: TransferAbortedException) {
            if (offer.id in cancelled) sink.cleanup()
            update(offer.id) { if (it.status == PhoneTransferStatus.Cancelled) it else it.copy(status = PhoneTransferStatus.Failed, error = e.reason) }
        } catch (e: Exception) {
            update(offer.id) { if (it.status == PhoneTransferStatus.Cancelled) it else it.copy(status = PhoneTransferStatus.Failed, error = "A conexão caiu. Envie de novo pelo computador: continua de onde parou.") }
        } finally {
            runCatching { socket.close() }
            onReceived()
        }
    }

    /** Phone → computer: dial and send the files the computer accepted. */
    private fun pushFiles(job: PhoneTransfer) {
        val device = store.devices().firstOrNull { it.deviceId == job.deviceId } ?: return
        val uris = sources[job.id] ?: return
        val opened = dial(device) ?: run {
            update(job.id) { it.copy(status = PhoneTransferStatus.Failed, error = "Não foi possível conectar ao computador") }
            return
        }
        val (link, socket) = opened
        update(job.id) { it.copy(status = PhoneTransferStatus.Running, error = null) }
        try {
            Channels.announce(link, ChannelHello.Data(job.id, senderIsDialer = true))
            TransferChannel.send(link, job.offer, { i, offset ->
                val input = context.contentResolver.openInputStream(uris[i]) ?: error("unreadable")
                var skipped = 0L
                while (skipped < offset) {
                    val n = input.skip(offset - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                input
            }, { p -> update(job.id) { it.copy(doneBytes = p.doneBytes) } }, { job.id in cancelled })
            update(job.id) { it.copy(status = PhoneTransferStatus.Done, doneBytes = it.offer.totalBytes) }
        } catch (e: TransferAbortedException) {
            update(job.id) { if (it.status == PhoneTransferStatus.Cancelled) it else it.copy(status = PhoneTransferStatus.Failed, error = e.reason) }
        } catch (e: Exception) {
            update(job.id) { if (it.status == PhoneTransferStatus.Cancelled) it else it.copy(status = PhoneTransferStatus.Failed, error = "A conexão caiu. Tente de novo: continua de onde parou.") }
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Copies a verified file into Music/AURA via MediaStore. Returns an error text or null. */
    private fun publish(file: TransferFile, verified: File): String? {
        val resolver = context.contentResolver
        val name = FileNames.sanitize(file.name).ifEmpty { "musica.mp3" }
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "audio/*"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AURA")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return "Não foi possível salvar no celular"
        return try {
            resolver.openOutputStream(uri)?.use { out -> verified.inputStream().use { it.copyTo(out) } } ?: error("no output")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            verified.delete()
            null
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            "Não foi possível salvar no celular"
        }
    }

    private fun displayName(track: Track) = FileNames.sanitize(track.displayName).ifEmpty { "${track.title}.mp3" }

    private fun add(job: PhoneTransfer) = _jobs.update { listOf(job) + it.filterNot { j -> j.id == job.id } }

    private fun update(id: String, transform: (PhoneTransfer) -> PhoneTransfer) = _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }

    companion object {
        private const val SPACE_MARGIN = 128L * 1024 * 1024
        private const val ANSWER_TIMEOUT_MS = 120_000L

        fun refusalText(r: TransferRefusal?) = when (r) {
            TransferRefusal.Declined -> "Recusado no computador"
            TransferRefusal.NotAllowed -> "O computador não aceita arquivos deste celular"
            TransferRefusal.NoSpace -> "Sem espaço no computador"
            TransferRefusal.TooLarge -> "Arquivo grande demais"
            TransferRefusal.Invalid -> "Oferta inválida"
            TransferRefusal.Busy, null -> "O computador está ocupado"
        }
    }
}
