package com.aura.music.together

import com.aura.music.together.protocol.Message
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.transfer.BytePacer
import com.aura.music.together.transfer.StreamCache
import com.aura.music.together.transfer.TrackReceiver
import com.aura.music.together.transfer.Transfers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Receiving a song must never stall: lost answers, repeated answers and pacing. */
class TrackReceiverTest {

    @get:Rule val temp = TemporaryFolder()

    private val ref = TrackRef("Longa", "Alguém", null, 60_000, 1_000_000)
    private val song = ByteArray(1_000_000) { (it % 251).toByte() }
    private var now = 0L

    /** The sender's side: answers a fetch with chunks, exactly like [Transfers.serve]. */
    private fun answer(fetch: Message.Fetch): List<Message.Chunk> {
        val end = minOf(song.size.toLong(), fetch.offset + fetch.length)
        return (fetch.offset until end step Transfers.CHUNK_BYTES.toLong()).map { offset ->
            val to = minOf(end, offset + Transfers.CHUNK_BYTES)
            Message.Chunk(ref, offset, song.size.toLong(), song.copyOfRange(offset.toInt(), to.toInt()))
        }
    }

    @Test
    fun `a lost answer is asked again instead of waiting forever`() = runBlocking {
        val cache = StreamCache(temp.newFolder())
        val receiver = TrackReceiver(cache) { now }
        val sent = ArrayDeque<Message.Fetch>()
        val send: suspend (Message) -> Unit = { if (it is Message.Fetch) sent.addLast(it) }
        receiver.receive(ref)
        receiver.pump(send)
        var lostOne = false
        var done = false
        var rounds = 0
        while (!done && rounds++ < 200) {
            val fetch = sent.removeFirstOrNull()
            if (fetch == null) {
                // Nothing left in flight that will ever be answered: only the watchdog can help.
                now += 10_000
                assertTrue("receiver noticed the stall", receiver.stalled(8_000))
                receiver.pump(send)
                continue
            }
            // The relay drops the second window once.
            if (!lostOne && fetch.offset > 0) {
                lostOne = true
                continue
            }
            for (chunk in answer(fetch)) {
                when (receiver.onChunk(chunk)) {
                    is TrackReceiver.Event.Complete -> done = true
                    null -> Unit
                    else -> receiver.pump(send)
                }
            }
        }
        assertTrue("song completed", done)
        assertTrue(cache.complete(ref)!!.readBytes().contentEquals(song))
    }

    @Test
    fun `answers to a song left and picked up again don't fill the window forever`() = runBlocking {
        val cache = StreamCache(temp.newFolder())
        val receiver = TrackReceiver(cache) { now }
        val other = ref.copy(title = "Outra")
        val sent = ArrayDeque<Message.Fetch>()
        val send: suspend (Message) -> Unit = { if (it is Message.Fetch) sent.addLast(it) }
        receiver.receive(ref)
        receiver.pump(send)
        // First answer in: the size is known and two windows are asked for.
        answer(sent.removeFirst()).forEach { if (receiver.onChunk(it) != null) receiver.pump(send) }
        // The guest switches away and back (the host skipped and returned) before those arrive.
        receiver.receive(other)
        receiver.receive(ref)
        receiver.pump(send)
        // Everything asked for arrives, old requests and new, in the order they were asked.
        var done = false
        var rounds = 0
        while (!done && rounds++ < 200) {
            val fetch = sent.removeFirstOrNull() ?: break
            for (chunk in answer(fetch)) {
                when (receiver.onChunk(chunk)) {
                    is TrackReceiver.Event.Complete -> done = true
                    null -> Unit
                    else -> receiver.pump(send)
                }
            }
        }
        if (!done) {
            // Whatever is left must be recoverable by the watchdog, never a permanent stall.
            now += 10_000
            receiver.stalled(8_000)
            receiver.pump(send)
            while (!done) {
                val fetch = sent.removeFirstOrNull() ?: break
                answer(fetch).forEach { c -> when (receiver.onChunk(c)) { is TrackReceiver.Event.Complete -> done = true; null -> Unit; else -> receiver.pump(send) } }
            }
        }
        assertTrue("song completed", done)
        assertTrue(cache.complete(ref)!!.readBytes().contentEquals(song))
    }

    @Test
    fun `the relay pace lets a burst through, then the steady rate`() {
        val pacer = BytePacer(perSecond = 1000, burst = 3000) { now }
        assertTrue(pacer.take(1000))
        assertTrue(pacer.take(1000))
        assertTrue(pacer.take(1000))
        assertFalse(pacer.take(1000))
        now += 1000
        assertTrue(pacer.take(1000))
        assertFalse(pacer.take(1000))
        now += 60_000
        // Never more than the burst saved up.
        repeat(3) { assertTrue(pacer.take(1000)) }
        assertFalse(pacer.take(1000))
    }
}
