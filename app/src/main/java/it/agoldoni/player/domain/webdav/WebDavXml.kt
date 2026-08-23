package it.agoldoni.player.domain.webdav

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Una risorsa da riportare in una risposta `207 Multi-Status`.
 *
 * [segments] è il percorso **in chiaro**, senza il token: l'encoding avviene in
 * [WebDavXml.href] una volta sola, così non esiste un secondo punto del codice
 * che possa sbagliarlo.
 */
data class DavResource(
    val segments: List<String>,
    val isCollection: Boolean,
    val displayName: String,
    val contentLength: Long = 0,
    val lastModified: Long = 0,
    val contentType: String? = null
)

/**
 * Costruzione delle risposte XML di WebDAV (RFC 4918), senza dipendenze Android.
 *
 * Il punto delicato sono gli `href`, ed è la causa classica dei bug WebDAV con i
 * nomi non ASCII. Servono **due** trasformazioni distinte, da non confondere:
 *
 * 1. ogni segmento del percorso va **percent-encoded** (spazi, accenti, `&`, `#`);
 * 2. il testo XML risultante va **XML-escaped**.
 *
 * In quest'ordine. Dopo il punto 1 nell'href non resta alcun `&` da escapare —
 * l'escaping serve davvero per `displayname`, che invece il percorso non lo è.
 * Applicarlo comunque all'href non costa nulla e rende l'ordine esplicito.
 *
 * Altre due regole che i client danno per scontate: l'href deve includere il
 * prefisso `/{token}`, altrimenti rclone perde il token navigando; e le
 * collection devono terminare con `/`, altrimenti vengono trattate come file.
 */
object WebDavXml {

    private const val XML_DECLARATION = """<?xml version="1.0" encoding="utf-8"?>"""
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX = "0123456789ABCDEF"

    /** RFC 1123 con giorno a due cifre e ora in GMT, come vuole `getlastmodified`. */
    private val HTTP_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)

    fun multiStatus(token: String, resources: List<DavResource>): String = buildString {
        append(XML_DECLARATION).append('\n')
        append("""<D:multistatus xmlns:D="DAV:">""").append('\n')
        for (resource in resources) appendResponse(token, resource)
        append("</D:multistatus>").append('\n')
    }

    /** Corpo del `403` con cui si rifiuta `Depth: infinity` (RFC 4918 §9.1). */
    fun finiteDepthError(): String = buildString {
        append(XML_DECLARATION).append('\n')
        append("""<D:error xmlns:D="DAV:"><D:propfind-finite-depth/></D:error>""").append('\n')
    }

    fun href(token: String, segments: List<String>, isCollection: Boolean): String {
        val path = (listOf(token) + segments.filter { it.isNotEmpty() })
            .joinToString("/") { encodeSegment(it) }
        return if (isCollection) "/$path/" else "/$path"
    }

    fun httpDate(epochMillis: Long): String = HTTP_DATE.format(Instant.ofEpochMilli(epochMillis))

    /**
     * Percent-encoding di un singolo segmento: passa solo l'insieme *unreserved*
     * di RFC 3986, tutto il resto diventa `%XX` sui byte UTF-8. Non si usa
     * `URLEncoder`, che è pensato per i form: codificherebbe lo spazio come `+`
     * e lascerebbe passare `*`.
     */
    internal fun encodeSegment(segment: String): String = buildString {
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in UNRESERVED) {
                append(char)
            } else {
                append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
            }
        }
    }

    /** Escaping del testo XML in un solo passaggio: nessun rischio di doppio escape. */
    internal fun escapeXml(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }

    private fun StringBuilder.appendResponse(token: String, resource: DavResource) {
        append("  <D:response>").append('\n')
        append("    <D:href>")
            .append(escapeXml(href(token, resource.segments, resource.isCollection)))
            .append("</D:href>").append('\n')
        append("    <D:propstat>").append('\n')
        append("      <D:prop>").append('\n')
        if (resource.isCollection) {
            append("        <D:resourcetype><D:collection/></D:resourcetype>").append('\n')
        } else {
            append("        <D:resourcetype/>").append('\n')
        }
        append("        <D:displayname>")
            .append(escapeXml(resource.displayName))
            .append("</D:displayname>").append('\n')
        append("        <D:getlastmodified>")
            .append(httpDate(resource.lastModified))
            .append("</D:getlastmodified>").append('\n')
        if (!resource.isCollection) {
            append("        <D:getcontentlength>")
                .append(resource.contentLength)
                .append("</D:getcontentlength>").append('\n')
            resource.contentType?.let {
                append("        <D:getcontenttype>")
                    .append(escapeXml(it))
                    .append("</D:getcontenttype>").append('\n')
            }
        }
        append("      </D:prop>").append('\n')
        append("      <D:status>HTTP/1.1 200 OK</D:status>").append('\n')
        append("    </D:propstat>").append('\n')
        append("  </D:response>").append('\n')
    }
}
