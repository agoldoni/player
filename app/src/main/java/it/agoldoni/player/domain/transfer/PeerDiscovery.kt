package it.agoldoni.player.domain.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PeerDiscovery"

/**
 * Annuncio e scoperta del mittente sulla rete locale via mDNS ([NsdManager],
 * parte del framework: nessuna dipendenza aggiuntiva).
 *
 * Serve solo a evitare all'utente di digitare un IP: il trasferimento resta
 * possibile inserendo host, porta e codice di accesso a mano, perché su alcune
 * reti il multicast è filtrato (AP isolation, Wi-Fi ospiti) e mDNS non arriva.
 *
 * `NsdManager.resolveService` accetta una richiesta per volta — una seconda
 * risoluzione in parallelo fallisce con `FAILURE_ALREADY_ACTIVE` — quindi le
 * risoluzioni sono messe in coda e servite una alla volta.
 */
@Singleton
class PeerDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Un mittente trovato sulla rete, pronto per il pairing. */
    data class Peer(
        val device: String,
        val host: String,
        val port: Int,
        val token: String
    )

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _discoveryError = MutableStateFlow<String?>(null)
    val discoveryError: StateFlow<String?> = _discoveryError.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Token del servizio pubblicato da questo device: serve a non scoprire sé stessi. */
    @Volatile
    private var ownToken: String? = null

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    // ------------------------------------------------------------- mittente

    /** Annuncia il server di trasferimento sulla rete. Idempotente. */
    fun register(port: Int, token: String) {
        if (registrationListener != null) return
        ownToken = token

        val info = NsdServiceInfo().apply {
            serviceName = "Player ${TransferSelectionResolver.deviceName()}"
            serviceType = TRANSFER_SERVICE_TYPE
            this.port = port
            setAttribute(TXT_TOKEN, token)
            setAttribute(TXT_DEVICE, TransferSelectionResolver.deviceName())
            setAttribute(TXT_VERSION, PROTOCOL_VERSION.toString())
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Servizio registrato: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registrazione mDNS fallita (codice $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Servizio deregistrato")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Deregistrazione mDNS fallita (codice $errorCode)")
            }
        }

        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.w(TAG, "Impossibile registrare il servizio mDNS", it)
                registrationListener = null
            }
    }

    fun unregister() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        ownToken = null
    }

    // --------------------------------------------------------- destinatario

    /** Comincia a cercare mittenti sulla rete. Idempotente. */
    fun startDiscovery() {
        if (discoveryListener != null) return
        _peers.value = emptyList()
        _discoveryError.value = null

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Ricerca avviata")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                enqueueResolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                _peers.update { peers -> peers.filterNot { it.device == info.serviceName } }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Ricerca terminata")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Avvio ricerca fallito (codice $errorCode)")
                _discoveryError.value =
                    "Ricerca automatica non disponibile su questa rete: inserisci i dati a mano."
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop ricerca fallito (codice $errorCode)")
            }
        }

        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(TRANSFER_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.w(TAG, "Impossibile avviare la ricerca mDNS", it)
            discoveryListener = null
            _discoveryError.value =
                "Ricerca automatica non disponibile su questa rete: inserisci i dati a mano."
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
    }

    // ------------------------------------------------------------- risoluzione

    private fun enqueueResolve(info: NsdServiceInfo) {
        if (info.serviceType?.trimEnd('.') != TRANSFER_SERVICE_TYPE.trimEnd('.')) return
        synchronized(resolveQueue) {
            resolveQueue.add(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            val candidate = resolveQueue.poll()
            if (candidate == null) resolving = false
            candidate
        } ?: return

        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Risoluzione di ${info.serviceName} fallita (codice $errorCode)")
                resolveNext()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                addPeer(info)
                resolveNext()
            }
        })
    }

    private fun addPeer(info: NsdServiceInfo) {
        val attributes = info.attributes ?: emptyMap()
        val token = attributes[TXT_TOKEN]?.toString(Charsets.UTF_8)
        val version = attributes[TXT_VERSION]?.toString(Charsets.UTF_8)?.toIntOrNull()
        val device = attributes[TXT_DEVICE]?.toString(Charsets.UTF_8)
            ?: info.serviceName
            ?: "Telefono sconosciuto"
        val host = info.host?.hostAddress

        if (token == null || host == null) return
        // Il proprio annuncio non è un peer, e una versione diversa non è utilizzabile.
        if (token == ownToken) return
        if (version != null && version != PROTOCOL_VERSION) return

        val peer = Peer(device = device, host = host, port = info.port, token = token)
        _peers.update { peers ->
            if (peers.any { it.token == peer.token && it.host == peer.host }) peers
            else peers + peer
        }
    }
}
