# vista-autori — Implementation Plan

**Stato:** Bozza — in attesa di approvazione
**Autore:** Alberto Goldoni (alberto.goldoni@euei.it)
**Data:** 2026-04-18
**Versione:** 1.0
**Riferimenti:** [phase-1-plan.md](phase-1-plan.md) · [phase-2-analysis.md](phase-2-analysis.md)

---

## 1. Executive Summary

Aggiunta di una vista "Autori" all'app player Android: una sezione navigabile dal drawer principale che elenca tutti gli artisti presenti in libreria con il numero di tracce, e per ogni artista una schermata di dettaglio read-only che permette di riprodurre tutte le sue tracce in ordine sequenziale o casuale. La vista si aggiorna in tempo reale e non richiede manutenzione manuale (nessuna playlist da creare). Stima: ~3 giorni/uomo, nessuna migrazione DB, nessun breaking change.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** oggi per ascoltare "tutto di un artista" l'utente deve scorrere/filtrare la `TrackList` o creare manualmente una playlist dedicata, operazione ripetitiva e fragile (le nuove tracce importate non si aggiungono automaticamente alla playlist).
- **Metriche di successo:** non sono definite metriche quantitative misurabili in-app (nessuna telemetria implementata). Successo qualitativo:
  - [ ] L'utente riesce ad avviare "tutto di X" in ≤ 3 tap dalla home (drawer → Autori → tap artista → Play).
  - [ ] Le nuove tracce importate compaiono automaticamente sotto il loro artista senza riavvio.
- **Legame con obiettivi di prodotto:** miglioramento dell'UX di navigazione della libreria, allineamento ai pattern dei player musicali commerciali (library-by-artist).

---

## 3. Scope

### Incluso
- Schermata `AuthorListScreen`: elenco di artisti distinti, ordine alfabetico case-insensitive, conteggio tracce per artista.
- Schermata `AuthorDetailScreen`: tracce filtrate per artista, header con nome + conteggio, pulsanti Play / Shuffle / SkipNext, riproduzione singola traccia su tap, **read-only** (nessun delete/edit).
- Voce "Autori" nel `ModalDrawerSheet` di [PlayerApp.kt](../../../app/src/main/java/it/agoldoni/player/ui/PlayerApp.kt).
- Gestione tracce con `artist` blank/empty: raggruppate sotto etichetta **"Sconosciuto"** (decisione approvata, vedi §3 → Decisioni risolte).
- Aggiornamento reattivo via `Flow` Room.

### Escluso (out of scope)
- Modifica/rimozione tracce dalla vista autore (read-only by design).
- Persistenza della vista come playlist materializzata in DB.
- Album view o raggruppamento per album dentro l'autore (nice-to-have v1.1).
- Artwork dell'artista (si usa solo l'artwork delle tracce).
- Ricerca/filtro testuale dentro la lista autori (rimandato a v1.1).
- Merge di varianti dello stesso artista (es. "The Beatles" vs "Beatles") — solo trim/case-insensitive a livello di query.
- Persistenza di `lastPlayedTrackId` per autore (esiste solo per playlist).
- Estrazione di un layer `TrackQueuePlayer` riusabile (vedi §3 → Decisioni risolte, opzione duplicazione).
- Indice DB su `tracks.artist` (rimandato a v1.1 se la performance lo richiederà).

### Decisioni risolte

| # | Decisione | Esito | Razionale |
|---|-----------|-------|-----------|
| 1 | Gestione `artist` vuoto/whitespace | **Gruppo "Sconosciuto"** | Più user-friendly: non perdere tracce, comportamento prevedibile. |
| 2 | Riuso playback layer da `PlaylistDetailViewModel` | **Duplicazione** in `AuthorDetailViewModel` | Velocità di consegna v1, zero rischio regressione su playlist esistenti. Estrazione rimandata a quando arriverà una terza vista che riproduce code di tracce. |

### Decisioni aperte

Nessuna decisione bloccante residua. Eventuali domande secondarie in §11.

---

## 4. User Stories e criteri di accettazione

### US-001 · Lista degli artisti
**Priorità:** Must Have

Come utente voglio vedere l'elenco di tutti gli artisti presenti nella mia libreria per navigare rapidamente la collezione per autore.

**Criteri di accettazione:**
- [ ] La schermata mostra tutti i valori distinti di `tracks.artist`, raggruppando le stringhe blank/empty sotto l'etichetta `"Sconosciuto"`.
- [ ] Ogni riga mostra: nome artista (1 linea con ellipsis se overflow) + numero di tracce.
- [ ] Ordinamento alfabetico case-insensitive (`COLLATE NOCASE`); il gruppo "Sconosciuto" segue lo stesso ordinamento (finirà dove cade alfabeticamente).
- [ ] La lista si aggiorna in tempo reale (flusso Room) all'import o eliminazione di tracce, senza riavvio app.
- [ ] Stato lista vuota: messaggio centrato `"Nessun artista in libreria. Importa tracce per iniziare."`.

### US-002 · Dettaglio autore
**Priorità:** Must Have

Come utente voglio toccare un artista e vedere tutte le sue tracce per scegliere cosa ascoltare senza cercare nella lista globale.

**Criteri di accettazione:**
- [ ] La schermata mostra le tracce dell'artista selezionato, ordinate per `title COLLATE NOCASE ASC`.
- [ ] `TopAppBar` con nome artista come titolo + back button.
- [ ] Sotto la `TopAppBar`, header riga con `"N tracce"` (es. `"12 tracce"`).
- [ ] Tap su una riga traccia naviga a `TrackDetail/{trackId}` (coerente con `TrackList`).
- [ ] Nessun pulsante delete/edit/remove visibile sulla riga.
- [ ] Per il gruppo "Sconosciuto", il filtro DB usa `WHERE TRIM(artist) = '' OR artist IS NULL` (anche se schema dichiara non-null).

### US-003 · Riproduzione sequenziale e random
**Priorità:** Must Have

Come utente voglio avviare la riproduzione (sequenziale o random) di tutte le tracce di un artista con un tap per ascoltare "tutto di X" senza dover creare una playlist dedicata.

**Criteri di accettazione:**
- [ ] Pulsante Play in `TopAppBar` avvia la riproduzione sequenziale dalla prima traccia visualizzata.
- [ ] Pulsante Shuffle (toggle) inverte modalità: se attivo prima del Play, la coda parte mescolata.
- [ ] Pulsante SkipNext appare solo durante la riproduzione e salta alla traccia successiva.
- [ ] La traccia attualmente in riproduzione è evidenziata in lista (bold + icona PlayArrow + container colorato), come in [PlaylistDetailScreen.kt:184-190](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistDetailScreen.kt#L184-L190).
- [ ] A fine coda: `_isPlaying = false`, `_currentPlayingTrackId = null`, cleanup file temp.
- [ ] Errore di playback (sessione DEK scaduta) → snackbar `"Sessione scaduta, riavvia l'app"`.
- [ ] Decrypt → MediaPlayer → cleanup identico al pattern di `PlaylistDetailViewModel`.

### US-004 · Aggiornamento reattivo
**Priorità:** Must Have

Come utente voglio che le nuove tracce importate compaiano automaticamente sotto il loro artista per non dover mantenere manualmente la vista.

**Criteri di accettazione:**
- [ ] Importando una traccia di artista esistente con app aperta su `AuthorListScreen`, il conteggio si incrementa entro pochi secondi (latency Room `Flow`).
- [ ] Importando una traccia di artista nuovo, l'artista compare in lista nella posizione alfabetica corretta.
- [ ] Eliminando l'ultima traccia di un artista, l'artista scompare dalla lista.

### US-005 · Caratteri speciali nel nome artista
**Priorità:** Should Have

Come utente con tracce di artisti dai nomi "sporchi" (slash, e-commerciale, accenti, spazi) voglio che la navigazione funzioni comunque.

**Criteri di accettazione:**
- [ ] Tap su artista `"AC/DC"` apre il dettaglio corretto.
- [ ] Tap su artista `"Sigur Rós"` apre il dettaglio corretto.
- [ ] Tap su artista `"Simon & Garfunkel"` apre il dettaglio corretto.
- [ ] Implementazione: `Uri.encode()` in `Screen.AuthorDetail.createRoute(...)` e `Uri.decode()` nel consumer del `SavedStateHandle` di `AuthorDetailViewModel`.

---

## 5. Architettura tecnica

### Componenti coinvolti

```
┌──────────────────────────────────────────────────────────────────┐
│ UI (Compose)                                                     │
│                                                                  │
│  Drawer ─► AuthorListScreen ──tap─► AuthorDetailScreen           │
│                  │                          │                    │
│                  ▼                          ▼                    │
│           AuthorListVM              AuthorDetailVM               │
│              │                       │  (playback duplicato      │
│              │                       │   da PlaylistDetailVM)    │
└──────────────┼───────────────────────┼───────────────────────────┘
               │                       │
               ▼                       ▼
        TrackRepository ──► TrackDao (Room) ──► tracks (no migrazione)
                       │
                       └─► CryptoManager (decrypt → MediaPlayer)
```

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `tracks` (DB) | Nessuna | Lo schema è già adeguato. Nessuna migrazione `5→6`. |
| `ArtistSummary` (Kotlin) | Nuovo POJO | `data class ArtistSummary(val name: String, val trackCount: Int)` — usato come result type Room. |

### Nuove API o endpoint

App offline, niente API HTTP. Le "API" interne nuove sono i metodi DAO/repository:

| Layer | Firma | Descrizione |
|---|---|---|
| `TrackDao` | `fun getDistinctArtistsWithCount(): Flow<List<ArtistSummary>>` | `GROUP BY artist` con `COUNT(*)`, ordine `COLLATE NOCASE`. Mappa whitespace/empty su `"Sconosciuto"` via `CASE`. |
| `TrackDao` | `fun getTracksByArtist(artist: String): Flow<List<Track>>` | `WHERE artist = :artist` (oppure `WHERE TRIM(artist) = '' OR artist IS NULL` per il gruppo "Sconosciuto" — gestito con un secondo metodo dedicato `getUnknownArtistTracks()`). |
| `TrackRepository` | wrapper dei due metodi sopra | Coerente con il pattern esistente ([TrackRepository.kt](../../../app/src/main/java/it/agoldoni/player/data/repository/TrackRepository.kt)). |

**Implementazione SQL "Sconosciuto" (proposta):**

```kotlin
@Query("""
    SELECT
        CASE WHEN TRIM(artist) = '' THEN 'Sconosciuto' ELSE artist END AS name,
        COUNT(*) AS trackCount
    FROM tracks
    GROUP BY name
    ORDER BY name COLLATE NOCASE ASC
""")
fun getDistinctArtistsWithCount(): Flow<List<ArtistSummary>>

@Query("""
    SELECT * FROM tracks
    WHERE (:artist = 'Sconosciuto' AND TRIM(artist) = '')
       OR (:artist != 'Sconosciuto' AND artist = :artist)
    ORDER BY title COLLATE NOCASE ASC
""")
fun getTracksByArtist(artist: String): Flow<List<Track>>
```

> ⚠️ La stringa letterale `'Sconosciuto'` accoppia il DAO alla UI. Alternativa più pulita: definire una `const val UNKNOWN_ARTIST = "Sconosciuto"` in un oggetto condiviso (`data/local/dao/TrackDao.kt` companion object o file `domain/Constants.kt`). Decisione lasciata al dev in fase implementativa, comunque non bloccante.

### Modifiche di navigazione

In [PlayerNavGraph.kt](../../../app/src/main/java/it/agoldoni/player/ui/navigation/PlayerNavGraph.kt):

```kotlin
object AuthorList : Screen("author_list")
object AuthorDetail : Screen("author_detail/{artistName}") {
    fun createRoute(artistName: String) = "author_detail/${Uri.encode(artistName)}"
}
```

Nel composable: `arguments = listOf(navArgument("artistName") { type = NavType.StringType })`. Nel ViewModel: `val artistName: String = Uri.decode(savedStateHandle["artistName"]!!)`.

### Breaking changes

Nessuno. Feature interamente additiva.

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | `ArtistSummary` POJO + 2 query in `TrackDao` (con gestione "Sconosciuto") | Data | 0.25 | — |
| T-02 | Wrapper in `TrackRepository` | Data | 0.1 | T-01 |
| T-03 | `AuthorListViewModel` (Hilt, `StateFlow<List<ArtistSummary>>`) | UI logic | 0.25 | T-02 |
| T-04 | `AuthorListScreen` (Compose, pattern `PlaylistListScreen`, no FAB, no long-press) | UI | 0.5 | T-03 |
| T-05 | Aggiungere `Screen.AuthorList` + `Screen.AuthorDetail` (con `Uri.encode/decode`) al `NavGraph` | UI | 0.25 | T-04 |
| T-06 | Aggiungere voce "Autori" al `ModalDrawerSheet` in `PlayerApp.kt` | UI | 0.1 | T-05 |
| T-07 | `AuthorDetailViewModel` (duplicazione playback da `PlaylistDetailViewModel`, senza `lastPlayedTrackId`) | UI logic | 0.75 | T-02 |
| T-08 | `AuthorDetailScreen` (Compose, pattern `PlaylistDetailScreen` ma read-only: niente Add/Remove/long-press) | UI | 0.5 | T-07, T-05 |
| T-09 | Test manuale completo (US-001 → US-005, casi `"AC/DC"`, `""`, libreria vuota, import live) | Test | 0.25 | T-08 |
| T-10 | Bump `versionCode`/`versionName` in `app/build.gradle.kts`, commit, build APK debug | Release | 0.1 | T-09 |

**Stima totale:** ~3.0 giorni/uomo
**Breakdown:** Data 0.35 · UI logic 1.0 · UI 1.35 · Test 0.25 · Release 0.1

> ⚠️ **Niente "FE separato"** (single-module Android). Niente "BE" (offline app). Stima leggermente più bassa rispetto alla Fase 1 (3–4 gg) perché la decisione "duplicazione" di E.4 evita il refactor del playback layer.

---

## 7. Piano di test

**Strategia generale:** test esclusivamente **manuali** — il progetto non ha test suite automatica (cfr. `CLAUDE.md`). Tutti i test sono eseguiti su device fisico/emulatore con build `debug`. La coverage automatica non è applicabile.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Manuale | Lista autori popolata, ordine NOCASE corretto, conteggio coerente con `SELECT COUNT(*) FROM tracks WHERE artist = ?` | Alta |
| TC-02 | Manuale | Tap su un artista apre il dettaglio con esattamente le tracce dell'artista (verifica con esportazione CSV) | Alta |
| TC-03 | Manuale | Play sequenziale: scorre tutte le tracce dell'artista in ordine, si ferma a fine lista | Alta |
| TC-04 | Manuale | Random play: ordine diverso ad ogni avvio, copre tutte le tracce | Alta |
| TC-05 | Manuale | Tap su singola traccia in dettaglio: naviga a `TrackDetail` corretto | Alta |
| TC-06 | Manuale | Import live di nuova traccia: lista autori si aggiorna senza riavvio | Alta |
| TC-07 | Manuale | Eliminazione di tutte le tracce di un artista: l'artista scompare | Alta |
| TC-08 | Manuale | Artista `"AC/DC"`: rotta funziona, dettaglio carica le tracce giuste | Alta |
| TC-09 | Manuale | Artista `"Sigur Rós"` (accento): rotta + dettaglio OK | Media |
| TC-10 | Manuale | Artista `""` (blank): raggruppato sotto "Sconosciuto", dettaglio carica le tracce blank | Alta |
| TC-11 | Manuale | Libreria vuota: messaggio "Nessun artista in libreria…" | Media |
| TC-12 | Manuale | Sessione DEK scaduta (forzata uccidendo l'app e ripristinando): tap Play → snackbar errore | Media |
| TC-13 | Manuale | Rotazione device durante riproduzione: stato playback non si rompe (ViewModel sopravvive) | Bassa |

### Definition of Done per QA

- [ ] Tutti i TC critici (priorità Alta) passano su un device fisico Android (API ≥ 26).
- [ ] Build `./build.sh debug` produce APK senza warning nuovi.
- [ ] Nessun crash durante TC-01..TC-13 (verifica con `adb logcat`).
- [ ] Nessun temp file orfano dopo riproduzione completa (verifica con `adb shell run-as it.agoldoni.player ls cache/`).
- [ ] Codice e commenti in italiano (vedi `CLAUDE.md`).
- [ ] Revisione manuale dell'autore (no PR review configurata su questo progetto).

---

## 8. Rischi e mitigazioni

| # | Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|---|
| R-01 | Encoding URL del nome artista non gestito → crash o tracce sbagliate per artisti con `/`, `&`, accenti | Media | Alto | `Uri.encode/decode` esplicito in `createRoute` e nel `SavedStateHandle` consumer. TC-08, TC-09. |
| R-02 | Performance `GROUP BY artist` su libreria > 5000 tracce (nessun indice su `tracks.artist`) | Bassa (libreria attuale piccola) | Medio | Non risolto in v1. Se emerge in produzione, aggiungere indice + migrazione `5→6` in v1.1. |
| R-03 | Duplicazione del playback (~80 LOC ripetute) crea drift se la logica playlist evolve | Media | Basso | Documentato come scelta consapevole nel doc. Refactor previsto quando arriverà la 3ª vista che riproduce code. |
| R-04 | Stringa letterale `"Sconosciuto"` accoppia DAO a UI italiana | Bassa | Basso | Estraibile in costante condivisa al primo bisogno di i18n. |
| R-05 | Artisti con varianti tipografiche ("Beatles" vs "The Beatles") restano duplicati | Media | Basso | **Out of scope v1**. Comunicato esplicitamente nello scope. |
| R-06 | Tap rapidi su Play/Skip lasciano temp file orfani (eredità del pattern playlist) | Bassa | Basso | `OrphanCleanupUseCase` già attivo allo startup → file ripuliti al successivo avvio. Nessuna azione qui. |

---

## 9. Rollout e feature flag

**Strategia di rilascio:** Deploy diretto. Non esistono feature flag nel progetto e l'app è installata localmente (single-user, no store distribution gestita in CI). Il nuovo codice viene rilasciato con il prossimo bump di `versionCode`/`versionName`.

**Piano di rollback:**
1. `git revert` del commit della feature.
2. Rebuild APK debug e re-install (`./build.sh debug` + `adb install -r`).
3. **Nessuna migrazione DB da revertire** (la feature non tocca lo schema).
4. **Nessun dato utente perso**: la feature è puramente di lettura, non modifica `tracks` né playlist.

---

## 10. Checklist di approvazione

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Alberto Goldoni (solo dev) | ⏳ In attesa | — |
| Revisione prodotto | Alberto Goldoni (anche owner) | ⏳ In attesa | — |
| Stima approvata | Alberto Goldoni | ⏳ In attesa | — |
| Rischi accettati | Alberto Goldoni | ⏳ In attesa | — |
| Data di inizio confermata | Alberto Goldoni | ⏳ In attesa | — |

> Progetto solo-developer: il workflow di approvazione formale collassa sull'autore. La checklist resta per tracciabilità della decisione.

---

## 11. Domande aperte

Nessuna domanda bloccante.

Domande secondarie non bloccanti (decisione rimandabile in fase implementativa):
1. Estrarre la stringa `"Sconosciuto"` in costante condivisa o lasciarla inline nel `@Query`? (Estetica codice, non funzionale.)
2. Nome esatto della voce nel drawer: `"Autori"` o `"Artisti"`? Default proposto: **"Autori"** (coerente con il nome della feature). Decisione finale al primo render UI.
3. Icona da usare nel drawer per la voce "Autori": `Icons.Default.Person`, `Icons.Default.Groups`, o `Icons.Default.Mic`? Default proposto: `Icons.Default.Person`.

---

*Documento generato con la skill `claude-code-feature`.*
