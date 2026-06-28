# Fase 2 — Analisi tecnica: Controlli player sul lock screen (Media3)

> Feature slug: `lock-screen-controls`
> Basata su lettura diretta della codebase (commit `c987fc7`).
> Default adottati da Fase 1: brano-precedente/seek-da-notifica fuori scope; DEK non
> disponibile dopo kill → notifica invita a riaprire l'app.

## A. File coinvolti

| File | Tipo | Motivazione |
|------|------|-------------|
| `app/build.gradle.kts` | modifica | aggiungere dipendenze Media3 (`media3-exoplayer`, `media3-session`) |
| `gradle/libs.versions.toml` | modifica | versione + alias librerie Media3 |
| `app/src/main/AndroidManifest.xml` | modifica | permessi `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`; dichiarazione `<service>` Media3 con `foregroundServiceType="mediaPlayback"` |
| `domain/playback/PlaybackService.kt` | **nuovo** | `MediaSessionService` (o `MediaLibraryService`) con ExoPlayer + `MediaSession` |
| `domain/playback/PlaybackQueue.kt` (o estensione di PlaybackManager) | **nuovo** | sorgente di verità della coda (ordine, indice, shuffle) raggiungibile dal service — vedi sez. E rischio #1 |
| `domain/PlaybackManager.kt` | **riscrittura sostanziale** | da wrapper di `MediaPlayer` a façade su `MediaController` connesso al service; mantiene l'attuale API (`play/pause/resume/stop/seekTo/skipToNext/setSkipToNextHandler` + StateFlow) per non toccare i 5 ViewModel chiamanti |
| `domain/CryptoManager.kt` | modifica minore | esporre `decryptToTempFile` al service (già pubblico); valutare cleanup centralizzato dei temp `playback_*` |
| `MainActivity.kt` | modifica | richiesta runtime `POST_NOTIFICATIONS` (API 33+); eventuale avvio/binding del `MediaController` |
| `PlayerApplication.kt` | possibile modifica | inizializzazione/cleanup (orphan cleanup già presente) |
| `di/PlaybackModule.kt` | **nuovo (opz.)** | provider Hilt per ExoPlayer/MediaSession se non costruiti dentro il service |
| `ui/playback/PlaybackBarViewModel.kt` | modifica lieve / nessuna | resta invariato se `PlaybackManager` mantiene la stessa interfaccia |
| `ui/tracklist/TrackListViewModel.kt`, `ui/playlist/PlaylistDetailViewModel.kt`, `ui/author/AuthorDetailViewModel.kt`, `ui/trackdetail/TrackDetailViewModel.kt` | modifica (dipende da scelta architetturale) | oggi possiedono la coda; se la coda si sposta nel layer playback, qui cambia la logica `playTrackAt`/`skipToNext` |

## B. Contratti e interfacce da modificare

**`PlaybackManager` — interfaccia pubblica attuale** (da preservare il più possibile):
- StateFlow: `currentTrackId: StateFlow<String?>`, `isPlaying`, `durationMs`, `positionMs`
  (consumati da `PlaybackBarViewModel` e da `ownsPlayback`/`currentTrackId` nei ViewModel).
- Metodi: `play(track, onCompletion): Boolean`, `pause()`, `resume()`, `stop()`,
  `seekTo(Int)`, `skipToNext()`, `setSkipToNextHandler((() -> Unit)?)`.

> **Vincolo forte di compatibilità:** mantenere questa firma identica permette di riscrivere
> solo l'implementazione interna (MediaPlayer → MediaController) **senza toccare i 5 ViewModel**.
> Punto debole: `MediaController` si connette **in modo asincrono** al service, mentre l'attuale
> `play()` è sincrono e ritorna `Boolean` immediatamente. → Possibile **breaking change semantico**:
> `play()` dovrà gestire la connessione pendente (coda comando, oppure restare sospeso). Da
> decidere in Fase 3.

**`onCompletion` callback** (riga 74, usato da `playTrackAt`): con ExoPlayer la fine brano e
l'avanzamento si gestiscono via `Player.Listener.onMediaItemTransition` / coda ExoPlayer nativa,
non più con un callback per-brano. → cambia il modello di "prossimo brano".

**Nessun contratto DB/API esterno cambia.** L'entità `Track` (`data/local/entity/Track.kt`) ha già
tutto il necessario per i metadati MediaSession: `title`, `artist`, `album`, `duration`,
`albumArtPath` (path locale alla copertina → usabile come artwork notifica), `originalExtension`.

## C. Pattern da rispettare

- **Hilt**: tutti i singleton via `@Singleton @Inject constructor` (vedi `PlaybackManager`,
  `CryptoManager`). Il service dovrà essere `@AndroidEntryPoint`; provider eventuali in un
  `@Module @InstallIn(SingletonComponent::class)` come `di/DatabaseModule.kt`.
- **State management**: `MutableStateFlow` privati + `asStateFlow()` pubblici (pattern in
  `PlaybackManager`). La `PlaybackBar` legge via `collectAsStateWithLifecycle`.
- **Coroutine**: `CoroutineScope(SupervisorJob() + Dispatchers.X)` come in `PlaybackManager`
  (riga 32) e `PlayerApplication`.
- **Decrypt-to-temp**: file temp in `context.cacheDir` con prefisso `playback_` ed estensione
  originale (`CryptoManager.decryptToTempFile`, righe 196-219). Cleanup attuale: `cleanup()` in
  `PlaybackManager` cancella il temp a fine/stop; `OrphanCleanupUseCase` pulisce all'avvio.
- **Commenti in italiano** (convenzione di progetto).
- **Gestione sessione scaduta**: già esiste il pattern `play()` → `false` → `sendSessionExpired()`
  → evento `ShowError("Sessione scaduta, riavvia l'app")` (TrackListViewModel righe 160-161,
  235-238). Riusare questo stesso pattern per il caso DEK non disponibile dal service.

## D. Test da creare o aggiornare

Il progetto **non ha suite di test automatici** (confermato: nessuna dir `test`/`androidTest`
con test significativi; CLAUDE.md lo dichiara). La verifica è manuale su device/emulatore.

Checklist di verifica manuale (da eseguire su API 26, 33 e 34):
- Riproduzione continua con schermo bloccato e con app in background.
- Metadati (titolo/artista/copertina) + avanzamento visibili su lock screen.
- Play/pausa/next da notifica e lock screen coerenti con `PlaybackBar`.
- Avanzamento automatico a fine brano e a fine coda (con/senza shuffle).
- Dismiss notifica → stop pulito, nessun temp `playback_*` orfano in `cacheDir`.
- Permesso `POST_NOTIFICATIONS` negato → nessun crash, riproduzione comunque funzionante.
- Processo killato → comando da notifica non crasha (DEK assente gestita).
- Bypass biometrico debug su emulatore ancora funzionante (`canBypassBiometric`).

> Opportunità: introdurre i primi unit test (es. logica della coda/shuffle estratta in una classe
> testabile come `PlaybackQueue`). Opzionale, non bloccante.

## E. Rischi tecnici aggiornati (con evidenze)

1. **[ALTO] La coda di riproduzione vive nei ViewModel, non nel layer playback.**
   `playbackOrder`, `currentPlaybackIndex`, `_shuffleEnabled` e la logica `playTrackAt`/`skipToNext`
   sono in **4 ViewModel** (`TrackListViewModel` 165-233, `PlaylistDetailViewModel`,
   `AuthorDetailViewModel`; `TrackDetailViewModel` riproduce singolo). Oggi funziona perché
   `PlaybackManager.setSkipToNextHandler { skipToNext() }` rimanda al ViewModel vivo. **Dal
   lock screen / notifica il ViewModel può non essere vivo** → "next" non funzionerebbe.
   *Mitigazione:* spostare la coda in un componente di processo (es. `PlaybackQueue` singleton o
   coda nativa ExoPlayer popolata con tutti i `MediaItem`). È il refactoring più impattante.

2. **[ALTO] `MediaController` è asincrono; `PlaybackManager.play()` è sincrono e ritorna `Boolean`.**
   La connessione al `MediaSessionService` richiede `MediaController.Builder(...).buildAsync()`.
   Va gestita la finestra tra richiesta-comando e controller-pronto senza rompere i 5 chiamanti
   che usano il `Boolean` di ritorno per `sendSessionExpired()`.

3. **[MEDIO] DEK in memoria di processo (`CryptoManager.sessionDek`).** Essendo `CryptoManager`
   un `@Singleton`, il service nello stesso processo **vede la stessa `sessionDek`** finché il
   processo vive → con app in background e schermo bloccato la decifratura funziona. Dopo **kill
   del processo**, `sessionDek == null`: `decryptToTempFile` non è invocabile. *Mitigazione
   (default Fase 1):* comando da notifica con DEK nulla → no-op + notifica/azione che invita a
   riaprire l'app (riusa pattern "sessione scaduta"). Niente re-auth da background (il
   `BiometricPrompt` richiede una Activity).

4. **[MEDIO] Ciclo di vita del file temp decifrato con coda ExoPlayer.** Oggi un solo temp per
   volta, cancellato in `cleanup()`. Con avanzamento gestito dal player serve decidere quando
   decifrare il brano successivo e quando cancellare il precedente, evitando accumulo in
   `cacheDir` e race. `OrphanCleanupUseCase` resta rete di sicurezza all'avvio.

5. **[MEDIO] Foreground service su Android 14 (target 34).** Obbligatori
   `FOREGROUND_SERVICE_MEDIA_PLAYBACK` e tipo `mediaPlayback`; il service deve entrare in
   foreground entro i tempi previsti, altrimenti `ForegroundServiceStartNotAllowedException`.

6. **[BASSO] `minSdk = 26`.** Media3 supporta API 21+, nessun problema; verificare resa notifica
   su API 26 vs 33+ (canali notifica già obbligatori da 26).

7. **[BASSO] Artwork notifica.** `Track.albumArtPath` è un path a file locale: caricarlo come
   `Bitmap`/`Uri` per `MediaMetadata.artworkUri`/`artworkData`. Brani senza copertina → placeholder.

8. **[BASSO] Convivenza con server Ktor embedded** (`ktor-server-*` in dipendenze): non
   interferisce, ma verificare che non ci siano conflitti di foreground service multipli.

## F. Prerequisiti e task bloccanti

1. **Decisione architetturale sulla coda (rischio #1)** — *bloccante*. Due opzioni:
   - **(a) Coda nativa ExoPlayer**: caricare i `MediaItem` della libreria/playlist nel player;
     shuffle e next gestiti da ExoPlayer. Più idiomatico, ma richiede di pre-risolvere le sorgenti
     (decrypt-to-temp lazy per item) e di replicare la logica shuffle attuale.
   - **(b) `PlaybackQueue` singleton di processo** che incapsula `playbackOrder`/indice/shuffle,
     iniettato sia nei ViewModel sia nel service; il service chiama la stessa logica per "next".
     Meno invasivo sul modello mentale attuale, mantiene il decrypt-to-temp brano-per-brano.
2. **Decisione su semantica `play()` sincrona vs asincrona (rischio #2)** — *bloccante* per
   l'integrazione coi ViewModel.
3. **Aggiunta dipendenze Media3** al version catalog — prerequisito tecnico semplice.
4. **Setup canale notifica + permesso `POST_NOTIFICATIONS`** — prerequisito per M3.
5. Nessun refactoring DB necessario (entità `Track` già sufficiente).

> ⚠️ DA DECIDERE in Fase 3: opzione (a) vs (b) per la coda; semantica di `play()`; se mantenere
> `setSkipToNextHandler` come ponte temporaneo o rimuoverlo del tutto.
