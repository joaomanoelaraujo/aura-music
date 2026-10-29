package com.aura.music.together.protocol

import java.io.IOException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Frames encrypted with AES-256-GCM. The key travels only inside the invite link's
 * fragment, so nobody without the invite can read or forge messages; a frame that fails
 * authentication closes the connection.
 *
 * Frame: [12-byte IV][ciphertext + 16-byte tag], with the session id (plus what [SecureLink]
 * adds: connection id, direction and counter) as associated data. The transport delimits frames
 * (a length prefix on TCP, one message per frame on the relay).
 */
class SecureChannel(key: ByteArray, private val sessionId: String) {

    init {
        require(key.size == KEY_BYTES) { "AES-256 key expected" }
    }

    private val secret = SecretKeySpec(key, "AES")
    private val random = SecureRandom()
    private val aad = sessionId.toByteArray(Charsets.UTF_8)

    fun seal(plain: ByteArray, extraAad: ByteArray = ByteArray(0)): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad + extraAad)
        return iv + cipher.doFinal(plain)
    }

    fun open(frame: ByteArray, extraAad: ByteArray = ByteArray(0)): ByteArray {
        if (frame.size < IV_BYTES + TAG_BITS / 8) throw IOException("Frame too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, frame, 0, IV_BYTES))
        cipher.updateAAD(aad + extraAad)
        return try {
            cipher.doFinal(frame, IV_BYTES, frame.size - IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw IOException("Unauthenticated frame", e)
        }
    }

    companion object {
        const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
    }
}
