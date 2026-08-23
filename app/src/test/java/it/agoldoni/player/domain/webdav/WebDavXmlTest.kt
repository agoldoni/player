package it.agoldoni.player.domain.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavXmlTest {

    // 2017-07-14T02:40:00Z, scelto fisso perché la formattazione delle date è
    // parte del contratto verso il client.
    private val istante = 1_500_000_000_000L

    private fun collection(vararg segments: String) = DavResource(
        segments = segments.toList(),
        isCollection = true,
        displayName = segments.lastOrNull() ?: "",
        lastModified = istante
    )

    private fun file(vararg segments: String) = DavResource(
        segments = segments.toList(),
        isCollection = false,
        displayName = segments.last(),
        contentLength = 4_096,
        lastModified = istante,
        contentType = "audio/mpeg"
    )

    @Test
    fun `spazi e accenti finiscono percent-encoded nell'href`() {
        val xml = WebDavXml.multiStatus("tok123", listOf(collection("Fabrizio De André")))

        assertTrue(xml.contains("<D:href>/tok123/Fabrizio%20De%20Andr%C3%A9/</D:href>"))
    }

    @Test
    fun `nell'href la e commerciale e percent-encoded, nel displayname e XML-escaped`() {
        val risorsa = DavResource(
            segments = listOf("Simon & Garfunkel"),
            isCollection = true,
            displayName = "Simon & Garfunkel",
            lastModified = istante
        )

        val xml = WebDavXml.multiStatus("tok123", listOf(risorsa))

        // Sono due trasformazioni diverse sullo stesso testo: dopo il
        // percent-encoding nell'href non resta alcuna & da escapare.
        assertTrue(xml.contains("<D:href>/tok123/Simon%20%26%20Garfunkel/</D:href>"))
        assertTrue(xml.contains("<D:displayname>Simon &amp; Garfunkel</D:displayname>"))
        assertFalse(xml.contains("<D:displayname>Simon & Garfunkel</D:displayname>"))
    }

    @Test
    fun `i caratteri XML nel displayname sono tutti escapati`() {
        assertEquals("&lt;a&gt; &amp; &lt;b&gt;", WebDavXml.escapeXml("<a> & <b>"))
    }

    @Test
    fun `il percent-encoding lascia passare solo i caratteri unreserved`() {
        assertEquals("abc-XYZ_09.~", WebDavXml.encodeSegment("abc-XYZ_09.~"))
        assertEquals("a%2Fb", WebDavXml.encodeSegment("a/b"))
        assertEquals("%2B%23%3F", WebDavXml.encodeSegment("+#?"))
        // Lo spazio non deve diventare '+': quello vale per i form, non per i path.
        assertEquals("a%20b", WebDavXml.encodeSegment("a b"))
    }

    @Test
    fun `le collection terminano con lo slash e dichiarano il tipo`() {
        val xml = WebDavXml.multiStatus("tok123", listOf(collection("Artista", "Album")))

        assertTrue(xml.contains("<D:href>/tok123/Artista/Album/</D:href>"))
        assertTrue(xml.contains("<D:resourcetype><D:collection/></D:resourcetype>"))
    }

    @Test
    fun `i file non terminano con lo slash e dichiarano lunghezza e content type`() {
        val xml = WebDavXml.multiStatus("tok123", listOf(file("Artista", "Album", "01 - Brano.mp3")))

        assertTrue(xml.contains("<D:href>/tok123/Artista/Album/01%20-%20Brano.mp3</D:href>"))
        assertTrue(xml.contains("<D:resourcetype/>"))
        assertTrue(xml.contains("<D:getcontentlength>4096</D:getcontentlength>"))
        assertTrue(xml.contains("<D:getcontenttype>audio/mpeg</D:getcontenttype>"))
    }

    @Test
    fun `le collection non dichiarano una lunghezza`() {
        val xml = WebDavXml.multiStatus("tok123", listOf(collection("Artista")))

        assertFalse(xml.contains("<D:getcontentlength>"))
    }

    @Test
    fun `ogni href porta il prefisso del token, anche quello della root`() {
        val xml = WebDavXml.multiStatus(
            "tok123",
            listOf(
                DavResource(emptyList(), isCollection = true, displayName = "", lastModified = istante),
                collection("Artista"),
                file("Artista", "Album", "Brano.mp3")
            )
        )

        val href = Regex("<D:href>([^<]*)</D:href>").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(3, href.size)
        assertTrue(href.all { it.startsWith("/tok123/") })
        assertEquals("/tok123/", href.first())
    }

    @Test
    fun `getlastmodified usa il formato RFC 1123 in GMT`() {
        assertEquals("Fri, 14 Jul 2017 02:40:00 GMT", WebDavXml.httpDate(istante))
    }

    @Test
    fun `il multistatus e ben formato e dichiara il namespace DAV`() {
        val xml = WebDavXml.multiStatus("tok123", listOf(collection("Artista")))

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(xml.contains("<D:multistatus xmlns:D=\"DAV:\">"))
        assertTrue(xml.trimEnd().endsWith("</D:multistatus>"))
        assertEquals(1, Regex("<D:response>").findAll(xml).count())
        assertTrue(xml.contains("<D:status>HTTP/1.1 200 OK</D:status>"))
    }

    @Test
    fun `una risposta senza risorse resta un multistatus valido`() {
        val xml = WebDavXml.multiStatus("tok123", emptyList())

        assertTrue(xml.contains("<D:multistatus xmlns:D=\"DAV:\">"))
        assertFalse(xml.contains("<D:response>"))
    }

    @Test
    fun `l'errore di depth infinity dichiara propfind-finite-depth`() {
        val xml = WebDavXml.finiteDepthError()

        assertTrue(xml.contains("<D:error xmlns:D=\"DAV:\"><D:propfind-finite-depth/></D:error>"))
    }
}
