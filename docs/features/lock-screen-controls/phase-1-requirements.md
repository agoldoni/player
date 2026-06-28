# Fase 1 — Requisiti: Controlli player sul lock screen (migrazione a Media3)

> Feature slug: `lock-screen-controls`
> Stato: bozza requisiti — in attesa di conferma per Fase 2

## 1. Obiettivo e motivazione

Oggi la riproduzione audio è gestita da `PlaybackManager` (singleton Hilt) che incapsula un
`android.media.MediaPlayer` legato al processo della UI. Non esistono `Service`, `MediaSession`
né notifiche. Conseguenze:

- Quando lo schermo si blocca o l'app va in background, il sistema può sospendere/uccidere il
  processo e **la riproduzione si interrompe**.
- **Non c'è alcuno stato del player visibile sul lock screen** né controlli rapidi
  (play/pausa/brano successivo) da notifica o schermata di blocco.

L'obiettivo è portare l'app allo standard Android per i media player: riproduzione che continua
in background e **controlli + metadati visibili su lock screen e notifica**, migrando da
`MediaPlayer` a **Media3 (ExoPlayer + MediaSessionService)**.

Valore: esperienza utente allineata a qualsiasi app musicale; possibilità di controllare la
riproduzione senza sbloccare il telefono; base tecnica moderna e mantenibile.

## 2. Scope

### Incluso
- Migrazione del motore di riproduzione da `MediaPlayer` a **ExoPlayer (Media3)**.
- Introduzione di un **`MediaSessionService`** (foreground service per media playback).
- **`MediaSession`** che espone metadati (titolo, artista, album, copertina, durata, posizione,
  stato play/pause) e riceve i comandi dal sistema.
- **MediaStyle notification** con controlli play/pausa e brano successivo; resa automatica sul
  lock screen da parte del sistema.
- Permessi manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`
  (con relativo flusso di richiesta runtime su Android 13+).
- Adattamento di `PlaybackBar` / `PlaybackBarViewModel` per leggere lo stato dal nuovo player.
- Adattamento del meccanismo `skipToNext` (oggi via handler in `TrackListViewModel` /
  `PlaylistDetailViewModel`).
- Integrazione del flusso **decrypt-to-temp** (`CryptoManager.decryptToTempFile`) con il nuovo
  player.
- Gestione del caso **DEK non disponibile** dopo kill del processo (riproduzione/ripresa da
  notifica quando la chiave biometrica non è più sbloccata).

### Escluso (out of scope)
- Pulsanti "brano precedente", seek da lock screen avanzato, coda/queue completa multi-brano a
  livello di MediaSession (oltre l'attuale modello single-track + skipToNext handler), a meno che
  non emerga necessario in Fase 2.
- Casting / Android Auto / Wear / widget home screen.
- DataSource ExoPlayer custom che decifra in streaming senza file temporaneo (valutato come
  possibile evoluzione futura, non in questa iterazione).
- Modifiche al modello di crittografia a riposo o al gate biometrico esistente.
- Riproduzione gapless, equalizzatore, velocità variabile.

## 3. User Stories

1. **Come** utente che ascolta musica **voglio** che la riproduzione continui quando blocco lo
   schermo o passo ad un'altra app **per** non interrompere l'ascolto.
2. **Come** utente con lo schermo bloccato **voglio** vedere titolo, artista e copertina del brano
   in riproduzione **per** sapere cosa sto ascoltando senza sbloccare il telefono.
3. **Come** utente **voglio** mettere in pausa/riprendere e passare al brano successivo dai
   controlli del lock screen / notifica **per** comandare il player senza aprire l'app.
4. **Come** utente **voglio** che, alla chiusura della notifica o a fine playlist, il player si
   fermi in modo pulito **per** non lasciare un service o una notifica orfani.
5. **Come** utente **voglio** un comportamento prevedibile quando l'app è stata terminata dal
   sistema (chiave biometrica non più sbloccata) **per** non incontrare crash o brani che non
   partono silenziosamente.

## 4. Criteri di accettazione

User Story 1 — continuità in background
- [ ] Avviando un brano e bloccando lo schermo, l'audio prosegue.
- [ ] Passando ad un'altra app, l'audio prosegue.
- [ ] Il service gira in foreground con notifica attiva durante la riproduzione.

User Story 2 — metadati su lock screen
- [ ] Sul lock screen compaiono titolo, artista e copertina del brano corrente.
- [ ] La posizione/durata si aggiornano (barra di avanzamento del sistema coerente).
- [ ] Al cambio brano i metadati si aggiornano.

User Story 3 — controlli da lock screen / notifica
- [ ] Play/pausa dalla notifica e dal lock screen funzionano e si riflettono nella `PlaybackBar`.
- [ ] "Brano successivo" dalla notifica invoca la stessa logica di `skipToNext` attuale.
- [ ] Lo stato (play/pausa) mostrato è sempre coerente tra notifica, lock screen e UI in-app.

User Story 4 — terminazione pulita
- [ ] A fine riproduzione (nessun brano successivo) il service esce dallo stato foreground e la
      notifica viene rimossa.
- [ ] Lo swipe/dismiss della notifica ferma la riproduzione e rilascia le risorse.
- [ ] Nessun file temporaneo decifrato resta orfano oltre la policy attuale di cleanup.

User Story 5 — DEK non disponibile dopo kill
- [ ] Se il processo è stato killato e la DEK non è sbloccata, un comando di play/resume da
      notifica non causa crash.
- [ ] Comportamento definito e documentato (es. la notifica richiede di riaprire l'app per
      autenticarsi, oppure i controlli vengono disabilitati). Scelta da confermare in Fase 2/3.

Permessi
- [ ] Su Android 13+ viene richiesto `POST_NOTIFICATIONS`; il rifiuto non fa crashare l'app.
- [ ] Su Android 14 (target SDK 34) il foreground service parte con tipo `mediaPlayback`.

## 5. Rischi e dipendenze

Rischi tecnici (da raffinare in Fase 2):
- **Decrypt-to-temp + ExoPlayer**: occorre fornire a ExoPlayer un `MediaItem` che punti al file
  temporaneo decifrato; gestione del ciclo di vita del file temp (creazione prima del play,
  cleanup a fine/cambio brano) più complessa di adesso con i media in background.
- **Gate biometrico / DEK**: `CryptoManager.decryptToTempFile` dipende dalla DEK sbloccata
  all'avvio. Dopo kill del processo, un comando da notifica può trovare la DEK non disponibile →
  serve una strategia esplicita (no-op + invito a riaprire, o restart con re-auth).
- **Ciclo di vita Service ↔ singleton Hilt**: oggi `PlaybackManager` è singleton di processo e gli
  handler `skipToNext` sono registrati dai ViewModel. Spostare la sorgente di verità nel
  `MediaSessionService`/`MediaController` richiede di ridisegnare chi "possiede" lo stato.
- **skipToNext basato su handler**: la logica di prossimo brano vive in `TrackListViewModel` /
  `PlaylistDetailViewModel`. Da notifica/lock screen questi ViewModel possono non essere vivi →
  la logica di sequenza va resa raggiungibile dal service.
- **Compatibilità versioni**: comportamento foreground service e notifiche diverso tra API 26–34;
  test su più livelli necessario.
- **Hilt + Service**: serve `@AndroidEntryPoint` sul service e provider Media3 nei moduli DI.

Dipendenze:
- Aggiunta librerie **Media3** (`media3-exoplayer`, `media3-session`, `media3-ui` se serve) al
  Gradle del modulo `app`.
- Nessuna dipendenza esterna di backend.

## 6. Stima effort

Stima preliminare (1 sviluppatore), da consolidare dopo la Fase 2:

| Area | Giorni/uomo | Note |
|------|-------------|------|
| Setup Media3 + permessi + manifest | 0.5 | dipendenze, permessi, dichiarazione service |
| MediaSessionService + ExoPlayer (sostituzione PlaybackManager) | 2.5 | cuore della migrazione |
| Integrazione decrypt-to-temp + cleanup file temp | 1.0 | ciclo vita file, MediaItem |
| MediaSession metadata + notifica + copertina | 1.0 | metadati, artwork, controlli |
| Riconnessione PlaybackBar/ViewModel via MediaController | 1.0 | stato UI dal nuovo player |
| skipToNext raggiungibile dal service | 1.0 | ridisegno ownership sequenza brani |
| Gestione DEK non disponibile dopo kill | 0.5 | strategia + edge case |
| Test manuali multi-API + fix | 1.5 | nessuna suite automatica nel progetto |
| Documentazione (CLAUDE.md, note migrazione) | 0.5 | aggiornare architettura |
| **Totale** | **~9.5 g/u** | ± dopo Fase 2 |

> Nota: il progetto non ha attualmente una suite di test automatici; il "Test" è prevalentemente
> verifica manuale su device/emulatore.

## 7. Milestones

1. **M0 — Setup**: aggiungere dipendenze Media3, permessi manifest, dichiarazione del service.
2. **M1 — Motore**: implementare `MediaSessionService` con ExoPlayer che riproduce un file locale
   (anche in chiaro, per validare il service in foreground + notifica base).
3. **M2 — Crittografia**: integrare il flusso decrypt-to-temp come sorgente del `MediaItem`, con
   gestione del ciclo di vita del file temporaneo.
4. **M3 — Metadati & controlli**: popolare la `MediaSession` (titolo/artista/copertina/posizione)
   e abilitare play/pausa/next su notifica e lock screen.
5. **M4 — UI in-app**: riconnettere `PlaybackBar`/`PlaybackBarViewModel` al nuovo player via
   `MediaController`, mantenendo la parità di funzioni attuali.
6. **M5 — skipToNext**: rendere la logica di prossimo brano raggiungibile dal service
   (ridisegno dell'attuale handler nei ViewModel).
7. **M6 — Edge cases**: DEK non disponibile dopo kill, dismiss notifica, fine playlist, cleanup.
8. **M7 — Test & docs**: verifica su API 26/33/34, fix, aggiornamento documentazione.
