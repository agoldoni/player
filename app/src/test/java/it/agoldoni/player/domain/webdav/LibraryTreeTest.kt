package it.agoldoni.player.domain.webdav

import it.agoldoni.player.data.local.entity.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTreeTest {

    private var counter = 0

    private fun track(
        id: String = "id-${++counter}",
        title: String = "Titolo",
        artist: String = "Artista",
        album: String = "Album",
        trackNumber: String? = null,
        extension: String = "mp3"
    ) = Track(
        id = id,
        uri = "/data/tracks/$id",
        title = title,
        artist = artist,
        album = album,
        duration = 1_000,
        year = null,
        trackNumber = trackNumber,
        albumArtPath = null,
        originalExtension = extension
    )

    private fun fileNames(root: DavNode.Collection, artist: String, album: String): List<String> {
        val node = root.resolve(listOf(artist, album))
        assertTrue("atteso un album in $artist/$album", node is DavNode.Collection)
        return (node as DavNode.Collection).children.map { it.name }
    }

    @Test
    fun `l'albero ha tre livelli anche quando l'artista ha un solo album`() {
        val root = LibraryTree.build(listOf(track(title = "Solo")))

        val artist = root.children.single() as DavNode.Collection
        assertEquals("Artista", artist.name)
        val album = artist.children.single() as DavNode.Collection
        assertEquals("Album", album.name)
        assertEquals("Solo.mp3", album.children.single().name)
    }

    @Test
    fun `artista, album e titolo vuoti usano i nomi di ripiego`() {
        val root = LibraryTree.build(listOf(track(title = "   ", artist = "", album = "  ")))

        assertEquals(
            listOf("Senza titolo.mp3"),
            fileNames(root, LibraryTree.UNKNOWN_ARTIST, LibraryTree.UNKNOWN_ALBUM)
        )
    }

    @Test
    fun `il numero di traccia viene normalizzato a due cifre`() {
        assertEquals("03", LibraryTree.trackNumberPrefix("3/12"))
        assertEquals("03", LibraryTree.trackNumberPrefix(" 3 "))
        assertEquals("12", LibraryTree.trackNumberPrefix("12"))
        assertEquals("105", LibraryTree.trackNumberPrefix("105"))
        assertNull(LibraryTree.trackNumberPrefix(null))
        assertNull(LibraryTree.trackNumberPrefix("abc"))
        assertNull(LibraryTree.trackNumberPrefix(""))
    }

    @Test
    fun `il prefisso di traccia compare nel nome del file solo se presente`() {
        val root = LibraryTree.build(
            listOf(
                track(title = "Con numero", trackNumber = "3/12"),
                track(title = "Senza numero", trackNumber = null)
            )
        )

        assertEquals(
            listOf("03 - Con numero.mp3", "Senza numero.mp3"),
            fileNames(root, "Artista", "Album")
        )
    }

    @Test
    fun `i caratteri vietati vengono sostituiti e i punti finali rimossi`() {
        val root = LibraryTree.build(
            listOf(
                track(
                    title = "AC/DC: live?",
                    artist = "Prima\\Dopo",
                    album = "Album finale..."
                )
            )
        )

        val artist = root.children.single() as DavNode.Collection
        assertEquals("Prima_Dopo", artist.name)
        val album = artist.children.single() as DavNode.Collection
        assertEquals("Album finale", album.name)
        assertEquals("AC_DC_ live_.mp3", album.children.single().name)
    }

    @Test
    fun `i caratteri di controllo diventano underscore`() {
        val conControllo = "a" + 1.toChar() + "b"

        assertEquals("a_b", LibraryTree.sanitizeSegment(conControllo, "ripiego"))
        assertEquals("ripiego", LibraryTree.sanitizeSegment("", "ripiego"))
        assertEquals("ripiego", LibraryTree.sanitizeSegment("  ...  ", "ripiego"))
    }

    @Test
    fun `un segmento troppo lungo viene troncato`() {
        val lungo = "x".repeat(300)

        val ripulito = LibraryTree.sanitizeSegment(lungo, "ripiego")

        assertEquals(LibraryTree.MAX_SEGMENT, ripulito.length)
    }

    @Test
    fun `l'estensione originale viene rispettata`() {
        val root = LibraryTree.build(listOf(track(title = "Brano", extension = "FLAC")))

        assertEquals(listOf("Brano.flac"), fileNames(root, "Artista", "Album"))
    }

    @Test
    fun `due brani omonimi ricevono un suffisso derivato dall'ID`() {
        val a = track(id = "aaaaaaaa-1111-2222-3333-444444444444", title = "Uguale")
        val b = track(id = "bbbbbbbb-1111-2222-3333-444444444444", title = "Uguale")

        val nomi = fileNames(LibraryTree.build(listOf(a, b)), "Artista", "Album")

        assertEquals(listOf("Uguale [aaaaaaaa].mp3", "Uguale [bbbbbbbb].mp3"), nomi)
    }

    @Test
    fun `i nomi degli omonimi non dipendono dall'ordine ne dal resto della libreria`() {
        val a = track(id = "aaaaaaaa-1111-2222-3333-444444444444", title = "Uguale")
        val b = track(id = "bbbbbbbb-1111-2222-3333-444444444444", title = "Uguale")
        val estraneo = track(id = "cccccccc-1111", title = "Altro", artist = "Altro artista")

        val riferimento = fileNames(LibraryTree.build(listOf(a, b)), "Artista", "Album")
        val ordineInverso = fileNames(LibraryTree.build(listOf(b, a)), "Artista", "Album")
        val conEstraneo = fileNames(LibraryTree.build(listOf(a, estraneo, b)), "Artista", "Album")

        // È il test che protegge il requisito principale: se un nome cambiasse fra
        // due sincronizzazioni, il client riscaricherebbe il brano ogni volta.
        assertEquals(riferimento, ordineInverso)
        assertEquals(riferimento, conEstraneo)
    }

    @Test
    fun `brani omonimi di album diversi non si disturbano`() {
        val root = LibraryTree.build(
            listOf(
                track(title = "Uguale", album = "Primo"),
                track(title = "Uguale", album = "Secondo")
            )
        )

        assertEquals(listOf("Uguale.mp3"), fileNames(root, "Artista", "Primo"))
        assertEquals(listOf("Uguale.mp3"), fileNames(root, "Artista", "Secondo"))
    }

    @Test
    fun `artisti che si sanificano allo stesso modo finiscono nella stessa cartella`() {
        val root = LibraryTree.build(
            listOf(
                track(title = "Uno", artist = "AC/DC"),
                track(title = "Due", artist = "AC_DC")
            )
        )

        val artist = root.children.single() as DavNode.Collection
        assertEquals("AC_DC", artist.name)
        assertEquals(listOf("Due.mp3", "Uno.mp3"), fileNames(root, "AC_DC", "Album"))
    }

    @Test
    fun `resolve naviga cartelle e file`() {
        val root = LibraryTree.build(listOf(track(title = "Brano")))

        assertTrue(root.resolve(emptyList()) is DavNode.Collection)
        assertTrue(root.resolve(listOf("Artista")) is DavNode.Collection)
        assertTrue(root.resolve(listOf("Artista", "Album")) is DavNode.Collection)
        assertTrue(root.resolve(listOf("Artista", "Album", "Brano.mp3")) is DavNode.TrackFile)
    }

    @Test
    fun `resolve rifiuta percorsi inesistenti e discese dentro un file`() {
        val root = LibraryTree.build(listOf(track(title = "Brano")))

        assertNull(root.resolve(listOf("Ignoto")))
        assertNull(root.resolve(listOf("Artista", "Ignoto")))
        assertNull(root.resolve(listOf("Artista", "Album", "Brano.mp3", "oltre")))
    }

    @Test
    fun `i segmenti vuoti vengono ignorati nella risoluzione`() {
        val root = LibraryTree.build(listOf(track(title = "Brano")))

        assertTrue(root.resolve(listOf("", "Artista", "")) is DavNode.Collection)
    }

    @Test
    fun `una libreria vuota produce una root senza figli`() {
        val root = LibraryTree.build(emptyList())

        assertEquals(emptyList<DavNode>(), root.children)
    }
}
