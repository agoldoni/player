package it.agoldoni.player.domain.playback

import it.agoldoni.player.data.local.entity.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {

    private fun track(id: String) = Track(
        id = id,
        uri = "/path/$id",
        title = "Title $id",
        artist = "Artist",
        album = "Album",
        duration = 1000L,
        year = null,
        trackNumber = null,
        albumArtPath = null
    )

    private val source = listOf("a", "b", "c", "d").map(::track)

    @Test
    fun `setQueue senza shuffle parte dal brano indicato e ne segue l'ordine`() {
        val q = PlaybackQueue()
        val current = q.setQueue("library", source, startTrackId = "c", shuffle = false)

        assertEquals("c", current?.id)
        assertEquals("c", q.currentTrackId.value)
        assertEquals("library", q.ownerTag.value)
        assertEquals("d", q.moveToNext(wrap = false)?.id)
    }

    @Test
    fun `setQueue senza shuffle e senza startTrackId parte dall'inizio`() {
        val q = PlaybackQueue()
        val current = q.setQueue("library", source, startTrackId = null, shuffle = false)
        assertEquals("a", current?.id)
    }

    @Test
    fun `setQueue su sorgente vuota ritorna null`() {
        val q = PlaybackQueue()
        assertNull(q.setQueue("library", emptyList(), startTrackId = "x", shuffle = false))
        assertNull(q.currentTrackId.value)
    }

    @Test
    fun `setQueue con shuffle mette il brano iniziale in testa e mantiene tutti i brani`() {
        val q = PlaybackQueue()
        val current = q.setQueue("library", source, startTrackId = "c", shuffle = true)

        assertEquals("c", current?.id)
        // Scorrendo tutta la coda devono comparire esattamente tutti i brani.
        val seen = mutableSetOf(current!!.id)
        repeat(source.size - 1) { seen.add(q.moveToNext(wrap = false)!!.id) }
        assertEquals(source.map { it.id }.toSet(), seen)
    }

    @Test
    fun `moveToNext senza wrap ritorna null a fine coda`() {
        val q = PlaybackQueue()
        q.setQueue("library", source, startTrackId = "d", shuffle = false)
        assertNull(q.moveToNext(wrap = false))
    }

    @Test
    fun `moveToNext con wrap riparte dall'inizio a fine coda`() {
        val q = PlaybackQueue()
        q.setQueue("library", source, startTrackId = "d", shuffle = false)
        val wrapped = q.moveToNext(wrap = true)
        assertEquals("a", wrapped?.id)
    }

    @Test
    fun `setShuffle attivo mantiene il brano corrente in riproduzione`() {
        val q = PlaybackQueue()
        q.setQueue("library", source, startTrackId = "b", shuffle = false)
        q.moveToNext(wrap = false) // ora corrente = c
        assertEquals("c", q.currentTrackId.value)

        q.setShuffle(true)
        // Il brano corrente non cambia riordinando.
        assertEquals("c", q.currentTrackId.value)
        assertEquals("c", q.current()?.id)
    }

    @Test
    fun `setShuffle disattivo ripristina l'ordine originale dal brano corrente`() {
        val q = PlaybackQueue()
        q.setQueue("library", source, startTrackId = "a", shuffle = true)
        // qualunque sia l'ordine shuffle, disattivandolo si torna alla sorgente originale
        q.setShuffle(false)
        val current = q.current()!!.id
        val expectedNext = source[source.indexOfFirst { it.id == current } + 1].id
        assertEquals(expectedNext, q.moveToNext(wrap = false)?.id)
    }

    @Test
    fun `clear svuota coda e owner`() {
        val q = PlaybackQueue()
        q.setQueue("library", source, startTrackId = "a", shuffle = false)
        q.clear()
        assertNull(q.currentTrackId.value)
        assertNull(q.ownerTag.value)
        assertNull(q.current())
        assertNull(q.moveToNext(wrap = true))
    }

    @Test
    fun `setShuffle disattivo quando il corrente non e' il primo posiziona correttamente l'indice`() {
        val q = PlaybackQueue()
        q.setQueue("library", source, startTrackId = "a", shuffle = false)
        q.moveToNext(wrap = false) // b
        q.moveToNext(wrap = false) // c
        q.setShuffle(false)
        assertEquals("c", q.current()?.id)
        assertTrue(q.moveToNext(wrap = false)?.id == "d")
    }
}
