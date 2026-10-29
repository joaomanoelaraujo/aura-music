package com.aura.music.core.image

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Bumped when artwork appears after it was looked for (e.g. a cover found online), so images and
 * colours already on screen load again instead of keeping their placeholder. Plain snapshot state:
 * images just read [version], which costs nothing per row of a long list.
 */
object ArtworkUpdates {
    var version by mutableIntStateOf(0)
        private set

    fun bump() {
        version++
    }
}
