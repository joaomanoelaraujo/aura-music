package com.aura.music.link.crypto

import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A peer failed to prove it knows the code, sent a bad signature, or replayed/forged a frame. */
class LinkSecurityException(message: String) : IOException(message)

internal object Bytes {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** RFC 5869 HKDF with SHA-256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    /** Length-prefixed (8 bytes, little-endian) concatenation, as RFC 9382 builds its transcript. */
    fun transcript(vararg parts: ByteArray): ByteArray {
        val size = parts.sumOf { 8 + it.size }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        parts.forEach { buffer.putLong(it.size.toLong()).put(it) }
        return buffer.array()
    }

    fun equal(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}

/**
 * SPAKE2 (RFC 9382) over P-256 with SHA-256, HKDF and HMAC. Both devices derive the same key
 * only if they used the same password; an attacker in the middle gets one guess per attempt and
 * learns nothing that would let it test other guesses offline, so even an 8-digit code is safe
 * as long as attempts are limited.
 *
 * Role A is the device that types/scans the code; role B is the one showing it.
 */
class Spake2(private val role: Role, password: ByteArray, private val random: SecureRandom = SecureRandom()) {
    enum class Role { A, B }

    data class Result(
        /** Shared secret for the session (16 bytes). */
        val sharedKey: ByteArray,
        /** This side's confirmation, to send. */
        val myConfirmation: ByteArray,
        /** What the peer's confirmation must equal. */
        val expectedPeerConfirmation: ByteArray,
        val transcriptHash: ByteArray,
    )

    private val w: BigInteger = BigInteger(1, Bytes.sha256("AURA-Link/SPAKE2/w".toByteArray(), password)).mod(P256.n)
    private val secret: BigInteger = P256.randomScalar(random)

    /** The public share to send to the peer (uncompressed point). */
    val share: ByteArray = P256.G.multiply(secret).add((if (role == Role.A) P256.M else P256.N).multiply(w)).normalize().getEncoded(false)

    fun finish(peerShare: ByteArray): Result {
        val peer = P256.decode(peerShare)
        val k = peer.subtract((if (role == Role.A) P256.N else P256.M).multiply(w)).multiply(secret).normalize()
        if (k.isInfinity) throw LinkSecurityException("Invalid SPAKE2 share")
        val (pA, pB) = if (role == Role.A) share to peerShare else peerShare to share
        val tt = Bytes.transcript(ID_A, ID_B, pA, pB, k.getEncoded(false), P256.scalarBytes(w))
        val hash = Bytes.sha256(tt)
        val ke = hash.copyOfRange(0, 16)
        val ka = hash.copyOfRange(16, 32)
        val kc = Bytes.hkdf(ka, ByteArray(0), "ConfirmationKeys".toByteArray(), 32)
        val confirmA = Bytes.hmac(kc.copyOfRange(0, 16), tt)
        val confirmB = Bytes.hmac(kc.copyOfRange(16, 32), tt)
        return if (role == Role.A) Result(ke, confirmA, confirmB, hash) else Result(ke, confirmB, confirmA, hash)
    }

    private companion object {
        val ID_A = "aura-link-initiator".toByteArray()
        val ID_B = "aura-link-responder".toByteArray()
    }
}

internal object P256 {
    private val params = ECNamedCurveTable.getByName("P-256")
    val curve = params.curve
    val G: ECPoint = params.g
    val n: BigInteger = params.n

    // RFC 9382 constants for P-256 (points nobody knows the discrete log of).
    val M: ECPoint = curve.decodePoint(hex("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))
    val N: ECPoint = curve.decodePoint(hex("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))

    fun randomScalar(random: SecureRandom): BigInteger {
        while (true) {
            val candidate = BigInteger(n.bitLength(), random)
            if (candidate.signum() > 0 && candidate < n) return candidate
        }
    }

    fun decode(bytes: ByteArray): ECPoint {
        val point = try {
            curve.decodePoint(bytes)
        } catch (e: IllegalArgumentException) {
            throw LinkSecurityException("Malformed point")
        }
        if (point.isInfinity || !point.isValid) throw LinkSecurityException("Point not on curve")
        return point
    }

    fun scalarBytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Seals frames with AES-256-GCM. The nonce is a per-direction counter that also travels in front
 * of each frame and must be exactly the next one expected: a replayed, dropped or reordered frame
 * fails and the link is closed.
 */
class FrameCipher(sendKey: ByteArray, receiveKey: ByteArray) {
    private val send = SecretKeySpec(sendKey, "AES")
    private val receive = SecretKeySpec(receiveKey, "AES")
    private var sendCounter = 0L
    private var receiveCounter = 0L

    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        val counter = sendCounter++
        val header = ByteBuffer.allocate(8).putLong(counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, send, GCMParameterSpec(128, nonce(counter)))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    @Synchronized
    fun open(frame: ByteArray): ByteArray {
        if (frame.size < 8 + 16) throw LinkSecurityException("Frame too short")
        val counter = ByteBuffer.wrap(frame, 0, 8).long
        if (counter != receiveCounter) throw LinkSecurityException("Unexpected frame counter")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, receive, GCMParameterSpec(128, nonce(counter)))
        cipher.updateAAD(frame, 0, 8)
        val plain = try {
            cipher.doFinal(frame, 8, frame.size - 8)
        } catch (e: AEADBadTagException) {
            throw LinkSecurityException("Unauthenticated frame")
        }
        receiveCounter++
        return plain
    }

    private fun nonce(counter: Long) = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    companion object {
        /** Directional keys from a shared secret; [initiator] says which side we are. */
        fun derive(secret: ByteArray, salt: ByteArray, initiator: Boolean): FrameCipher {
            val forward = Bytes.hkdf(secret, salt, "aura-link initiator->responder".toByteArray(), 32)
            val backward = Bytes.hkdf(secret, salt, "aura-link responder->initiator".toByteArray(), 32)
            return if (initiator) FrameCipher(forward, backward) else FrameCipher(backward, forward)
        }
    }
}

/**
 * A device's long-term identity: an EC P-256 key pair. The private key never leaves the device
 * (Android keeps it in the Keystore; the desktop protects it with the OS key store).
 */
interface DeviceIdentity {
    /** X.509 SubjectPublicKeyInfo encoding. */
    val publicKey: ByteArray

    fun sign(data: ByteArray): ByteArray

    companion object {
        fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)

        /** Short, human-comparable fingerprint of a public key, e.g. "4F2A·91C0·7D3E·B815". */
        fun fingerprint(publicKey: ByteArray): String =
            Bytes.sha256(publicKey).take(8).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("·")
    }
}

/** An identity kept as plain key material, for the desktop (stored encrypted by the OS) and tests. */
class SoftwareIdentity(private val keyPair: KeyPair) : DeviceIdentity {
    override val publicKey: ByteArray = keyPair.public.encoded

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update(data)
        sign()
    }

    /** PKCS#8 private key, to be stored encrypted. */
    fun privateKeyBytes(): ByteArray = keyPair.private.encoded

    companion object {
        fun generate(): SoftwareIdentity = SoftwareIdentity(
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(),
        )

        fun restore(privateKey: ByteArray, publicKey: ByteArray): SoftwareIdentity {
            val factory = KeyFactory.getInstance("EC")
            val private: PrivateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateKey))
            val public: PublicKey = factory.generatePublic(X509EncodedKeySpec(publicKey))
            return SoftwareIdentity(KeyPair(public, private))
        }
    }
}

/** Ephemeral ECDH used for each authenticated reconnection (forward secrecy). */
internal class Ephemeral {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val publicKey: ByteArray = pair.public.encoded

    fun agree(peerPublic: ByteArray): ByteArray {
        val peer = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublic))
        } catch (e: Exception) {
            throw LinkSecurityException("Bad ephemeral key")
        }
        return KeyAgreement.getInstance("ECDH").run {
            init(pair.private)
            doPhase(peer, true)
            generateSecret()
        }
    }
}
