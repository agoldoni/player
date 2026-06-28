# Controlli player sul lock screen (migrazione a Media3) — Implementation Plan

**Stato:** Bozza — in attesa di approvazione
**Autore:** Alberto Goldoni (alberto.goldoni@gmail.com)
**Data:** 2026-06-28
**Versione:** 1.0
**Feature slug:** `lock-screen-controls`

---

## 1. Executive Summary

Oggi la musica si ferma quando si blocca lo schermo e non esiste alcun controllo dall'esterno
dell'app. Questa feature porta la riproduzione allo standard Android: l'audio continua in
background e su lock screen/notifica compaiono titolo, artista, copertina e i comandi
play/pausa/brano-successivo. Tecnicamente si migra il motore audio da `MediaPlayer` a **Media3
(ExoPlayer + MediaSessionService)**, mantenendo intatta la cifratura a riposo e il gate biometrico
esistenti. Effort stimato ~9–10 giorni/uomo per un singolo sviluppatore.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** la riproduzione è legata al processo UI (`PlaybackManager` →
  `MediaPlayer`); con schermo bloccato/app in background il sistema può sospenderla, e non c'è
  modo di vedere lo stato o comandare il player senza sbloccare il telefono.
- **Metriche di successo:**
  - [ ] La riproduzione continua nel 100% dei casi con schermo bloccato (verifica manuale su API 26/33/34).
  - [ ] Metadati e controlli presenti e coerenti su lock screen e notifica.
  - [ ] Zero crash legati a permessi notifiche negati o processo killato (DEK assente).
  - [ ] Nessun file temporaneo `playback_*` orfano in `cacheDir` dopo l'uso normale.
- **Legame con obiettivi di prodotto:** esperienza utente alla pari di un'app musicale moderna;
  base tecnica (Media3) manutenibile e abilitante per futuri sviluppi (queue avanzata, Auto, cast).

---

## 3. Scope

### Incluso
- Migrazione motore audio da `MediaPlayer` a **ExoPlayer (Media3)**.
- Nuovo **`PlaybackService`** (`MediaSessionService`, foreground `mediaPlayback`) con `MediaSession`.
- **MediaStyle notification** + resa su lock screen con metadati e controlli play/pausa/next.
- Estrazione della coda in un **`PlaybackQueue` singleton condiviso** (decisione approvata in Fase 2).
- Riscrittura interna di `PlaybackManager` come façade su `MediaController`, **preservando l'API
  pubblica** verso i ViewModel.
- Integrazione del flusso **decrypt-to-temp** come sorgente dei `MediaItem`.
- Permessi: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (+ richiesta runtime).
- Gestione **DEK non disponibile dopo kill** → riuso del pattern "Sessione scaduta".

### Escluso (out of scope)
- Pulsante "brano precedente" e seek dal lock screen — *non richiesti per la v1; riducono lo scope
  e il rischio. Riconsiderabili in iterazione successiva.*
- DataSource ExoPlayer che decifra in streaming senza file temporaneo — *ottimizzazione futura;
  il decrypt-to-temp esistente è sufficiente e già validato.*
- Re-autenticazione biometrica avviata dal background — *il `BiometricPrompt` richiede una
  Activity; non fattibile da notifica.*
- Android Auto / Wear / cast / widget — *fuori obiettivo v1.*
- Modifiche al modello di crittografia o al gate biometrico.

### Decisioni risolte
| # | Decisione | Esito | Data |
|---|-----------|-------|------|
| 1 | Semantica di `play()` (vedi §5 Breaking changes) | ✅ Mantenere `Boolean` sincrono basato sul check `sessionDek`, con gestione interna della connessione async del controller | 2026-06-28 |
| 2 | Sorte di `setSkipToNextHandler` | ✅ **Rimuovere subito** a favore di `PlaybackQueue` (nessun ponte temporaneo) | 2026-06-28 |
| 3 | Politica cleanup temp con coda | ✅ **Accettabile** cancellare il temp del brano precedente al `MediaItemTransition` | 2026-06-28 |
| 4 | Unit test `PlaybackQueue` | ✅ **Introdurre** i primi test automatici (+ setup) | 2026-06-28 |

---

## 4. User Stories e criteri di accettazione

### US-001 · Riproduzione continua in background
**Priorità:** Must Have

Come utente voglio che la musica continui quando blocco lo schermo o cambio app, per non
interrompere l'ascolto.

**Criteri di accettazione:**
- [ ] Bloccando lo schermo durante un brano, l'audio prosegue.
- [ ] Passando ad altra app, l'audio prosegue.
- [ ] Durante la riproduzione il service è in foreground con notifica attiva.

### US-002 · Stato del player sul lock screen
**Priorità:** Must Have

Come utente con schermo bloccato voglio vedere titolo, artista e copertina, per sapere cosa sto
ascoltando senza sbloccare.

**Criteri di accettazione:**
- [ ] Su lock screen compaiono titolo, artista e copertina (`Track.albumArtPath`).
- [ ] Avanzamento/durata coerenti con la riproduzione.
- [ ] Al cambio brano i metadati si aggiornano.

### US-003 · Controlli da lock screen / notifica
**Priorità:** Must Have

Come utente voglio play/pausa e brano-successivo dai controlli di sistema, per comandare il player
senza aprire l'app.

**Criteri di accettazione:**
- [ ] Play/pausa da notifica e lock screen, riflessi nella `PlaybackBar`.
- [ ] "Brano successivo" invoca la logica di coda condivisa (anche con ViewModel non vivi).
- [ ] Stato play/pausa sempre coerente tra notifica, lock screen e UI.

### US-004 · Terminazione pulita
**Priorità:** Must Have

Come utente voglio che il player si fermi in modo pulito a fine coda o alla chiusura della notifica.

**Criteri di accettazione:**
- [ ] A fine coda il service esce dal foreground e rimuove la notifica.
- [ ] Dismiss notifica → stop e rilascio risorse.
- [ ] Nessun temp `playback_*` orfano oltre la policy di cleanup.

### US-005 · DEK non disponibile dopo kill
**Priorità:** Should Have

Come utente voglio un comportamento prevedibile quando il processo è stato terminato, per non
incontrare crash.

**Criteri di accettazione:**
- [ ] Comando play/resume da notifica con `sessionDek == null` → nessun crash.
- [ ] L'utente è invitato a riaprire l'app per autenticarsi (riuso pattern "Sessione scaduta").

### US-006 · Permesso notifiche
**Priorità:** Must Have

Come utente su Android 13+ voglio che la richiesta del permesso notifiche sia gestita, per non
avere comportamenti rotti.

**Criteri di accettazione:**
- [ ] Su API 33+ viene richiesto `POST_NOTIFICATIONS`.
- [ ] Il rifiuto non fa crashare l'app; la riproduzione resta funzionante (senza notifica visibile).

---

## 5. Architettura tecnica

### Componenti coinvolti

```
  ViewModel (TrackList/PlaylistDetail/AuthorDetail/TrackDetail)
        │  usa
        ▼
  PlaybackQueue (singleton @Singleton)  ── ordine, indice, shuffle, "next"
        │                                      ▲
        │ play(track/queue)                    │ comandi da notifica/lock screen
        ▼                                      │
  PlaybackManager (façade)  ──async──►  MediaController ──► PlaybackService
        ▲                                            (MediaSessionService)
        │ StateFlow (currentTrackId/isPlaying/...)        │
        │                                                  ├─ ExoPlayer
  PlaybackBarViewModel ──► PlaybackBar (Compose)           ├─ MediaSession  ──► Lock screen / Notifica
                                                           └─ CryptoManager.decryptToTempFile → cacheDir
```

Note di design:
- **`PlaybackQueue`** diventa la sorgente di verità della coda (oggi duplicata in 4 ViewModel).
  Espone l'ordine corrente, l'indice, lo stato shuffle e la funzione `next()`/`current()`. Viene
  iniettato sia nei ViewModel (per `togglePlayTrack`, `toggleShuffle`, ecc.) sia raggiunto dal
  service tramite i comandi della `MediaSession`.
- **`PlaybackManager`** mantiene l'interfaccia attuale ma internamente costruisce e usa un
  `MediaController` connesso a `PlaybackService`; gli StateFlow vengono alimentati da un
  `Player.Listener`.
- **`PlaybackService`** possiede ExoPlayer + `MediaSession`; per ogni brano richiede a
  `CryptoManager.decryptToTempFile` il file in chiaro in `cacheDir` e lo passa come `MediaItem`,
  popolando `MediaMetadata` (titolo/artista/album/artwork da `Track`).

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| — | Nessuna | L'entità `Track` ha già `title/artist/album/duration/albumArtPath/originalExtension`. Nessuna migrazione DB. |

### Nuove API o endpoint
Nessuna API di rete. "Contratti" interni interessati:
- `MediaSession` commands: `PLAY`, `PAUSE`, `SEEK_TO`, `SKIP_TO_NEXT` (mappati su `PlaybackQueue`).
- `PlaybackManager` API pubblica invariata: `play/pause/resume/stop/seekTo/skipToNext` + StateFlow.

### Breaking changes

| Componente | Tipo di breaking change | Piano di migrazione |
|---|---|---|
| `PlaybackManager.play(track): Boolean` | La connessione del `MediaController` è asincrona; il `Boolean` sincrono attuale (usato per `sendSessionExpired()`) non può più riflettere lo stato della connessione | Mantenere la firma: `play()` controlla `cryptoManager.sessionDek` (sincrono, già così) per il `Boolean`; la connessione al service viene garantita prima/coda comandi. La "sessione scaduta" resta sul check DEK, non sulla connessione. |
| `onCompletion` per-brano (callback in `play()`) | ExoPlayer gestisce l'avanzamento via `onMediaItemTransition`/coda | Spostare l'avanzamento automatico in `PlaybackQueue` + listener del player; deprecare il callback per-brano. |
| `setSkipToNextHandler` | La coda non è più "posseduta" dai ViewModel | Sostituito da `PlaybackQueue`; mantenibile come ponte no-op durante la migrazione (Decisione aperta #2). |

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | Aggiungere Media3 al version catalog + `app/build.gradle.kts` | Infra | 0.5 | — |
| T-02 | Manifest: permessi (`FOREGROUND_SERVICE`, `…MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`) + `<service>` `mediaPlayback` | Infra | 0.5 | T-01 |
| T-03 | `PlaybackService` (MediaSessionService) + ExoPlayer che riproduce un file locale in chiaro (validazione foreground + notifica base) | BE | 2.0 | T-02 |
| T-04 | Riscrittura interna `PlaybackManager` → façade su `MediaController`; StateFlow alimentati da `Player.Listener`; preservare API pubblica e `Boolean` di `play()` | BE | 2.0 | T-03 |
| T-05 | `PlaybackQueue` singleton: estrarre ordine/indice/shuffle/next dai 4 ViewModel; aggiornare i ViewModel a usarlo | BE | 1.5 | T-04 |
| T-06 | Integrare decrypt-to-temp come sorgente `MediaItem` + ciclo di vita/cleanup temp con coda | BE | 1.0 | T-04 |
| T-07 | Metadati MediaSession (titolo/artista/album/posizione) + artwork da `albumArtPath` + controlli play/pausa/next | BE | 1.0 | T-05, T-06 |
| T-08 | `MainActivity`: richiesta runtime `POST_NOTIFICATIONS`; binding/avvio controller; gestione rifiuto permesso | FE | 0.5 | T-04 |
| T-09 | Edge case DEK assente dopo kill (no-op + "Sessione scaduta"); dismiss notifica; fine coda | BE | 0.5 | T-07 |
| T-10 | Verifica manuale multi-API (26/33/34) + fix; controllo orfani temp | Test | 1.5 | T-07, T-08, T-09 |
| T-11 | Setup infrastruttura test (JUnit + dipendenze `testImplementation`) + unit test `PlaybackQueue` (shuffle/indice/next/wrap) | Test | 1.0 | T-05 |
| T-12 | Aggiornare `CLAUDE.md` (architettura/playback) + note migrazione | Doc | 0.5 | T-10 |

**Stima totale:** ~12.5 giorni/uomo (cuscinetto rispetto ai ~9.5 della Fase 1, per refactoring coda + setup test).
**Breakdown:** BE 8.0 · FE 0.5 · Infra 1.0 · Test 2.5 · Doc 0.5

> Nota: il progetto **non ha attualmente alcuna infrastruttura di test** (nessun `testImplementation`
> in `app/build.gradle.kts`). T-11 include il setup iniziale (JUnit, eventuale `kotlinx-coroutines-test`).

---

## 7. Piano di test

**Strategia generale:** verifica prevalentemente **manuale** su device/emulatore (API 26, 33, 34),
affiancata dai **primi unit test** introdotti con questa feature (T-11) sulla logica pura di
`PlaybackQueue` (shuffle, avanzamento, wrap a fine coda).

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Manuale | Schermo bloccato → audio continua; metadati + controlli su lock screen | Alta |
| TC-02 | Manuale | Play/pausa/next da notifica coerenti con `PlaybackBar` | Alta |
| TC-03 | Manuale | Avanzamento automatico fine brano e wrap fine coda (con/senza shuffle) | Alta |
| TC-04 | Manuale | Dismiss notifica → stop pulito, nessun temp `playback_*` residuo | Alta |
| TC-05 | Manuale | Permesso `POST_NOTIFICATIONS` negato → nessun crash, audio ok | Alta |
| TC-06 | Manuale | Processo killato → comando da notifica non crasha (DEK assente) | Media |
| TC-07 | Manuale | Bypass biometrico debug su emulatore ancora funzionante | Media |
| TC-08 | Unit | `PlaybackQueue`: shuffle, indice, next, wrap a fine coda | Alta |

### Definition of Done per QA
- [ ] Tutti i TC ad Alta priorità superati su API 26/33/34.
- [ ] Nessun crash nei log durante i flussi critici.
- [ ] Nessun file temp orfano dopo sessione d'uso normale.
- [ ] `CLAUDE.md` aggiornato.
- [ ] Code review approvata.

---

## 8. Rischi e mitigazioni

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| Coda nei 4 ViewModel: "next" da lock screen non funziona | Alta | Alto | `PlaybackQueue` singleton di processo (T-05); raggiungibile dal service via MediaSession |
| `MediaController` async vs `play()` sincrono | Media | Alto | `Boolean` da check DEK (sincrono); coda comandi finché il controller si connette (T-04) |
| DEK assente dopo kill processo | Media | Medio | No-op + "Sessione scaduta" (T-09); nessuna re-auth da background |
| Accumulo/race file temp con coda | Media | Medio | Cleanup al `MediaItemTransition` + `OrphanCleanupUseCase` all'avvio (T-06) |
| Foreground service Android 14 (timeout/tipo) | Media | Alto | Tipo `mediaPlayback` + ingresso foreground tempestivo (T-02/T-03) |
| Resa notifica difforme tra API 26 e 33+ | Bassa | Basso | Test multi-API (T-10) |
| Brani senza copertina | Bassa | Basso | Placeholder artwork |

---

## 9. Rollout e feature flag

**Strategia di rilascio:**
- [x] Deploy diretto (app mono-modulo, distribuzione APK personale; nessuna infra di flag).

**Feature flag:** non previsto (app personale, nessun sistema di flag). Se si volesse mitigare il
rischio, si può tenere temporaneamente il vecchio `MediaPlayer` dietro un flag `BuildConfig`, ma
non è raccomandato (raddoppia il codice di playback).

**Piano di rollback:**
1. `git revert` del commit/branch della feature.
2. Rebuild APK con `./build.sh release` e reinstallazione.
3. Nessuna migrazione DB da invertire (data model invariato).

---

## 10. Checklist di approvazione

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Alberto | ⏳ In attesa | — |
| Revisione prodotto | Alberto | ⏳ In attesa | — |
| Stima approvata | Alberto | ⏳ In attesa | — |
| Rischi accettati | Alberto | ⏳ In attesa | — |
| Data di inizio confermata | Alberto | ⏳ In attesa | — |

---

## Domande aperte

> ✅ Tutte le domande sono state risolte il 2026-06-28 (vedi §3 "Decisioni risolte"). Nessuna
> questione bloccante residua per l'approvazione.

---

*Documento generato con la skill `claude-code-feature`.*
