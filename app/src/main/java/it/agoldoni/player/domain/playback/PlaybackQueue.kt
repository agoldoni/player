package it.agoldoni.player.domain.playback

import it.agoldoni.player.data.local.entity.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sorgente di verità unica della coda di riproduzione (ordine, indice, shuffle).
 *
 * Prima della migrazione a Media3 questa logica era duplicata nei ViewModel di
 * lista/playlist/autore; ora vive in un singleton di processo condiviso sia dai
 * ViewModel (che impostano la coda) sia dal [PlaybackService] (che la fa avanzare
 * quando il brano finisce o si preme "successivo" dalla notifica/lock screen).
 *
 * Tutti i metodi sono `@Synchronized`: vi accedono il thread UI (ViewModel) e il
 * main thread del service.
 *
 * [ownerTag] identifica la schermata che ha avviato la coda corrente
 * (es. `"library"`, `"playlist:<id>"`, `"author:<nome>"`), così che ogni schermata
 * possa sapere se la riproduzione in corso le "appartiene".
 */
@Singleton
class PlaybackQueue @Inject constructor() {

    /** Lista originale (non rimescolata) da cui si ricostruisce l'ordine al toggle shuffle. */
    private var source: List<Track> = emptyList()
    private var order: List<Track> = emptyList()
    private var index: Int = -1
    private var shuffle: Boolean = false

    private val _currentTrackId = MutableStateFlow<String?>(null)
    val currentTrackId: StateFlow<String?> = _currentTrackId.asStateFlow()

    private val _ownerTag = MutableStateFlow<String?>(null)
    val ownerTag: StateFlow<String?> = _ownerTag.asStateFlow()

    /**
     * Imposta una nuova coda e seleziona il brano corrente.
     * @param startTrackId brano da cui partire; se shuffle è attivo viene messo in testa,
     *   altrimenti determina l'indice iniziale. `null` → si parte dall'inizio.
     * @return il brano corrente da riprodurre, o null se [source] è vuota.
     */
    @Synchronized
    fun setQueue(ownerTag: String, source: List<Track>, startTrackId: String?, shuffle: Boolean): Track? {
        this.source = source
        this.shuffle = shuffle
        this.order = buildOrder(source, startTrackId, shuffle)
        this.index = if (!shuffle && startTrackId != null) {
            order.indexOfFirst { it.id == startTrackId }.coerceAtLeast(0)
        } else {
            0
        }
        _ownerTag.value = ownerTag
        val current = order.getOrNull(index)
        _currentTrackId.value = current?.id
        return current
    }

    private fun buildOrder(source: List<Track>, startTrackId: String?, shuffle: Boolean): List<Track> {
        if (source.isEmpty()) return emptyList()
        if (!shuffle) return source
        val start = startTrackId?.let { id -> source.firstOrNull { it.id == id } }
        return if (start != null) {
            listOf(start) + source.filter { it.id != start.id }.shuffled()
        } else {
            source.shuffled()
        }
    }

    /** Brano corrente, o null se la coda è vuota. */
    @Synchronized
    fun current(): Track? = order.getOrNull(index)

    /**
     * Avanza al brano successivo.
     * @param wrap se true, a fine coda riparte dall'inizio (rimescolando se shuffle attivo);
     *   se false ritorna null a fine coda (usato per il fine-brano naturale, che non deve
     *   ripartire in loop).
     * @return il nuovo brano corrente, o null se la coda è finita e wrap è false.
     */
    @Synchronized
    fun moveToNext(wrap: Boolean): Track? {
        if (order.isEmpty() || index < 0) return null
        val next = index + 1
        if (next >= order.size) {
            if (!wrap) return null
            if (shuffle) order = order.shuffled()
            index = 0
        } else {
            index = next
        }
        val current = order.getOrNull(index)
        _currentTrackId.value = current?.id
        return current
    }

    /**
     * Attiva/disattiva lo shuffle riordinando la coda e mantenendo il brano corrente.
     * No-op se la coda è vuota.
     */
    @Synchronized
    fun setShuffle(enabled: Boolean) {
        this.shuffle = enabled
        val current = order.getOrNull(index) ?: return
        order = if (enabled) {
            listOf(current) + source.filter { it.id != current.id }.shuffled()
        } else {
            source
        }
        index = order.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    }

    /** Svuota la coda (stop / fine riproduzione). */
    @Synchronized
    fun clear() {
        source = emptyList()
        order = emptyList()
        index = -1
        shuffle = false
        _ownerTag.value = null
        _currentTrackId.value = null
    }
}
