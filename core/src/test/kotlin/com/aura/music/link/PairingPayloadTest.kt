package com.aura.music.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun roundTrips() {
        val p = PairingPayload("AURA no PC da Sala", listOf("192.168.1.10", "10.0.0.5"), 47820, Pairing.newQrSecret())
        assertEquals(p, PairingPayload.parse(p.encode()))
    }

    @Test
    fun rejectsAnythingElse() {
        val good = PairingPayload("PC", listOf("192.168.0.2"), 47820, ByteArray(32) { 7 }).encode()
        assertNull(PairingPayload.parse("https://example.com/?x=1"))
        assertNull(PairingPayload.parse(good.replace("v=1", "v=9")))
        assertNull(PairingPayload.parse(good.replace("p=47820", "p=70000")))
        assertNull(PairingPayload.parse(good.replace("h=192.168.0.2", "h=999.1.1.1")))
        assertNull(PairingPayload.parse(good.replace("h=192.168.0.2", "h=evil.example.com")))
        assertNull(PairingPayload.parse(good.substringBefore("&s=") + "&s=AAAA"))
    }
}
