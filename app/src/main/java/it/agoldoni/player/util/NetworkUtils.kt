package it.agoldoni.player.util

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * Utility di rete per la ricezione di brani via Wi-Fi.
 *
 * L'app, durante "Ricevi via Wi-Fi", espone un piccolo server HTTP sulla rete
 * locale: questi helper servono a individuare l'IP del telefono sulla LAN,
 * scegliere una porta libera e generare il token casuale usato come root del
 * server (capability URL).
 */
object NetworkUtils {

    /**
     * Ritorna il primo indirizzo IPv4 site-local (es. 192.168.x.x, 10.x.x.x)
     * di un'interfaccia attiva non di loopback, ovvero l'IP raggiungibile dal
     * PC sulla stessa rete Wi-Fi. Ritorna null se nessuna rete è disponibile.
     */
    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verifica se una porta TCP è libera tentando un bind effimero.
     */
    fun isPortFree(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Ritorna la prima porta libera nell'intervallo [range], o null se nessuna.
     */
    fun firstFreePort(range: IntRange): Int? = range.firstOrNull { isPortFree(it) }

    // Charset url-safe senza caratteri ambigui (niente 0/O/1/l/i/j ecc.).
    private const val TOKEN_CHARS = "abcdefghkmnpqrstuvwxyz23456789"

    /**
     * Genera un token casuale breve usato come segmento root del server HTTP.
     * Funge da "capability URL": chi non conosce il token completo riceve 404.
     */
    fun generateToken(length: Int = 6): String {
        val rnd = SecureRandom()
        return buildString(length) {
            repeat(length) { append(TOKEN_CHARS[rnd.nextInt(TOKEN_CHARS.length)]) }
        }
    }
}
