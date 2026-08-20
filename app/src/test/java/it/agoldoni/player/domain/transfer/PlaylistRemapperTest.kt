package it.agoldoni.player.domain.transfer

import it.agoldoni.player.data.local.entity.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRemapperTest {

    private fun manifestPlaylist(
        id: String,
        name: String,
        entries: List<Pair<String, Long>>,
        lastPlayed: String? = null
    ) = ManifestPlaylist(
        id = id,
        name = name,
        createdAt = 1_500_000_000_000,
        lastPlayedTrackId = lastPlayed,
        entries = entries.map { (trackId, addedAt) -> ManifestPlaylistEntry(trackId, addedAt) }
    )

    private val fixedIds = generateSequence(1) { it + 1 }.map { "local-p$it" }.iterator()

    @Test
    fun `una playlist nuova viene creata con gli ID locali`() {
        val result = PlaylistRemapper.remap(
            manifestPlaylists = listOf(
                manifestPlaylist("p1", "Corsa", listOf("t1" to 100L, "t2" to 200L), lastPlayed = "t2")
            ),
            trackIdMap = mapOf("t1" to "local-t1", "t2" to "local-t2"),
            existingByName = emptyMap(),
            newIdProvider = { fixedIds.next() }
        )

        assertEquals(1, result.newPlaylists.size)
        assertEquals(0, result.mergedCount)
        val created = result.newPlaylists.first()
        assertEquals("Corsa", created.name)
        assertEquals(1_500_000_000_000, created.createdAt)
        assertEquals("local-t2", created.lastPlayedTrackId)

        assertEquals(2, result.crossRefs.size)
        assertTrue(result.crossRefs.all { it.playlistId == created.id })
        assertEquals(listOf("local-t1", "local-t2"), result.crossRefs.map { it.trackId })
        assertEquals(listOf(100L, 200L), result.crossRefs.map { it.addedAt })
    }

    @Test
    fun `una playlist omonima viene fusa con quella locale`() {
        val existing = Playlist(id = "già-mia", name = "Corsa", createdAt = 1L, lastPlayedTrackId = "mio")
        val result = PlaylistRemapper.remap(
            manifestPlaylists = listOf(
                manifestPlaylist("p1", "Corsa", listOf("t1" to 100L), lastPlayed = "t1")
            ),
            trackIdMap = mapOf("t1" to "local-t1"),
            existingByName = mapOf("Corsa" to existing),
            newIdProvider = { fixedIds.next() }
        )

        assertTrue(result.newPlaylists.isEmpty())
        assertEquals(1, result.mergedCount)
        assertEquals("già-mia", result.crossRefs.single().playlistId)
        // Il lastPlayedTrackId locale non viene toccato: descrive gli ascolti su questo telefono.
        assertEquals("mio", existing.lastPlayedTrackId)
    }

    @Test
    fun `i brani deduplicati restano nella playlist tramite la mappa degli ID`() {
        val result = PlaylistRemapper.remap(
            manifestPlaylists = listOf(manifestPlaylist("p1", "Mix", listOf("t1" to 1L, "t2" to 2L))),
            // t2 era già presente: la mappa punta al brano locale preesistente.
            trackIdMap = mapOf("t1" to "local-t1", "t2" to "preesistente"),
            existingByName = emptyMap(),
            newIdProvider = { fixedIds.next() }
        )

        assertEquals(listOf("local-t1", "preesistente"), result.crossRefs.map { it.trackId })
    }

    @Test
    fun `i brani non arrivati vengono esclusi e le playlist vuote saltate`() {
        val result = PlaylistRemapper.remap(
            manifestPlaylists = listOf(
                manifestPlaylist("p1", "Parziale", listOf("t1" to 1L, "mancante" to 2L)),
                manifestPlaylist("p2", "Persa", listOf("mancante2" to 3L))
            ),
            trackIdMap = mapOf("t1" to "local-t1"),
            existingByName = emptyMap(),
            newIdProvider = { fixedIds.next() }
        )

        assertEquals(1, result.newPlaylists.size)
        assertEquals("Parziale", result.newPlaylists.first().name)
        assertEquals(listOf("local-t1"), result.crossRefs.map { it.trackId })
    }

    @Test
    fun `un lastPlayedTrackId non trasferito diventa null`() {
        val result = PlaylistRemapper.remap(
            manifestPlaylists = listOf(
                manifestPlaylist("p1", "Serale", listOf("t1" to 1L), lastPlayed = "mai-arrivato")
            ),
            trackIdMap = mapOf("t1" to "local-t1"),
            existingByName = emptyMap(),
            newIdProvider = { fixedIds.next() }
        )

        assertNull(result.newPlaylists.first().lastPlayedTrackId)
    }
}
