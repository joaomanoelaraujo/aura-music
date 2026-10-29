package com.aura.desktop.playback

import com.aura.music.core.model.Track
import kotlin.random.Random

enum class Repeat { Off, All, One }

/** A queue entry; [uid] tells apart the same song queued twice. */
data class QueueItem(val uid: Long, val track: Track)

/**
 * The play queue as an immutable value. Every operation returns a new queue, so the player can
 * swap it atomically and tests can check each step.
 *
 * With shuffle on, [items] is the shuffled order and [original] remembers the order the user chose,
 * so turning shuffle off puts things back (including songs added meanwhile, at the end).
 */
data class PlayQueue(
    val items: List<QueueItem> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.Off,
    val original: List<QueueItem> = emptyList(),
    private val nextUid: Long = 1,
) {
    val current: QueueItem? get() = items.getOrNull(index)
    val isEmpty: Boolean get() = items.isEmpty()
    val upcoming: List<QueueItem> get() = if (index < 0) items else items.drop(index + 1)

    /** Replaces the queue with [tracks], starting at [start]. */
    fun play(tracks: List<Track>, start: Int, random: Random = Random): PlayQueue {
        if (tracks.isEmpty()) return copy(items = emptyList(), index = -1, original = emptyList())
        val (wrapped, uid) = wrap(tracks, nextUid)
        val first = start.coerceIn(0, tracks.lastIndex)
        return if (shuffle) {
            val chosen = wrapped[first]
            copy(items = listOf(chosen) + (wrapped - chosen).shuffled(random), index = 0, original = wrapped, nextUid = uid)
        } else {
            copy(items = wrapped, index = first, original = wrapped, nextUid = uid)
        }
    }

    /**
     * Where to go after the current song. [auto] is true when the song ended by itself: only then
     * does Repeat One replay it. Returns null at the end of the queue.
     */
    fun next(auto: Boolean): PlayQueue? = when {
        items.isEmpty() -> null
        auto && repeat == Repeat.One -> this
        index < items.lastIndex -> copy(index = index + 1)
        repeat != Repeat.Off -> copy(index = 0)
        else -> null
    }

    fun previous(): PlayQueue = when {
        items.isEmpty() -> this
        index > 0 -> copy(index = index - 1)
        repeat == Repeat.All -> copy(index = items.lastIndex)
        else -> copy(index = 0)
    }

    fun jumpTo(position: Int): PlayQueue = if (position in items.indices) copy(index = position) else this

    fun addLast(tracks: List<Track>): PlayQueue {
        val (wrapped, uid) = wrap(tracks, nextUid)
        val items = items + wrapped
        return copy(items = items, index = if (index < 0 && items.isNotEmpty()) 0 else index, original = original + wrapped, nextUid = uid)
    }

    /** Puts [tracks] right after the current song. */
    fun playNext(tracks: List<Track>): PlayQueue {
        if (items.isEmpty()) return addLast(tracks)
        val (wrapped, uid) = wrap(tracks, nextUid)
        val at = index + 1
        val items = items.toMutableList().apply { addAll(at, wrapped) }
        val currentInOriginal = original.indexOf(current).let { if (it < 0) original.size - 1 else it }
        val original = original.toMutableList().apply { addAll(currentInOriginal + 1, wrapped) }
        return copy(items = items, original = original, nextUid = uid)
    }

    /** Removes the entry at [position]; if it was playing, the next one becomes current. */
    fun removeAt(position: Int): PlayQueue {
        if (position !in items.indices) return this
        val removed = items[position]
        val items = items.toMutableList().apply { removeAt(position) }
        val index = when {
            items.isEmpty() -> -1
            position < index -> index - 1
            position == index -> index.coerceAtMost(items.lastIndex)
            else -> index
        }
        return copy(items = items, index = index, original = original - removed)
    }

    fun move(from: Int, to: Int): PlayQueue {
        if (from !in items.indices || to !in items.indices || from == to) return this
        val moved = items[from]
        val items = items.toMutableList().apply { removeAt(from); add(to, moved) }
        val index = when (index) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) index - 1 else index + 1
            else -> index
        }
        val original = if (shuffle) original else items
        return copy(items = items, index = index, original = original)
    }

    /** Keeps the current song and drops everything after it. */
    fun clearUpcoming(): PlayQueue {
        if (current == null) return PlayQueue(shuffle = shuffle, repeat = repeat, nextUid = nextUid)
        val kept = items.take(index + 1)
        return copy(items = kept, original = if (shuffle) original.filter { it in kept } else kept)
    }

    fun withShuffle(enabled: Boolean, random: Random = Random): PlayQueue {
        if (enabled == shuffle) return this
        val playing = current
        if (enabled) {
            if (playing == null) return copy(shuffle = true, items = items.shuffled(random), original = items)
            return copy(shuffle = true, items = listOf(playing) + (items - playing).shuffled(random), index = 0, original = original)
        }
        // Back to the chosen order; anything the original lost track of goes at the end.
        val restored = original.filter { it in items } + items.filter { it !in original }
        return copy(shuffle = false, items = restored, index = playing?.let(restored::indexOf) ?: -1, original = restored)
    }

    fun withRepeat(mode: Repeat): PlayQueue = copy(repeat = mode)

    private fun wrap(tracks: List<Track>, firstUid: Long): Pair<List<QueueItem>, Long> {
        var uid = firstUid
        return tracks.map { QueueItem(uid++, it) } to uid
    }
}
