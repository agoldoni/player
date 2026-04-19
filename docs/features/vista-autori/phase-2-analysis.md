# Feature: `vista-autori` — Analisi tecnica codebase (Fase 2)

**Stato:** in attesa di approvazione per Fase 3
**Data:** 2026-04-18
**Riferimento piano:** [phase-1-plan.md](phase-1-plan.md)

---

## A. File coinvolti

| Percorso | Tipo | Motivazione |
|---|---|---|
| [app/src/main/java/it/agoldoni/player/data/local/dao/TrackDao.kt](../../../app/src/main/java/it/agoldoni/player/data/local/dao/TrackDao.kt) | **modifica** | Aggiungere 2 query: `getDistinctArtistsWithCount(): Flow<List<ArtistSummary>>` e `getTracksByArtist(artist: String): Flow<List<Track>>`. |
| `app/src/main/java/it/agoldoni/player/data/local/entity/ArtistSummary.kt` | **nuovo** | POJO Room (non `@Entity`) con `name: String` e `trackCount: Int` per il risultato della GROUP BY. Annotato eventualmente con `@Ignore` non necessario perché è solo result type. |
| [app/src/main/java/it/agoldoni/player/data/repository/TrackRepository.kt](../../../app/src/main/java/it/agoldoni/player/data/repository/TrackRepository.kt) | **modifica** | Esporre i due nuovi metodi DAO al layer ViewModel. |
| `app/src/main/java/it/agoldoni/player/ui/author/AuthorListScreen.kt` | **nuovo** | Compose screen con `LazyColumn` di `ArtistSummary` (nome + conteggio), pattern coerente con [PlaylistListScreen.kt](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistListScreen.kt). |
| `app/src/main/java/it/agoldoni/player/ui/author/AuthorListViewModel.kt` | **nuovo** | `@HiltViewModel` che espone `StateFlow<List<ArtistSummary>>` da `TrackRepository`. |
| `app/src/main/java/it/agoldoni/player/ui/author/AuthorDetailScreen.kt` | **nuovo** | Compose screen read-only: header (nome artista + conteggio), pulsanti Play/Shuffle/SkipNext (riuso pattern da [PlaylistDetailScreen.kt](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistDetailScreen.kt)), `LazyColumn` tracce **senza** azione di rimozione. |
| `app/src/main/java/it/agoldoni/player/ui/author/AuthorDetailViewModel.kt` | **nuovo** | `@HiltViewModel` con stessa logica di playback di [PlaylistDetailViewModel.kt](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistDetailViewModel.kt) (decrypt → MediaPlayer → cleanup, shuffle, skip), parametrizzato sull'artista invece che su `playlistId`. **Niente** `lastPlayedTrackId` (non c'è entità persistente). |
| [app/src/main/java/it/agoldoni/player/ui/navigation/PlayerNavGraph.kt](../../../app/src/main/java/it/agoldoni/player/ui/navigation/PlayerNavGraph.kt) | **modifica** | Aggiungere `Screen.AuthorList` (`"author_list"`) e `Screen.AuthorDetail` (`"author_detail/{artistName}"` + helper `createRoute`); registrare i due `composable` nel `NavHost`. |
| [app/src/main/java/it/agoldoni/player/ui/PlayerApp.kt](../../../app/src/main/java/it/agoldoni/player/ui/PlayerApp.kt) | **modifica** | Aggiungere `NavigationDrawerItem` "Autori" nel `ModalDrawerSheet` (icona `Icons.Default.Person` o `Icons.Default.Groups`). |
| [app/src/main/java/it/agoldoni/player/data/local/PlayerDatabase.kt](../../../app/src/main/java/it/agoldoni/player/data/local/PlayerDatabase.kt) | **nessuna** | Nessuna migrazione DB richiesta: lo schema `tracks` ha già il campo `artist`. |

> Nessun file da eliminare. Nessuna modifica a Hilt `DatabaseModule` (le nuove dipendenze passano da `TrackRepository` già provvisto).

---

## B. Contratti e interfacce da modificare

### B.1 — Nuovo DTO: `ArtistSummary`

```kotlin
data class ArtistSummary(
    val name: String,
    val trackCount: Int
)
```

### B.2 — Nuove query in `TrackDao`

```kotlin
@Query("""
    SELECT artist AS name, COUNT(*) AS trackCount
    FROM tracks
    GROUP BY artist
    ORDER BY artist COLLATE NOCASE ASC
""")
fun getDistinctArtistsWithCount(): Flow<List<ArtistSummary>>

@Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY title COLLATE NOCASE ASC")
fun getTracksByArtist(artist: String): Flow<List<Track>>
```

> **Note**:
> - `Track.artist` è `String` non-nullable (vedi [Track.kt:13](../../../app/src/main/java/it/agoldoni/player/data/local/entity/Track.kt#L13)), quindi non occorre gestire `NULL` in SQL — solo eventuali stringhe vuote.
> - `COLLATE NOCASE` per ordinamento case-insensitive coerente con il piano.

### B.3 — Nuovi metodi in `TrackRepository`

```kotlin
fun getDistinctArtistsWithCount(): Flow<List<ArtistSummary>> =
    trackDao.getDistinctArtistsWithCount()

fun getTracksByArtist(artist: String): Flow<List<Track>> =
    trackDao.getTracksByArtist(artist)
```

### B.4 — Navigazione

In [PlayerNavGraph.kt:19-32](../../../app/src/main/java/it/agoldoni/player/ui/navigation/PlayerNavGraph.kt#L19-L32), aggiungere alla `sealed class Screen`:

```kotlin
object AuthorList : Screen("author_list")
object AuthorDetail : Screen("author_detail/{artistName}") {
    fun createRoute(artistName: String) = "author_detail/${Uri.encode(artistName)}"
}
```

> **⚠️ Breaking constraint:** il nome dell'artista come argomento di rotta deve essere **URL-encoded** (e decoded a destinazione con `Uri.decode`) perché può contenere `/`, `?`, `&`, spazi, accenti. Pattern non già usato nel progetto (gli altri arg sono UUID), va aggiunto da zero.

### B.5 — Nessun breaking change su DB / API esistenti

L'introduzione è puramente additiva: nessuna entità modificata, nessuna migrazione, nessun consumer rotto.

---

## C. Pattern da rispettare (osservati nella codebase)

1. **Architettura:** Clean Architecture con MVVM, già usata ovunque. Layer `data/dao` → `data/repository` → `ui/<feature>/<Feature>ViewModel` → `ui/<feature>/<Feature>Screen`.
2. **Hilt:** ViewModel annotati `@HiltViewModel` con `@Inject constructor`, repository `@Singleton @Inject` (vedi [TrackRepository.kt:9-12](../../../app/src/main/java/it/agoldoni/player/data/repository/TrackRepository.kt#L9-L12)).
3. **Reattività:** ogni list-screen consuma `Flow` esposto come `StateFlow` con `stateIn(scope, SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())` (vedi [PlaylistListViewModel.kt:25-31](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistListViewModel.kt#L25-L31)).
4. **Eventi UI one-shot:** `Channel<XxxEvent>(Channel.BUFFERED)` esposto come `receiveAsFlow()`, raccolto in `LaunchedEffect(Unit)` con `snackbarHostState.showSnackbar(...)`. Pattern standard in tutti i ViewModel ([PlaylistDetailViewModel.kt:65-66](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistDetailViewModel.kt#L65-L66)).
5. **Naming Compose:** `XxxScreen` (composable pubblico) + `XxxItem` privato per le righe della lista. Le icone usano `Icons.Default.*` / `Icons.AutoMirrored.Filled.*`.
6. **Layout schermo lista:** `Scaffold` con `TopAppBar` (titolo + icona Menu sinistra che chiama `onOpenDrawer`), `LazyColumn` con `items(list, key = { it.id })` e `HorizontalDivider()` tra elementi, `Box` centrato con messaggio quando lista vuota.
7. **Stringhe in Italiano:** tutti i testi UI e i log di codice sono in italiano (cfr. `CLAUDE.md` e [PlaylistListScreen.kt:77](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistListScreen.kt#L77), `"Le mie Playlist"`).
8. **Playback (riferimento [PlaylistDetailViewModel.kt:90-212](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistDetailViewModel.kt#L90-L212)):**
   - Decrypt via `cryptoManager.decryptToTempFile(dek, encryptedFile)` su `Dispatchers.IO`.
   - `MediaPlayer` con `AudioAttributes` USAGE_MEDIA, `setOnCompletionListener` che richiama `playTrackAt(nextIndex, ...)`.
   - `releasePlayer()` + `cleanupTempFile()` in `onCleared()`.
   - Stati: `_isPlaying`, `_shuffleEnabled`, `_currentPlayingTrackId` come `MutableStateFlow`.
   - DEK ottenuta da `cryptoManager.sessionDek` (già unlockata dal `BiometricGateScreen` all'avvio).

---

## D. Test da creare o aggiornare

Nel progetto **non c'è una test suite configurata** (cfr. `CLAUDE.md` → "No test suite is currently configured"). Quindi:

- **Nessun unit/integration test automatico** da creare/modificare in questa feature.
- **Test manuali obbligatori** prima del bump versione, da documentare nel piano di Fase 3:
  - Lista autori popolata e ordinata (case-insensitive).
  - Conteggio per artista corretto.
  - Tap su artista apre il dettaglio con tracce filtrate corrette.
  - Play sequenziale: scorre tutte le tracce, si ferma a fine lista.
  - Random play: ordine diverso a ogni avvio, copre tutte le tracce.
  - Tap su singola traccia in dettaglio autore: avvia da quella.
  - Import di una nuova traccia di un artista esistente: il conteggio si aggiorna live.
  - Import di una nuova traccia di un artista nuovo: l'artista compare live.
  - Eliminazione di tutte le tracce di un artista: l'artista scompare dalla lista.
  - Nome artista con caratteri speciali (`/`, `&`, accenti, spazi): la rotta funziona, il dettaglio carica le tracce giuste (verifica encoding URL).
  - Artista stringa vuota: comportamento atteso (vedi rischio E.1).

---

## E. Rischi tecnici aggiornati (post-analisi)

### E.1 — Artista con stringa vuota o whitespace ⚠️ DECISIONE RICHIESTA
**Conferma dalla Fase 1:** `Track.artist` è `String` non-nullable ([Track.kt:13](../../../app/src/main/java/it/agoldoni/player/data/local/entity/Track.kt#L13)), quindi non c'è il caso `NULL`. Restano però:
- `""` (stringa vuota, possibile se [MetadataExtractor.kt](../../../app/src/main/java/it/agoldoni/player/domain/MetadataExtractor.kt) usa fallback vuoto).
- Spazi/whitespace (es. `" "`).

**Opzioni**:
- (a) Mostrare gruppo "Sconosciuto" raggruppando le stringhe vuote/blank.
- (b) Escludere dalla lista autori (`WHERE TRIM(artist) != ''`).
- (c) Lasciare così (riga vuota visibile in cima alla lista NOCASE).

**Raccomandazione:** opzione (a) — più user-friendly. Da decidere in approvazione Fase 3.

### E.2 — Encoding URL del nome artista (rischio nuovo, non in Fase 1)
Le altre rotte usano UUID ([PlayerNavGraph.kt:25-27](../../../app/src/main/java/it/agoldoni/player/ui/navigation/PlayerNavGraph.kt#L25-L27)), sicuri per l'URL. Il nome artista invece è user content e può contenere `/`, `?`, `#`, `&`, spazi, accenti.

**Mitigazione:** `Uri.encode()` in `createRoute` e `Uri.decode()` nel `SavedStateHandle` consumer del `AuthorDetailViewModel`. Da testare manualmente con caratteri sporchi.

### E.3 — Performance `GROUP BY artist` senza indice
Lo schema `tracks` non ha indice su `artist` (vedi [PlayerDatabase.kt](../../../app/src/main/java/it/agoldoni/player/data/local/PlayerDatabase.kt) — nessuna `CREATE INDEX` su tracks).

- Su libreria <1000 tracce: trascurabile.
- Su libreria >5000: `GROUP BY` su colonna non indicizzata può diventare percepibile su entry della schermata.

**Mitigazione:** rimandata a v1.1. Se necessaria, aggiungere `@Entity(tableName = "tracks", indices = [Index("artist")])` con migrazione `5→6`.

### E.4 — Riuso del playback layer
**Conferma:** la logica di playback in [PlaylistDetailViewModel.kt:148-212](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistDetailViewModel.kt#L148-L212) è strettamente accoppiata a `playlistId` e `playlistRepository.updateLastPlayedTrackId(...)`. Le opzioni:
- (a) **Duplicare** la logica in `AuthorDetailViewModel`, omettendo la persistenza di `lastPlayedTrackId` (~80 LOC duplicate, ma zero refactor sul codice esistente).
- (b) **Estrarre** un `TrackQueuePlayer` riusabile in `domain/` o `ui/playback/` parametrizzato su `List<Track>` + callback opzionale di "lastPlayed".

**Raccomandazione:** opzione (a) per la v1 (più rapida, basso rischio di regressioni sulla playlist esistente). L'estrazione (opzione b) è un nice-to-have da fare quando arriverà una **terza** vista che riproduce playlist (es. shuffle globale, "tutti gli album"). Decisione da confermare nel documento Fase 3.

### E.5 — Concorrenza dello shuffle e del cleanup
La logica di `playTrackAt` di [PlaylistDetailViewModel.kt:148](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistDetailViewModel.kt#L148) lancia una `viewModelScope.launch { withContext(Dispatchers.IO) { ... } }` per ogni traccia: tap rapidi successivi possono lasciare temp file orfani (mitigato da `OrphanCleanupUseCase` allo startup). Lo stesso comportamento sarà ereditato dalla copia.

**Note:** non è una regressione introdotta da questa feature, è il pattern esistente. Non risolvere qui.

---

## F. Prerequisiti e task bloccanti

**Nessun prerequisito bloccante.** Tutti i layer richiesti esistono già:
- `Track` con campo `artist` ✅
- `CryptoManager` con `decryptToTempFile` ✅
- `TrackRepository` iniettabile ✅
- Pattern playback completo riusabile da [PlaylistDetailViewModel](../../../app/src/main/java/it/agoldoni/player/ui/playlist/PlaylistDetailViewModel.kt) ✅
- Sistema di drawer + nav graph estendibile ✅

**Decisioni pendenti (non bloccanti per iniziare, bloccanti per chiudere):**
1. Comportamento artisti vuoti (E.1).
2. Duplicazione vs estrazione playback layer (E.4).
3. Eventuale UI extra: pulsante "Tutti" in lista autori per ascoltare l'intera libreria? **Out of scope** per ora.

**Task suggerito da fare prima dell'M3 (UI):** confermare le decisioni 1 e 2 per evitare rework.

---

> ✅ **Fase 2 completata.** Analisi salvata in [docs/features/vista-autori/phase-2-analysis.md](phase-2-analysis.md).
> Verifica che percorsi e file citati esistano (tutti i link sono linkabili nel render Markdown).
> Conferma per procedere con la generazione del documento di implementazione finale (Fase 3).
