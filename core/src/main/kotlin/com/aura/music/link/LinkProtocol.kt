package com.aura.music.link

import com.aura.music.link.crypto.Bytes
import com.aura.music.link.crypto.DeviceIdentity
import com.aura.music.link.crypto.Ephemeral
import com.aura.music.link.crypto.FrameCipher
import com.aura.music.link.crypto.LinkSecurityException
import com.aura.music.link.crypto.Spake2
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * AURA Link: the private connection between a person's own devices (a phone and a computer), for
 * remote control and, later, file transfer. Separate from AURA Together: a jam invite never grants
 * any of this, and pairing never joins a jam.
 */
object LinkProtocol {
    /** Bumped on incompatible changes; peers also exchange capability lists for optional features. */
    const val VERSION = 1

    /** mDNS/DNS-SD service type the receiving side advertises on the local network. */
    const val SERVICE_TYPE = "_auralink._tcp"

    const val MAX_FRAME = 256 * 1024

    /** Default TCP port a computer listens on (another one is used if it's taken). */
    const val DEFAULT_PORT = 47_820

    /**
     * Short public id announced over mDNS, derived from the device id, so paired devices recognise
     * each other without broadcasting the id itself.
     */
    fun shortId(deviceId: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "t"
    }
}

enum class Platform { Android, Windows, MacOS, Linux }

/** What this device lets one paired device do. Chosen by the user per device. */
@Serializable
data class LinkPermissions(
    /** Play/pause, skip, seek, volume, shuffle/repeat and queue changes. */
    val controlPlayback: Boolean = true,
    /** Search this device's library and start songs from it. */
    val browseLibrary: Boolean = true,
    /** Accept files sent by that device without asking each time (off: ask every time). */
    val receiveFilesWithoutAsking: Boolean = false,
)

/** A device this one is paired with, as stored locally. */
@Serializable
data class PairedDevice(
    val deviceId: String,
    /** Name the user gave it here (starts as the name the device announced). */
    val name: String,
    val platform: Platform,
    /** The peer's identity key (X.509); reconnections must prove possession of it. */
    val publicKey: String,
    val pairedAt: Long,
    /** What that device may do to this one. */
    val permissions: LinkPermissions = LinkPermissions(),
    /** Last address it was reached at (host:port), only a hint; identity is the key. */
    val lastAddress: String? = null,
    val lastSeen: Long = 0,
) {
    val publicKeyBytes: ByteArray get() = Base64.getDecoder().decode(publicKey)
    val fingerprint: String get() = DeviceIdentity.fingerprint(publicKeyBytes)
}

/** This device as it introduces itself. */
data class LocalDevice(
    val deviceId: String,
    val name: String,
    val platform: Platform,
    val identity: DeviceIdentity,
    val capabilities: List<String>,
)

// ---------------------------------------------------------------- framing

/** Length-prefixed frames over a byte stream (TCP). */
class FrameStream(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)
    private var pushedBack: ByteArray? = null

    /** Makes [frame] the next one [read] returns (to peek at the first message). */
    fun pushBack(frame: ByteArray) {
        pushedBack = frame
    }

    fun read(): ByteArray {
        pushedBack?.let { pushedBack = null; return it }
        val size = try {
            input.readInt()
        } catch (e: EOFException) {
            throw IOException("Connection closed")
        }
        if (size !in 1..LinkProtocol.MAX_FRAME) throw LinkSecurityException("Bad frame size $size")
        return ByteArray(size).also(input::readFully)
    }

    @Synchronized
    fun write(bytes: ByteArray) {
        require(bytes.size <= LinkProtocol.MAX_FRAME) { "Frame too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }
}

// ---------------------------------------------------------------- handshake messages

@Serializable
sealed interface HandshakeMessage {
    /** [method] tells the responder which secret the initiator used: the QR code's or the typed code. */
    @Serializable @SerialName("pairStart") data class PairStart(val version: Int, val method: PairMethod, val share: String) : HandshakeMessage
    @Serializable @SerialName("pairReply") data class PairReply(val share: String, val confirmation: String) : HandshakeMessage
    @Serializable @SerialName("pairConfirm") data class PairConfirm(val confirmation: String) : HandshakeMessage

    /** Sent encrypted after SPAKE2: who I am, with a signature binding my key to this pairing. */
    @Serializable @SerialName("identity") data class Identity(
        val deviceId: String,
        val name: String,
        val platform: Platform,
        val publicKey: String,
        val signature: String,
    ) : HandshakeMessage

    @Serializable @SerialName("decision") data class Decision(val accepted: Boolean) : HandshakeMessage

    @Serializable @SerialName("hello") data class Hello(val version: Int, val deviceId: String, val ephemeral: String, val nonce: String) : HandshakeMessage
    @Serializable @SerialName("helloReply") data class HelloReply(val deviceId: String, val ephemeral: String, val nonce: String, val signature: String) : HandshakeMessage
    @Serializable @SerialName("finish") data class Finish(val signature: String) : HandshakeMessage
    @Serializable @SerialName("reject") data class Reject(val reason: RejectReason) : HandshakeMessage
}

@Serializable
enum class PairMethod { Qr, Code }

enum class RejectReason { UnknownDevice, BadVersion, WrongCode, Expired, TooManyAttempts, Declined, Busy, Paused }

class LinkRejectedException(val reason: RejectReason) : IOException("Rejected: $reason")

private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
private fun unb64(text: String): ByteArray = try {
    Base64.getDecoder().decode(text)
} catch (e: IllegalArgumentException) {
    throw LinkSecurityException("Bad base64")
}

private fun FrameStream.send(message: HandshakeMessage) = write(LinkProtocol.json.encodeToString(HandshakeMessage.serializer(), message).toByteArray())

private inline fun <reified T : HandshakeMessage> FrameStream.expect(cipher: FrameCipher? = null): T {
    val raw = read().let { if (cipher != null) cipher.open(it) else it }
    val message = try {
        LinkProtocol.json.decodeFromString(HandshakeMessage.serializer(), String(raw, Charsets.UTF_8))
    } catch (e: Exception) {
        throw LinkSecurityException("Malformed handshake")
    }
    if (message is HandshakeMessage.Reject) throw LinkRejectedException(message.reason)
    return message as? T ?: throw LinkSecurityException("Unexpected ${message::class.simpleName}")
}

private fun FrameStream.sendSealed(cipher: FrameCipher, message: HandshakeMessage) =
    write(cipher.seal(LinkProtocol.json.encodeToString(HandshakeMessage.serializer(), message).toByteArray()))

// ---------------------------------------------------------------- pairing

/** What the user sees before agreeing to pair: the other device and a code to compare. */
data class PairingOffer(val deviceId: String, val name: String, val platform: Platform, val fingerprint: String, val verificationCode: String)

/**
 * First pairing. Both devices run SPAKE2 with the secret from the QR code or the typed code, then
 * swap identity keys (each signed over this pairing's transcript), show the same six-digit
 * verification code, and only pair if the user accepts on both.
 */
object Pairing {

    /** The device that scanned the QR code ([PairMethod.Qr]) or typed the code ([PairMethod.Code]). */
    fun initiate(stream: FrameStream, method: PairMethod, secret: ByteArray, me: LocalDevice, decide: (PairingOffer) -> Boolean): PairedDevice {
        val spake = Spake2(Spake2.Role.A, secret)
        stream.send(HandshakeMessage.PairStart(LinkProtocol.VERSION, method, b64(spake.share)))
        val reply = stream.expect<HandshakeMessage.PairReply>()
        val result = spake.finish(unb64(reply.share))
        if (!Bytes.equal(result.expectedPeerConfirmation, unb64(reply.confirmation))) throw LinkRejectedException(RejectReason.WrongCode)
        stream.send(HandshakeMessage.PairConfirm(b64(result.myConfirmation)))
        return exchangeIdentities(stream, result, me, initiator = true, decide)
    }

    /**
     * The device showing the code. [onWrongCode] is called when the peer used a wrong code, so the
     * caller can count attempts and close the pairing window.
     */
    fun respond(
        stream: FrameStream,
        secretFor: (PairMethod) -> ByteArray?,
        me: LocalDevice,
        onWrongCode: () -> Unit,
        decide: (PairingOffer) -> Boolean,
    ): PairedDevice {
        val start = stream.expect<HandshakeMessage.PairStart>()
        if (start.version != LinkProtocol.VERSION) {
            stream.send(HandshakeMessage.Reject(RejectReason.BadVersion))
            throw LinkRejectedException(RejectReason.BadVersion)
        }
        val secret = secretFor(start.method) ?: run {
            stream.send(HandshakeMessage.Reject(RejectReason.Expired))
            throw LinkRejectedException(RejectReason.Expired)
        }
        val spake = Spake2(Spake2.Role.B, secret)
        val result = spake.finish(unb64(start.share))
        stream.send(HandshakeMessage.PairReply(b64(spake.share), b64(result.myConfirmation)))
        val confirm = try {
            stream.expect<HandshakeMessage.PairConfirm>()
        } catch (e: IOException) {
            // The initiator hangs up when our confirmation doesn't match its code: a wrong code.
            onWrongCode()
            throw LinkRejectedException(RejectReason.WrongCode)
        }
        if (!Bytes.equal(result.expectedPeerConfirmation, unb64(confirm.confirmation))) {
            onWrongCode()
            throw LinkRejectedException(RejectReason.WrongCode)
        }
        return exchangeIdentities(stream, result, me, initiator = false, decide)
    }

    private fun exchangeIdentities(
        stream: FrameStream,
        result: Spake2.Result,
        me: LocalDevice,
        initiator: Boolean,
        decide: (PairingOffer) -> Boolean,
    ): PairedDevice {
        val cipher = FrameCipher.derive(result.sharedKey, result.transcriptHash, initiator)
        val role = if (initiator) "A" else "B"
        val signed = Bytes.sha256("aura-link pairing identity $role".toByteArray(), result.transcriptHash)
        stream.sendSealed(
            cipher,
            HandshakeMessage.Identity(me.deviceId, me.name.take(60), me.platform, b64(me.identity.publicKey), b64(me.identity.sign(signed))),
        )
        val peer = stream.expect<HandshakeMessage.Identity>(cipher)
        val peerRole = if (initiator) "B" else "A"
        val peerKey = unb64(peer.publicKey)
        val peerSigned = Bytes.sha256("aura-link pairing identity $peerRole".toByteArray(), result.transcriptHash)
        if (!DeviceIdentity.verify(peerKey, peerSigned, unb64(peer.signature))) throw LinkSecurityException("Identity signature")
        if (peer.deviceId == me.deviceId) throw LinkSecurityException("Same device")

        val sas = Bytes.hkdf(result.sharedKey, result.transcriptHash, "aura-link verification code".toByteArray(), 4)
        val code = ((sas[0].toLong() and 0xFF shl 24) or (sas[1].toLong() and 0xFF shl 16) or (sas[2].toLong() and 0xFF shl 8) or (sas[3].toLong() and 0xFF)) % 1_000_000
        val offer = PairingOffer(peer.deviceId, peer.name.take(60), peer.platform, DeviceIdentity.fingerprint(peerKey), "%06d".format(code))

        val accepted = decide(offer)
        stream.sendSealed(cipher, HandshakeMessage.Decision(accepted))
        val theirs = stream.expect<HandshakeMessage.Decision>(cipher)
        if (!accepted || !theirs.accepted) throw LinkRejectedException(RejectReason.Declined)
        return PairedDevice(
            deviceId = peer.deviceId,
            name = offer.name,
            platform = peer.platform,
            publicKey = peer.publicKey,
            pairedAt = System.currentTimeMillis(),
        )
    }

    /** A fresh secret for a QR code (256 bits). */
    fun newQrSecret(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(32).also(random::nextBytes)

    /** An 8-digit code to type, shown as "1234-5678". */
    fun newCode(random: SecureRandom = SecureRandom()): String = "%08d".format(random.nextInt(100_000_000))

    /** Peeks at the first message of an incoming connection: true for a pairing attempt. */
    fun isPairingAttempt(stream: FrameStream): Boolean {
        val first = stream.read()
        stream.pushBack(first)
        return runCatching { LinkProtocol.json.decodeFromString(HandshakeMessage.serializer(), String(first, Charsets.UTF_8)) }
            .getOrNull() is HandshakeMessage.PairStart
    }

    /** The SPAKE2 password for a typed code (spaces and dashes ignored). */
    fun codeSecret(code: String): ByteArray = code.filter(Char::isDigit).toByteArray()
}

// ---------------------------------------------------------------- reconnection

/** Keys and peer of an authenticated connection. */
class AuthenticatedLink(val peer: PairedDevice, val cipher: FrameCipher, val stream: FrameStream)

/**
 * Reconnection between already paired devices: ephemeral ECDH for fresh keys, each side signing
 * the transcript with its identity key, verified against the key stored at pairing time. A
 * device that was unpaired (revoked) on the other side is refused right away.
 */
object Handshake {

    fun connect(stream: FrameStream, me: LocalDevice, peer: PairedDevice, random: SecureRandom = SecureRandom()): AuthenticatedLink {
        val eph = Ephemeral()
        val nonce = ByteArray(16).also(random::nextBytes)
        val hello = HandshakeMessage.Hello(LinkProtocol.VERSION, me.deviceId, b64(eph.publicKey), b64(nonce))
        stream.send(hello)
        val reply = stream.expect<HandshakeMessage.HelloReply>()
        if (reply.deviceId != peer.deviceId) throw LinkSecurityException("Different device answered")
        val th = transcript(hello, reply)
        if (!DeviceIdentity.verify(peer.publicKeyBytes, Bytes.sha256("R".toByteArray(), th), unb64(reply.signature))) {
            throw LinkSecurityException("Peer signature")
        }
        stream.send(HandshakeMessage.Finish(b64(me.identity.sign(Bytes.sha256("I".toByteArray(), th)))))
        val cipher = FrameCipher.derive(eph.agree(unb64(reply.ephemeral)), th, initiator = true)
        // The responder answers with an encrypted Decision once it verified us.
        stream.expect<HandshakeMessage.Decision>(cipher).takeIf { it.accepted } ?: throw LinkRejectedException(RejectReason.Declined)
        return AuthenticatedLink(peer, cipher, stream)
    }

    fun accept(stream: FrameStream, me: LocalDevice, lookup: (String) -> PairedDevice?, random: SecureRandom = SecureRandom()): AuthenticatedLink {
        val hello = stream.expect<HandshakeMessage.Hello>()
        if (hello.version != LinkProtocol.VERSION) {
            stream.send(HandshakeMessage.Reject(RejectReason.BadVersion))
            throw LinkRejectedException(RejectReason.BadVersion)
        }
        val peer = lookup(hello.deviceId) ?: run {
            stream.send(HandshakeMessage.Reject(RejectReason.UnknownDevice))
            throw LinkRejectedException(RejectReason.UnknownDevice)
        }
        val eph = Ephemeral()
        val nonce = ByteArray(16).also(random::nextBytes)
        val unsigned = HandshakeMessage.HelloReply(me.deviceId, b64(eph.publicKey), b64(nonce), "")
        val th = transcript(hello, unsigned)
        stream.send(unsigned.copy(signature = b64(me.identity.sign(Bytes.sha256("R".toByteArray(), th)))))
        val finish = stream.expect<HandshakeMessage.Finish>()
        if (!DeviceIdentity.verify(peer.publicKeyBytes, Bytes.sha256("I".toByteArray(), th), unb64(finish.signature))) {
            throw LinkSecurityException("Peer signature")
        }
        val cipher = FrameCipher.derive(eph.agree(unb64(hello.ephemeral)), th, initiator = false)
        stream.sendSealed(cipher, HandshakeMessage.Decision(true))
        return AuthenticatedLink(peer, cipher, stream)
    }

    private fun transcript(hello: HandshakeMessage.Hello, reply: HandshakeMessage.HelloReply): ByteArray = Bytes.sha256(
        Bytes.transcript(
            "aura-link reconnect v1".toByteArray(),
            hello.deviceId.toByteArray(), unb64(hello.ephemeral), unb64(hello.nonce),
            reply.deviceId.toByteArray(), unb64(reply.ephemeral), unb64(reply.nonce),
        ),
    )
}
