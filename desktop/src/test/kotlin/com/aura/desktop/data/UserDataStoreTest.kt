package com.aura.desktop.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserDataStoreTest {

    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun store() = UserDataStore(scope, temp.root.toPath().resolve("user.json"))

    @Test
    fun playlistsKeepOrderAndSkipDuplicates() {
        val s = store()
        val id = s.createPlaylist("Estrada", listOf("a.mp3"))
        assertEquals(2, s.addToPlaylist(id, listOf("b.mp3", "c.mp3", "a.mp3")))
        assertEquals(0, s.addToPlaylist(id, listOf("b.mp3")))
        s.moveInPlaylist(id, 2, 0)
        s.removeFromPlaylist(id, 1)
        assertEquals(listOf("c.mp3", "b.mp3"), s.value.value.playlists.single().paths)
    }

    @Test
    fun namesStayUnique() {
        val s = store()
        s.createPlaylist("Noite")
        s.createPlaylist("noite")
        s.createPlaylist("  ")
        assertEquals(listOf("Noite", "noite 2", "Nova playlist"), s.value.value.playlists.map { it.name })
    }

    @Test
    fun favoritesToggleAndNewestFirst() {
        val s = store()
        s.toggleFavorite("a.mp3")
        s.toggleFavorite("b.mp3")
        assertEquals(listOf("b.mp3", "a.mp3"), s.value.value.favorites)
        s.toggleFavorite("a.mp3")
        assertFalse(s.isFavorite("a.mp3"))
        assertTrue(s.isFavorite("b.mp3"))
    }

    @Test
    fun survivesARestart() {
        val first = store()
        val id = first.createPlaylist("Guardada", listOf("x.flac"))
        first.toggleFavorite("x.flac")
        first.recordPlay("x.flac", at = 42)
        first.recordPlay("x.flac", at = 43)
        first.flush()
        val second = store()
        assertEquals(listOf("x.flac"), second.value.value.playlists.single { it.id == id }.paths)
        assertEquals(listOf("x.flac"), second.value.value.favorites)
        assertEquals(PlayStat(2, 43), second.value.value.stats["x.flac"])
    }

    @Test
    fun deletingRemovesOnlyThatPlaylist() {
        val s = store()
        val a = s.createPlaylist("A")
        s.createPlaylist("B")
        s.deletePlaylist(a)
        assertEquals(listOf("B"), s.value.value.playlists.map { it.name })
    }
}
