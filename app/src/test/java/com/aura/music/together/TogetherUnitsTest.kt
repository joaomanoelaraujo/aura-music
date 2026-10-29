package com.aura.music.together

import com.aura.music.together.protocol.Invite
import com.aura.music.together.protocol.Message
import com.aura.music.together.protocol.SecureChannel
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.protocol.Wire
import com.aura.music.together.sync.ClockSync
import com.aura.music.together.sync.Correction
import com.aura.music.together.sync.DriftCorrector
import com.aura.music.together.sync.TrackMatcher
import com.aura.music.track
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherUnitsTest {

    @Test
    fun `invite round-trips and keeps secrets in the fragment`() {
        val invite = Invite("abc123_XYZ", "192.168.0.12", 47321, SecureChannel.newKey(), "João & cia", "Sexta à noite")
        val link = invite.toLink("https://usuario.github.io/aura/j/")
        assertTrue(link.startsWith("https://usuario.github.io/aura/j/#"))
        assertTrue("nothing sensitive before #", !link.substringBefore('#').contains("192.168"))
        assertEquals(invite, Invite.parse(link))
    }

    @Test
    fun `relay invites work with or without a local address`() {
        val key = SecureChannel.newKey()
        val both = Invite("abc123_XYZ", "192.168.0.12", 47321, key, "João", "Jam", relay = true)
        assertEquals(both, Invite.parse(both.toLink("https://x/aura/j/")))
        val internetOnly = Invite("abc123_XYZ", null, 0, key, "João", "Jam", relay = true)
        val link = internetOnly.toLink("https://x/aura/j/")
        assertTrue("no address in the link", !link.contains("h="))
        assertEquals(internetOnly, Invite.parse(link))
        assertNull(Invite.parse(link.replace("r=1", "r=0")))
    }

    @Test
    fun `song chunks travel as raw bytes, not JSON numbers`() {
        val data = ByteArray(48 * 1024) { it.toByte() }
        val chunk = Message.Chunk(TrackRef("Saudade", "Henrique", null, 200_000), 4096, 700_000, data)
        val encoded = Wire.encode(chunk)
        assertTrue("overhead ${encoded.size - data.size} bytes", encoded.size - data.size < 200)
        assertEquals(chunk, Wire.decode(encoded))
        assertEquals(Message.Leave, Wire.decode(Wire.encode(Message.Leave)))
    }

    @Test
    fun `malformed invites are rejected`() {
        assertNull(Invite.parse("https://x.github.io/aura/j/"))
        assertNull(Invite.parse("https://x/#v=1&s=abc123&h=1.2.3.4:80&k=AAAA&n=a&j=b"))
        assertNull(Invite.parse("https://x/#v=2&s=abc123&h=1.2.3.4:47321&k=AAAA"))
        assertNull(Invite.parse("https://x/#v=1&s=a b&h=1.2.3.4:47321&k=AAAA"))
    }

    @Test
    fun `tampered or foreign frames are refused`() {
        val key = SecureChannel.newKey()
        val a = SecureChannel(key, "s1")
        val frame = a.seal(Wire.encode(Message.Leave))
        assertEquals(Message.Leave, Wire.decode(a.open(frame)))
        frame[frame.size - 1] = (frame.last() + 1).toByte()
        assertThrows { a.open(frame) }
        assertThrows { SecureChannel(key, "other-session").open(a.seal(Wire.encode(Message.Leave))) }
        assertThrows { SecureChannel(SecureChannel.newKey(), "s1").open(a.seal(Wire.encode(Message.Leave))) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        }
        throw AssertionError("expected IOException")
    }

    @Test
    fun `clock sync trusts the fastest round trip`() {
        val sync = ClockSync()
        // Host is 10 s ahead. One slow, asymmetric sample and two clean ones.
        sync.add(sentLocal = 0, hostMs = 10_400, receivedLocal = 500)
        sync.add(sentLocal = 1_000, hostMs = 11_020, receivedLocal = 1_040)
        sync.add(sentLocal = 2_000, hostMs = 12_015, receivedLocal = 2_030)
        assertEquals(30, sync.rttMs)
        assertTrue(kotlin.math.abs(sync.offsetMs - 10_000) <= 5)
    }

    @Test
    fun `drift correction ignores noise, nudges with hysteresis, then seeks`() {
        val c = DriftCorrector()
        assertEquals(Correction.None, c.decide(10_050, 10_000, isPlaying = true, nowMs = 0))
        assertEquals(Correction.Nudge(0.97f), c.decide(10_120, 10_000, true, 100))
        // Keeps correcting inside the noise band until really aligned: no flip-flopping.
        assertEquals(Correction.Nudge(0.97f), c.decide(10_045, 10_000, true, 200))
        assertEquals(Correction.None, c.decide(10_010, 10_000, true, 300))
        assertEquals(Correction.None, c.decide(9_950, 10_000, true, 400))
        assertEquals(Correction.Nudge(1.03f), c.decide(9_900, 10_000, true, 500))
        assertEquals(Correction.Seek(10_000), c.decide(12_000, 10_000, true, 600))
        // Right after a seek the decoder settles; no second seek within the cooldown.
        assertEquals(Correction.None, c.decide(12_000, 10_000, true, 1_100))
        assertEquals(Correction.Seek(10_000), c.decide(12_000, 10_000, true, 2_200))
    }

    @Test
    fun `tracks are matched by tags and duration, not file names`() {
        val library = listOf(
            track(1, title = "Saudade", artist = "Henrique Mendonça", album = "Ao Vivo", durationMs = 200_400),
            track(2, title = "Saudade", artist = "Outro Artista", durationMs = 200_000),
            track(3, title = "Saudade (Remix)", artist = "Henrique Mendonça", durationMs = 200_000),
        )
        val ref = TrackRef("SAUDADE", "henrique mendonca", "Ao Vivo", 199_000)
        assertEquals(1L, TrackMatcher.match(ref, library).track?.id)
        assertNull(TrackMatcher.match(ref.copy(durationMs = 260_000), library).track)
    }

    @Test
    fun `identical candidates are flagged for the user to choose`() {
        val library = listOf(
            track(1, title = "Saudade", artist = "H", album = "A", durationMs = 200_000),
            track(2, title = "Saudade", artist = "H", album = "A", durationMs = 200_100),
        ).map { it.copy(sizeBytes = 0) }
        val result = TrackMatcher.match(TrackRef("Saudade", "H", "A", 200_000), library)
        assertEquals(2, result.ambiguous.size)
    }
}
