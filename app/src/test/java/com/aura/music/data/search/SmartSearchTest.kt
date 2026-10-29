package com.aura.music.data.search

import com.aura.music.core.model.Playlist
import com.aura.music.data.media.LibraryIndex
import com.aura.music.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSearchTest {

    private val index = LibraryIndex(
        listOf(
            track(1, title = "Coração Selvagem", artist = "Belchior", artistId = 1, album = "Coração Selvagem", albumId = 1),
            track(2, title = "Como Nossos Pais", artist = "Belchior", artistId = 1, album = "Alucinação", albumId = 2),
            track(3, title = "Águas de Março", artist = "Elis Regina", artistId = 2, album = "Elis & Tom", albumId = 3),
            track(4, title = "Saudade", artist = "Henrique Mendonça", artistId = 4, album = "Ao Vivo", albumId = 5),
            track(5, title = "Sinal Fechado", artist = "Paulinho da Viola", artistId = 3, album = "Foi um Rio", albumId = 4),
            track(6, title = "Fechado pra Balanço", artist = "Outro", artistId = 5, album = "Avulsas", albumId = 6),
        ),
    )
    private val corpus = SearchCorpus(index, listOf(Playlist(1, "Manhã de domingo", 3, 600_000, null, 0)))

    private fun ids(query: String, signals: PersonalSignals = PersonalSignals.None) = corpus.search(query, signals).tracks.map { it.id }

    @Test
    fun `missing letters still find the artist`() {
        val results = corpus.search("henrique mendoca", PersonalSignals.None)
        assertEquals(listOf(4L), results.artists.map { it.id })
        assertEquals(listOf(4L), results.tracks.map { it.id })
    }

    @Test
    fun `accents and case do not matter`() {
        assertEquals(listOf(3L), ids("AGUAS DE MARCO"))
        assertTrue(ids("coracao").contains(1L))
    }

    @Test
    fun `small typos are tolerated`() {
        assertEquals(listOf(5L), ids("sinal fecahdo"))
        assertTrue(ids("belchoir").containsAll(listOf(1L, 2L)))
    }

    @Test
    fun `words may come in any order and across fields`() {
        assertEquals(listOf(5L), ids("fechado sinal"))
        assertEquals(1L, ids("belchior selvagem").first())
    }

    @Test
    fun `partial words match as prefixes`() {
        assertTrue(ids("alucin").contains(2L))
        assertTrue(ids("domin").isEmpty())
        assertEquals(1, corpus.search("domin", PersonalSignals.None).playlists.size)
    }

    @Test
    fun `personalization reorders equal matches but never hides an exact one`() {
        val heavy = PersonalSignals(plays = mapOf(6L to 500), lastPlayed = mapOf(6L to System.currentTimeMillis()), favorites = setOf(6L))
        // "Sinal Fechado" matches by word; the frequently played "Fechado pra Balanço" by prefix: prefix wins.
        assertEquals(6L, ids("fechado", heavy).first())
        // An exact title match stays first however much the other song is played.
        assertEquals(5L, ids("sinal fechado", heavy).first())
    }

    @Test
    fun `personalization breaks ties between equally relevant results`() {
        // Both songs match "belchior" equally (artist); without history the order is alphabetical.
        assertEquals(2L, ids("belchior", PersonalSignals.None).first())
        val signals = PersonalSignals(plays = mapOf(1L to 50))
        assertEquals(1L, ids("belchior", signals).first())
    }

    @Test
    fun `short words need an exact prefix`() {
        assertTrue(ids("xy").isEmpty())
    }

    @Test
    fun `top result prefers an exact artist`() {
        val top = corpus.search("elis regina", PersonalSignals.None).top
        assertTrue(top is TopResult.OfArtist)
    }

    @Test
    fun `suggestions complete the last word`() {
        val suggestions = corpus.search("paulinho da vio", PersonalSignals.None).suggestions
        assertTrue(suggestions.any { it.endsWith("viola") })
    }

    @Test
    fun `filter restricts categories`() {
        val results = corpus.search("belchior", PersonalSignals.None, SearchFilter.Artists)
        assertTrue(results.tracks.isEmpty())
        assertEquals(1, results.artists.size)
    }

    @Test
    fun `track filter keeps library order`() {
        assertEquals(listOf(1L, 2L), corpus.filterTracks("belchior").map { it.id }.sorted())
        assertEquals(6, corpus.filterTracks("").size)
    }

    @Test
    fun `bounded edit distance`() {
        assertEquals(1, Fuzzy.distance("mendoca", "mendonca", 2))
        assertEquals(1, Fuzzy.distance("fecahdo", "fechado", 2))
        assertEquals(Int.MAX_VALUE, Fuzzy.distance("abc", "xyz", 1))
    }

    @Test
    fun `large libraries are searched quickly`() {
        val big = LibraryIndex((1L..20_000L).map { track(it, title = "Song number $it", artist = "Artist ${it % 700}", artistId = it % 700, album = "Album ${it % 1500}", albumId = it % 1500) })
        val bigCorpus = SearchCorpus(big, emptyList())
        bigCorpus.search("warmup", PersonalSignals.None)
        val start = System.nanoTime()
        val results = bigCorpus.search("artst 12 sogn", PersonalSignals.None)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(results.tracks.isNotEmpty())
        assertTrue("search took $elapsedMs ms", elapsedMs < 1_000)
    }
}
