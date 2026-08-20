package it.agoldoni.player.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferManifestTest {

    private fun manifest(vararg tracks: ManifestTrack, playlists: List<ManifestPlaylist> = emptyList()) =
        TransferManifest(
            device = "Pixel 7",
            trackCount = tracks.size,
            totalBytes = tracks.sumOf { it.originalFileSize },
            tracks = tracks.toList(),
            playlists = playlists
        )

    private fun track(id: String, title: String = "Titolo $id") = ManifestTrack(
        id = id,
        title = title,
        artist = "Artista",
        album = "Album",
        duration = 240_000,
        year = "1998",
        trackNumber = "3/12",
        originalExtension = "flac",
        originalFileSize = 12_345_678,
        encryptedFileSize = 12_345_706,
        importedAt = 1_600_000_000_000,
        hasArt = true
    )

    @Test
    fun `il manifest sopravvive al giro completo di serializzazione`() {
        val original = manifest(
            track("a"),
            track("b"),
            playlists = listOf(
                ManifestPlaylist(
                    id = "p1",
                    name = "Preferiti",
                    createdAt = 1_500_000_000_000,
                    lastPlayedTrackId = "a",
                    entries = listOf(
                        ManifestPlaylistEntry("a", 1_510_000_000_000),
                        ManifestPlaylistEntry("b", 1_520_000_000_000)
                    )
                )
            )
        )

        val decoded = decodeManifest(original.encodeToJson())

        assertEquals(original, decoded)
        assertEquals(2, decoded.trackCount)
        assertEquals("1998", decoded.tracks.first().year)
        assertEquals(1_600_000_000_000, decoded.tracks.first().importedAt)
        assertEquals("flac", decoded.tracks.first().originalExtension)
        assertEquals(2, decoded.playlists.first().entries.size)
    }

    @Test
    fun `titoli con virgolette accapo ed emoji restano intatti`() {
        val nasty = "Bohemian \"Rhapsody\"\n\tLive \\ 2024 — è così 🎧"
        val decoded = decodeManifest(manifest(track("a", nasty)).encodeToJson())
        assertEquals(nasty, decoded.tracks.first().title)
    }

    @Test
    fun `i campi opzionali assenti diventano null`() {
        val json = """
            {"protocolVersion":$PROTOCOL_VERSION,"device":"Pixel","trackCount":1,"totalBytes":10,
             "tracks":[{"id":"a","title":"T","artist":"A","album":"Al","duration":1000}],
             "playlists":[]}
        """.trimIndent()

        val decoded = decodeManifest(json)

        assertNull(decoded.tracks.first().year)
        assertNull(decoded.tracks.first().trackNumber)
        assertEquals("mp3", decoded.tracks.first().originalExtension)
        assertTrue(decoded.playlists.isEmpty())
    }

    @Test
    fun `i campi sconosciuti non fanno fallire la decodifica`() {
        val json = manifest(track("a")).encodeToJson()
            .replaceFirst("{\"protocolVersion\"", "{\"campoFuturo\":\"x\",\"protocolVersion\"")
        assertEquals(1, decodeManifest(json).trackCount)
    }

    @Test
    fun `una versione di protocollo diversa viene rifiutata`() {
        val json = manifest(track("a")).copy(protocolVersion = PROTOCOL_VERSION + 1).encodeToJson()
        val error = assertThrows(IncompatibleProtocolException::class.java) { decodeManifest(json) }
        assertEquals(PROTOCOL_VERSION + 1, error.theirVersion)
    }

    @Test
    fun `pairing e riepilogo viaggiano nello stesso formato`() {
        val request = PairRequest(PROTOCOL_VERSION, "chiave", "Pixel 7")
        val encoded = TransferJson.encodeToString(PairRequest.serializer(), request)
        assertEquals(request, TransferJson.decodeFromString(PairRequest.serializer(), encoded))

        val report = TransferReport(added = 12, skipped = 3, errors = 1, cancelled = true)
        val reportJson = TransferJson.encodeToString(TransferReport.serializer(), report)
        assertEquals(report, TransferJson.decodeFromString(TransferReport.serializer(), reportJson))
    }
}
