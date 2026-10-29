package com.aura.desktop

import java.nio.file.Path
import java.nio.file.Paths

/** Short synthetic tones with tags and covers (generated with FFmpeg), in several formats. */
object TestMedia {
    val root: Path = Paths.get(requireNotNull(TestMedia::class.java.getResource("/media")).toURI())

    fun file(name: String): Path = root.resolve(name)
}
