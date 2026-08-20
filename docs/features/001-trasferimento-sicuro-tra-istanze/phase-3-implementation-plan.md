# Trasferimento sicuro della libreria tra due istanze — Implementation Plan

**Stato:** Approvato — decisioni chiuse il 2026-08-20, implementazione in corso
**Autore:** Alberto Goldoni
**Data:** 2026-08-20
**Versione:** 1.1

Documenti collegati: [Fase 1 — Requisiti](./phase-1-requirements.md) · [Fase 2 — Analisi tecnica](./phase-2-analysis.md)

---

## 1. Executive Summary

Oggi la libreria musicale è prigioniera del telefono su cui è stata importata: i brani sono cifrati
con una chiave che non esce dal Keystore del dispositivo, quindi cambiare telefono significa
ricominciare da zero e perdere playlist e cronologia di import. Questa feature aggiunge un
trasferimento **da telefono a telefono sulla stessa rete Wi-Fi**: un device mostra un codice a 6
cifre, l'altro lo conferma, e la libreria (file audio, metadati, copertine e playlist) viene copiata
cifrata da un capo all'altro, senza passare da PC né da cloud. Stima: **13,25 giorni/uomo**,
rilasciabile come singola versione dell'app.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** la libreria non è migrabile né recuperabile. I file in
  `filesDir/tracks/` sono cifrati con la DEK, wrappata da una KEK non esportabile in
  AndroidKeystore: copiarli su un altro device produce file indecifrabili. Cambio telefono,
  reset di fabbrica o smarrimento = perdita totale di brani, playlist e date di import.
- **Metriche di successo:** misurate sulla prima migrazione reale a due device.
  - [ ] Una libreria di ~200 brani (~1,5 GB) viene trasferita per intero con **0 errori**
  - [ ] Throughput sostenuto ≥ **3 MB/s** su Wi-Fi 5 a 2 device fermi sulla stessa rete
  - [ ] **100%** dei brani trasferiti conserva `title`, `artist`, `album`, `duration`, `year`,
        `trackNumber`, `originalExtension`, `originalFileSize` e `importedAt` dell'origine
  - [ ] **100%** delle playlist ricostruite con gli stessi brani e nessun riferimento rotto
  - [ ] **0 file in chiaro** residui in `cacheDir` al termine del trasferimento e dopo un riavvio
  - [ ] Al rilancio dopo interruzione, **0 brani già presenti** vengono riscaricati
- **Legame con obiettivi di prodotto:** l'app custodisce l'unica copia di una libreria personale.
  Senza una via d'uscita dai dati, la cifratura a riposo si trasforma da garanzia in rischio.
  Questa feature chiude il ciclo di vita del dato (import → uso → migrazione).

---

## 3. Scope

### Incluso

- Modalità **"Invia libreria"**: il device sorgente espone un server HTTP sulla LAN e mostra il codice di verifica.
- Modalità **"Ricevi libreria"**: il device destinazione trova il peer, conferma il codice e scarica.
- **Pairing autenticato**: ECDH effimero (secp256r1) + HKDF-SHA256 → chiave di sessione AES-256-GCM;
  codice a 6 cifre derivato dal transcript (SAS) e confrontato dall'utente sui due schermi.
- **Manifest cifrato** con metadati completi dei brani, copertine e playlist (con relazioni).
- **Ricifratura per hop**: DEK mittente → chiave di sessione → DEK destinatario. Nessun dato in
  chiaro sulla rete; sul disco solo temporanei di durata breve, come già per FTP e upload Wi-Fi.
- **Dedup lato destinatario** su `(title, artist, album)`, coerente con gli altri flussi di import.
- **Selezione dei contenuti**: intera libreria, playlist selezionate o brani selezionati.
- **Progresso e riepilogo** su entrambi i lati; **ripresa** dopo interruzione garantita dalla dedup.
- Due schermate Compose, voci nel drawer, cleanup dei temporanei, documentazione.

### Escluso (out of scope)

- **Trasferimento di DEK/KEK** — ogni installazione mantiene le proprie chiavi: si migra il
  contenuto, non l'identità crittografica. Copiare la KEK non è tecnicamente possibile.
- **Trasferimento della configurazione FTP** — decisione esplicita del committente: le credenziali
  restano legate al device.
- **Sincronizzazione bidirezionale o continua** — il flusso è one-shot e unidirezionale; nessun
  merge né risoluzione di conflitti oltre la dedup.
- **Trasferimento via Internet o relay cloud** — fuori dal modello di minaccia: la feature vive
  sulla LAN, dove il traffico non attraversa terze parti.
- **Bluetooth / Wi-Fi Direct / Nearby Connections** — throughput inadeguato o dipendenze aggiuntive
  (Google Play Services) che il progetto non ha.
- **Backup su file verso PC** — feature diversa; il formato manifest qui definito la renderà però
  molto più economica in futuro.
- **Trasferimento in background** — coerente con sync FTP e upload Wi-Fi: nessun foreground service,
  l'operazione vive finché la schermata è aperta.
- **Cancellazione della libreria sul mittente** al termine della migrazione.

### Decisioni approvate

Tutte le decisioni sono state chiuse il **2026-08-20** adottando le raccomandazioni tecniche
formulate in Fase 2 (§F.9) e in questo documento.

| # | Decisione | Esito |
|---|---|---|
| 1 | Client HTTP | **`ktor-client-cio` 2.3.12** — stessa versione e stesso modello del server già in uso |
| 2 | Serializzazione manifest | **`kotlinx-serialization-json`** — `org.json` è uno stub negli unit test JVM e il progetto non ha Robolectric |
| 3 | Ambito del cleartext | **`network_security_config.xml` dedicata**, con commento che motiva la scelta; niente `usesCleartextTraffic` globale |
| 4 | `importedAt` | **Preservato** dal manifest: è la ragione principale del nuovo entry point di import |
| 5 | `lastPlayedTrackId` | **Rimappato** se il brano esiste sul destinatario, altrimenti `null` |
| 6 | Playlist omonima | **Fusione** nella playlist esistente: i cross-ref usano `OnConflictStrategy.IGNORE`, l'operazione è idempotente |
| 7 | Direzione del flusso | **Mittente = server**, riuso quasi 1:1 della struttura di `UploadServer` |
| 8 | Verifica del pairing | **SAS a 6 cifre derivata dal transcript ECDH**, confrontata a vista e confermata sui due lati: l'entropia della chiave non dipende dal codice |
| 9 | Config FTP | **Esclusa in via definitiva** per questa versione. Il manifest è versionato (`protocolVersion`), quindi un'eventuale estensione futura non richiede campi placeholder oggi |
| 10 | Stima | **13,25 gg bottom-up approvata**, scope completo: la discovery automatica (T-13) resta dentro |

## 4. User Stories e criteri di accettazione

### US-001 · Migrazione su telefono nuovo
**Priorità:** Must Have

Come utente che ha appena comprato un telefono nuovo, voglio trasferire l'intera libreria dal
vecchio device al nuovo tramite Wi-Fi, per non dover reimportare i brani uno a uno e non perdere
le playlist che ho costruito.

**Criteri di accettazione:**
- [ ] Dal drawer sono raggiungibili le voci "Invia libreria" e "Ricevi libreria"
- [ ] Il mittente mostra il proprio indirizzo sulla LAN ed è individuabile automaticamente dal destinatario
- [ ] Al termine il destinatario ha nel DB tutti i brani inviati con `title`, `artist`, `album`,
      `duration`, `year`, `trackNumber`, `originalExtension`, `originalFileSize` e `importedAt` identici all'origine
- [ ] Le copertine sono presenti e visibili in lista e nel dettaglio brano
- [ ] Le playlist inviate esistono con lo stesso nome, gli stessi brani e lo stesso ordinamento
- [ ] I brani trasferiti sono riproducibili dopo lo sblocco biometrico
- [ ] La schermata Statistiche riporta conteggi e dimensioni coerenti con quanto ricevuto

### US-002 · Certezza di parlare col device giusto
**Priorità:** Must Have

Come utente, voglio confermare un codice mostrato su entrambi i telefoni prima che parta il
trasferimento, per essere sicuro che i miei brani vadano al mio device e non a un altro
dispositivo sulla stessa rete.

**Criteri di accettazione:**
- [ ] Il trasferimento non parte finché il codice non è confermato su entrambi i lati
- [ ] Codice non corrispondente → pairing fallito con messaggio esplicito, **zero byte** trasferiti
- [ ] Dopo 3 tentativi falliti il mittente invalida la sessione e rigenera le chiavi effimere
- [ ] La sessione di pairing scade dopo 5 minuti e alla chiusura della schermata
- [ ] Una richiesta senza sessione valida riceve `401` e non espone alcun metadato; token errato → `404`

### US-003 · Riservatezza dei contenuti sulla rete
**Priorità:** Must Have

Come utente attento alla privacy, voglio che i brani viaggino cifrati anche sulla LAN, per essere
certo che chi è connesso alla stessa rete non possa intercettarli, coerentemente col fatto che
l'app li tiene cifrati a riposo.

**Criteri di accettazione:**
- [ ] Manifest, audio e copertine sono cifrati con la chiave di sessione prima di raggiungere il socket
- [ ] La chiave di sessione è effimera: nuova a ogni pairing, mai scritta su disco
- [ ] La DEK non compare in nessun payload di rete né nel manifest
- [ ] Una cattura del traffico sulla LAN non permette di ricostruire i file audio
- [ ] I temporanei in chiaro sono cancellati al termine di ogni brano e i residui rimossi
      da `OrphanCleanupUseCase` all'avvio successivo

### US-004 · Trasferimento parziale
**Priorità:** Should Have

Come utente con una libreria grande, voglio poter inviare solo alcune playlist o alcuni brani,
per copiare sul secondo telefono solo ciò che mi serve senza saturarne lo spazio.

**Criteri di accettazione:**
- [ ] Il mittente può scegliere "tutta la libreria", "playlist selezionate" o "brani selezionati"
- [ ] Selezionando una playlist vengono inclusi automaticamente tutti i suoi brani
- [ ] Conteggio brani e dimensione totale stimata sono mostrati prima di confermare l'invio

### US-005 · Progresso e ripresa
**Priorità:** Should Have

Come utente, voglio vedere a che punto è il trasferimento e cosa è andato storto, e voglio poter
rilanciare dopo un'interruzione senza ricominciare da capo.

**Criteri di accettazione:**
- [ ] Entrambi i lati mostrano brano corrente, `n/tot` e percentuale del file in corso
- [ ] Al termine è mostrato il riepilogo `aggiunti / già presenti / errori`
- [ ] Caduta di connessione → errore in UI e possibilità di rilanciare
- [ ] Al rilancio i brani già presenti risultano "già presenti" e non vengono riscaricati
- [ ] DEK non sbloccata su uno dei due lati → operazione bloccata con "Sessione scaduta",
      coerente con `playQueue()` / `playSingle()`
- [ ] Spazio disco insufficiente rispetto ai `totalBytes` del manifest → avviso prima di iniziare

---

## 5. Architettura tecnica

### Componenti coinvolti

```
  DEVICE A (mittente)                                  DEVICE B (destinatario)
  ┌────────────────────────────┐                       ┌────────────────────────────┐
  │ SendLibraryScreen/VM       │                       │ ReceiveLibraryScreen/VM    │
  │        ↓                   │                       │        ↓                   │
  │ TransferServer (Ktor CIO)  │                       │ ReceiveLibraryUseCase      │
  │  ├─ PeerDiscovery (NSD)  ──┼──── _playerxfer._tcp ─┼─→ PeerDiscovery (NSD)      │
  │  ├─ TransferCrypto ────────┼──── ECDH + HKDF ──────┼─→ TransferCrypto           │
  │  │     └─ SAS 6 cifre ─────┼── confronto a video ──┼─→ SAS 6 cifre              │
  │  ├─ TransferProtocol ──────┼──── manifest cifrato ─┼─→ TransferProtocol         │
  │  │                         │                       │        ↓                   │
  │  ├─ CryptoManager          │                       │ TransferClient             │
  │  │   decryptStream(DEK)    │                       │   decryptStream(sessione)  │
  │  │        ↓                │                       │        ↓                   │
  │  │   encryptStream(sess.) ─┼──── GET /track/{id} ──┼─→ cacheDir/transfer_temp   │
  │  │                         │                       │        ↓                   │
  │  └─ TrackDao / PlaylistDao │                       │ ImportTrackUseCase         │
  │      (letture one-shot)    │                       │   .importTransferred()     │
  │                            │                       │        ↓                   │
  │                            │                       │ encryptFile(DEK) + Room    │
  └────────────────────────────┘                       └────────────────────────────┘
                                                          mappa idOrigine → idLocale
                                                          → playlist_track_cross_ref
```

Nuovo package `domain/transfer/` (`TransferProtocol`, `TransferCrypto`, `TransferServer`,
`TransferClient`, `ReceiveLibraryUseCase`, `TransferSelection`, `PeerDiscovery`) e `ui/transfer/`
(due schermate + due ViewModel), in analogia a `domain/ftp/` e `domain/upload/`.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `tracks` | **Nessuna** | Lo schema è già sufficiente: il manifest trasporta i campi esistenti |
| `playlists`, `playlist_track_cross_ref` | **Nessuna** | Ricostruite con gli ID locali del destinatario |
| `ftp_config` | **Nessuna** | Fuori scope |
| `PlayerDatabase` | **Nessuna** | Versione invariata a **6**: nessuna migrazione da scrivere né da revertire |
| `TransferManifest` (nuovo tipo) | Nuovo | Modello di trasporto, non persistito: `protocolVersion`, `device`, `trackCount`, `totalBytes`, `tracks[]`, `playlists[]` |
| Mappa `idOrigine → idLocale` | Nuovo | Solo in memoria, per la durata del trasferimento |

### Nuove API o endpoint

Tutte le rotte sono montate sotto il capability token, come in `UploadServer`. Auth = header
`X-Session` legato alla sessione ECDH confermata via SAS.

| Metodo | Path | Descrizione | Auth richiesta |
|---|---|---|---|
| POST | `/{token}/pair` | Scambio delle chiavi pubbliche effimere; risponde con `sessionId` e `protocolVersion` | No (è il pairing) |
| GET | `/{token}/manifest` | Manifest JSON cifrato con la chiave di sessione | Sì |
| GET | `/{token}/track/{trackId}` | Audio decifrato con la DEK e ricifrato con la chiave di sessione, in streaming | Sì |
| GET | `/{token}/art/{trackId}` | Copertina PNG cifrata con la chiave di sessione | Sì |
| POST | `/{token}/done` | Comunica l'esito, chiude la sessione e invalida il codice | Sì |

Regole di errore: token errato → `404` (nessuna informazione sull'esistenza del servizio);
sessione assente o non confermata → `401`; SAS non ancora approvata dall'utente sul mittente →
`409` finché non arriva il tap di conferma; `protocolVersion` incompatibile → `426` con messaggio
esplicito.

### Breaking changes

**Nessuno.** Tutte le modifiche al codice esistente sono additive: nuove funzioni in
`CryptoManager`, un nuovo overload in `ImportTrackUseCase`, nuove query nei DAO e nei repository.
Le firme esistenti restano invariate e lo schema del DB non cambia.

L'unico contratto soggetto a incompatibilità è il **protocollo fra due installazioni di versioni
diverse**: gestito con `protocolVersion` nel manifest e risposta `426`, senza tentativi di
interpretazione parziale.

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da | Responsabile |
|---|---|---|---|---|---|
| T-01 | Spike: `ktor-client-cio` su Android 8 (API 26), `KeyAgreement("ECDH")` + `Mac("HmacSHA256")` su API 26, `NsdManager` fra due device reali | Infra | 0,5 | — | Alberto Goldoni |
| T-02 | Dipendenze: client HTTP e serializzazione in `libs.versions.toml` + `app/build.gradle.kts` | Infra | 0,25 | T-01, Dec. 1-2 | Alberto Goldoni |
| T-03 | `res/xml/network_security_config.xml` + attributo nel manifest, con motivazione a commento | Infra | 0,25 | Dec. 3 | Alberto Goldoni |
| T-04 | `CryptoManager`: `encryptStream` / `decryptStream` con `SecretKey` arbitraria, buffer 64 KB | Core | 0,5 | — | Alberto Goldoni |
| T-05 | `TransferProtocol`: modello manifest, `protocolVersion`, costanti di rotta, serializzazione | Core | 0,5 | T-02 | Alberto Goldoni |
| T-06 | `TransferCrypto`: ECDH secp256r1, HKDF-SHA256 scritto a mano, SAS a 6 cifre, cifratura di sessione con nonce progressivi | Core | 1,0 | T-01 | Alberto Goldoni |
| T-07 | DAO/repository: `getTracksByIds`, `getAllPlaylistsOnce`, `getTrackIdsForPlaylist`, `insertCrossRefs` | Core | 0,25 | — | Alberto Goldoni |
| T-08 | `ImportTrackUseCase.importTransferred(...)`: metadati dal manifest, copertina via `AlbumArtSaver`, ritorna l'ID locale | Core | 0,5 | T-05, T-07 | Alberto Goldoni |
| T-09 | `TransferServer`: bind su range dedicato `8091..8100`, token, rotte, macchina a stati del pairing, rate limit 3 tentativi, scadenza 5 min, `StateFlow<TransferServerState>` | Core | 1,5 | T-04, T-05, T-06 | Alberto Goldoni |
| T-10 | `TransferSelection`: libreria / playlist / brani, conteggio e byte totali | Core | 0,25 | T-07 | Alberto Goldoni |
| T-11 | `TransferClient`: chiamate alle rotte, decifratura di sessione in streaming su file temporaneo | Core | 0,75 | T-02, T-06 | Alberto Goldoni |
| T-12 | `ReceiveLibraryUseCase`: orchestrazione, `Mutex` su dedup+import, mappa `idOrigine → idLocale`, ricostruzione playlist, `Flow<TransferProgress>` cancellation-aware | Core | 1,25 | T-08, T-11, Dec. 4-6 | Alberto Goldoni |
| T-13 | `PeerDiscovery` con `NsdManager` (registrazione + discovery + risoluzione) e fallback a IP digitato | Core | 0,75 | T-01 | Alberto Goldoni |
| T-14 | `SendLibraryScreen` + `SendLibraryViewModel`: selezione contenuti, SAS, progresso, riepilogo | FE | 1,0 | T-09, T-10 | Alberto Goldoni |
| T-15 | `ReceiveLibraryScreen` + `ReceiveLibraryViewModel`: lista peer, IP manuale, conferma SAS, progresso, riepilogo | FE | 1,0 | T-12, T-13 | Alberto Goldoni |
| T-16 | Navigazione (`Screen.SendLibrary`, `Screen.ReceiveLibrary`), voci nel drawer, `FLAG_KEEP_SCREEN_ON`, stringhe | FE | 0,5 | T-14, T-15 | Alberto Goldoni |
| T-17 | Robustezza: cleanup `transfer_temp` in `OrphanCleanupUseCase`, controllo spazio disco, guardia DEK, ripresa dopo interruzione | Core | 0,5 | T-12 | Alberto Goldoni |
| T-18 | Unit test: `TransferCryptoTest`, `TransferManifestTest`, `PlaylistRemapTest` | Test | 1,0 | T-06, T-05, T-12 | Alberto Goldoni |
| T-19 | Sessione di test manuale a due device + stesura della checklist | Test | 0,5 | T-16, T-17 | Alberto Goldoni |
| T-20 | Documentazione: `CLAUDE.md` (nuovo package e flusso, **correzione `player_db` v5 → v6**), `test-manuale.md`, nota in Info app | Doc | 0,5 | T-19 | Alberto Goldoni |

**Stima totale:** 13,25 giorni/uomo
**Breakdown:** Core 7,75gg · FE 2,5gg · Infra 1,0gg · Test 1,5gg · Doc 0,5gg

> **Nota sulla stima.** Fase 1 stimava 10,5 gg top-down, Fase 2 l'ha portata a 11 gg aggiungendo lo
> spike. Il breakdown per task qui sopra dà **13,25 gg**: la differenza (+2,25) è tutta in voci che
> nelle fasi precedenti erano implicite nelle milestone — infrastruttura (T-02, T-03: 0,5 gg),
> robustezza e casi d'errore (T-17: 0,5 gg) — e in una revisione al rialzo di server e orchestratore
> (T-09, T-12), i due componenti con più stati da gestire. **La stima bottom-up è quella da approvare.**

**Percorso critico:** T-01 → T-06 → T-09 → T-14/T-15 → T-16 → T-19 → T-20. T-04, T-07 e T-13 sono
parallelizzabili e non bloccano nessun altro ramo fino a T-09/T-12.

---

## 7. Piano di test

**Strategia generale:** unit test JVM puri su tutto ciò che non tocca API Android (crittografia,
manifest, rimappatura degli ID), test manuale strutturato a due device per il resto. Il progetto ha
oggi **un solo file di test** (`PlaybackQueueTest.kt`, JUnit 4 + `kotlinx-coroutines-test`) e
**nessun test strumentato**: la scelta progettuale è quindi tenere la logica critica fuori dalle
dipendenze Android, non introdurre Robolectric o MockK per questa feature.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | I due lati derivano la **stessa** chiave di sessione dallo scambio ECDH | Alta |
| TC-02 | Unit | La SAS a 6 cifre è deterministica e **cambia** se cambia una delle chiavi pubbliche (anti-MITM) | Alta |
| TC-03 | Unit | Roundtrip `encryptStream`/`decryptStream`; chiave o nonce errati → `AEADBadTagException` | Alta |
| TC-04 | Unit | Nessun nonce viene riusato all'interno della stessa sessione | Alta |
| TC-05 | Unit | Manifest: roundtrip completo, campi null, titoli con caratteri speciali e virgolette | Alta |
| TC-06 | Unit | `protocolVersion` incompatibile → errore tipizzato, nessun parsing parziale | Alta |
| TC-07 | Unit | Rimappatura playlist: brano nuovo, brano dedup, brano fallito, `lastPlayedTrackId` assente | Alta |
| TC-08 | Unit | Selezione contenuti: conteggio e byte totali per libreria / playlist / sottoinsieme | Media |
| TC-09 | Manuale | Migrazione completa libreria vuota → piena, con verifica campo per campo dei metadati | Alta |
| TC-10 | Manuale | Libreria parzialmente sovrapposta: i duplicati risultano "già presenti", le playlist restano complete | Alta |
| TC-11 | Manuale | Codice di verifica sbagliato ×3 → sessione invalidata, zero byte trasferiti | Alta |
| TC-12 | Manuale | Wi-Fi staccato a metà trasferimento, poi rilancio: nessun brano riscaricato | Alta |
| TC-13 | Manuale | DEK non sbloccata su un lato → "Sessione scaduta" | Alta |
| TC-14 | Manuale | Spazio disco insufficiente → avviso prima dell'inizio | Media |
| TC-15 | Manuale | Cattura del traffico sulla LAN: nessun frammento audio ricostruibile | Alta |
| TC-16 | Manuale | Nessun residuo in `cacheDir/transfer_temp` a fine trasferimento e dopo riavvio dell'app | Alta |
| TC-17 | Manuale | Brani `.flac` oltre a `.mp3`; brano con copertina e brano senza | Media |
| TC-18 | Manuale | Schermo bloccato e app in background durante l'invio | Media |
| TC-19 | Manuale | "Ricevi via Wi-Fi" già aperta e trasferimento avviato in contemporanea: nessun conflitto di porta | Media |

I test manuali possono essere eseguiti anche su un solo telefono usando le due installazioni
conviventi `it.agoldoni.player` e `it.agoldoni.player.debug`, con il limite che non verificano il
percorso di rete reale (loopback) — TC-09, TC-12 e TC-15 richiedono **due device fisici**.

### Definition of Done

- [ ] `./gradlew testDebugUnitTest` verde (nuovi test TC-01…TC-08 inclusi)
- [ ] `./gradlew clean assembleDebug` e `assembleRelease` senza warning nuovi
- [ ] Checklist manuale TC-09…TC-19 eseguita e allegata a `test-manuale.md`, esiti annotati
- [ ] Nessun `Log.e` inatteso in `adb logcat` durante un trasferimento completo
- [ ] Throughput misurato e annotato (soglia: ≥ 3 MB/s)
- [ ] `CLAUDE.md` aggiornato, inclusa la correzione `player_db` v5 → v6
- [ ] Auto-revisione del diff con `/code-review` prima del merge

> ⚠️ DA COMPLETARE: il progetto non ha CI né ambiente di staging, e non misura la coverage.
> Le voci "test in CI", "log puliti in staging" e "coverage ≥ X%" del template non sono
> applicabili e sono state sostituite dai controlli locali qui sopra.

---

## 8. Rischi e mitigazioni

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| Un codice a 6 cifre usato come **unica** sorgente di entropia sarebbe forzabile offline da chi cattura il traffico | Media | Alto | La chiave nasce dall'ECDH, non dal codice: la SAS serve solo a verificare il transcript. Copertura TC-01/TC-02 |
| Il traffico HTTP in chiaro è bloccato da Android per il lato client (`targetSdk 34`, nessuna config nel manifest) | **Alta** | Alto | `network_security_config.xml` dedicata (T-03); il payload resta cifrato a livello applicativo, quindi l'involucro HTTP non espone nulla |
| `ktor-client-cio` 2.3.12 mai usato in questo progetto: incognite su Android 8 | Media | Medio | Spike T-01 prima di committare la scelta; ripiego su `HttpURLConnection` già identificato |
| Doppia cifratura per file (decifra DEK → cifra sessione → decifra sessione → cifra DEK) rallenta il trasferimento | Media | Medio | Streaming a blocchi da 64 KB, mai il file intero in memoria; misura del throughput nella DoD |
| `ImportTrackUseCase` riestrae i metadati e rigenera l'UUID: i campi dell'origine andrebbero persi | **Alta** (certa senza T-08) | Alto | Nuovo entry point `importTransferred` che salta `MetadataExtractor` (T-08) |
| Collisione di ID fra brani/playlist di origine e locali | Media | Alto | Rimappatura `idOrigine → idLocale`, con i brani deduplicati mappati sull'ID già esistente (T-12, TC-07) |
| `NsdManager` inaffidabile su alcune reti (AP isolation, mDNS filtrato) | Media | Medio | Fallback sempre disponibile con IP digitato a mano (T-13); IP del mittente mostrato in chiaro sulla schermata di invio |
| Trasferimento lungo interrotto da schermo spento o app in background | Media | Medio | `FLAG_KEEP_SCREEN_ON` come in `WifiUploadScreen`; ripresa garantita dalla dedup (TC-12, TC-18) |
| Spazio disco insufficiente sul destinatario a metà trasferimento | Bassa | Medio | Controllo preventivo contro `totalBytes` del manifest (T-17, TC-14) |
| Conflitto di porta con `UploadServer` (range `8080..8090`) | Bassa | Basso | Range dedicato `8091..8100` (T-09, TC-19) |
| Dedup su `(title, artist, album)` salta versioni diverse dello stesso brano | Media | Basso | Comportamento noto e coerente con FTP/upload; `originalFileSize` resta nel manifest come dato diagnostico |
| Assenza di test strumentati: il flusso è per natura end-to-end | Alta | Medio | Logica critica isolata in classi JVM-testabili; checklist manuale formalizzata (T-19) |
| Innalzamento futuro del `targetSdk` con restrizioni sull'accesso alla rete locale | Bassa | Medio | ⚠️ DA COMPLETARE: verificare i requisiti di permesso per la rete locale al prossimo salto di `targetSdk` (oggi 34, nessun permesso aggiuntivo richiesto) |

---

## 9. Rollout e feature flag

**Strategia di rilascio:**
- [x] **Deploy diretto** — app Android senza backend, distribuita come APK firmato a un utente singolo
- [ ] Graduale con feature flag
- [ ] Canary release

**Feature flag:** nessuno. Il progetto non ha un'infrastruttura di flag e la feature è inerte finché
l'utente non apre esplicitamente una delle due nuove schermate: il rischio di regressione sui flussi
esistenti è confinato alle modifiche additive elencate in §5.

**Versione:** `versionCode 4 → 5`, `versionName 1.2.0 → 1.5.0`.

**Piano di rollback:**
1. Reinstallare l'APK della versione precedente (`versionCode 4`).
2. **Nessuna migrazione DB da revertire**: lo schema resta alla versione 6, quindi il downgrade
   dell'app non lascia il database in uno stato incompatibile.
3. I brani già importati dal destinatario **restano validi**: sono file cifrati con la sua DEK e
   record `tracks` ordinari, indistinguibili da quelli importati via FTP o upload Wi-Fi.
4. Eventuali residui in `cacheDir/transfer_temp` sopravvivono al rollback ma sono innocui: verranno
   ignorati dalla versione precedente e cancellabili svuotando la cache dell'app.

---

## 10. Checklist di approvazione

> ⚠️ DA COMPLETARE: progetto a singolo sviluppatore — i ruoli sotto coincidono nella stessa persona.
> Se la revisione deve essere fatta da qualcun altro, indicare i nomi prima di procedere.

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Alberto Goldoni (Tech Lead) | ⏳ In attesa | — |
| Revisione prodotto | Alberto Goldoni (Product Owner) | ⏳ In attesa | — |
| Stima approvata (13,25 gg) | Alberto Goldoni | ⏳ In attesa | — |
| Rischi accettati | Alberto Goldoni | ⏳ In attesa | — |
| Data di inizio confermata | — | ⏳ In attesa | — |

---

## Domande aperte

Nessuna. Le dieci decisioni elencate in §3 sono state chiuse il 2026-08-20 adottando le
raccomandazioni tecniche; l'implementazione procede su quella base.

Restano due verifiche **rimandate al campo**, che non bloccano lo sviluppo:

1. **Comportamento reale di `ktor-client-cio` su Android 8 (API 26)** e di `NsdManager` fra due
   device sulla rete di casa: verificabili solo con hardware alla mano (TC-09, TC-12, TC-15).
2. **Restrizioni sull'accesso alla rete locale** al prossimo innalzamento del `targetSdk`
   (oggi 34, nessun permesso aggiuntivo richiesto).

---

## 11. Stato dell'implementazione (2026-08-20)

Implementate tutte le task da T-02 a T-18 e T-20; la build debug compila e i test JVM passano
(37 test totali: 10 preesistenti su `PlaybackQueue`, 27 nuovi).

**Codice nuovo**
- `domain/transfer/`: `TransferProtocol`, `TransferCrypto`, `TransferSelection`, `TransferServer`,
  `TransferClient`, `ReceiveLibraryUseCase`, `PlaylistRemapper`, `PeerDiscovery`
- `domain/AesGcmStreams.kt`: primitive AES-GCM pure estratte da `CryptoManager` — non previsto dal
  piano, ma senza di esse TC-03 e TC-04 non sarebbero unit-testabili (`CryptoManager` richiede
  `Context` e AndroidKeystore). `CryptoManager` resta l'unica porta d'accesso per l'app.
- `ui/transfer/`: `SendLibraryScreen`/`ViewModel`, `ReceiveLibraryScreen`/`ViewModel`
- `res/xml/network_security_config.xml`
- Test: `TransferCryptoTest` (8), `TransferManifestTest` (6), `PlaylistRemapperTest` (5),
  `AesGcmStreamsTest` (8)

**Codice modificato**
- `CryptoManager` (primitive su stream), `ImportTrackUseCase` (`importTransferred`),
  `TrackDao`/`PlaylistDao` e relativi repository (letture one-shot), `OrphanCleanupUseCase`
  (`transfer_temp/`), `PlayerNavGraph`, `PlayerApp`, `AndroidManifest.xml`,
  `build.gradle.kts` + `libs.versions.toml` (ktor-client-cio, kotlinx-serialization,
  `versionCode 5` / `versionName 1.5.0`), `CLAUDE.md`

**T-19 — provato su due emulatori (2026-08-20)**
Sei giri di trasferimento fra due istanze di `Emulator_x86_64` (API 33): libreria completa, dedup,
rifiuto del codice ×3, cattura del traffico, invio parziale per playlist, libreria parzialmente
sovrapposta. Esiti e prove in [`test-manuale.md`](./test-manuale.md): metadati identici campo per
campo nei due DB, playlist ricostruita e poi fusa senza duplicati, brano trasferito riprodotto sul
ricevente, 693 KB di traffico catturato senza un solo frammento in chiaro, `transfer_temp` vuota.

**Due bug corretti durante i test**

1. `TransferServer.stop()` conservava gli stati finali, così riaprendo "Invia libreria" si ritrovava
   il riepilogo precedente invece della scelta dei contenuti (trovato su emulatore).
2. **"Sessione scaduta" sul telefono che riceve** (trovato dall'utente su device reale): su
   un'installazione nuova la libreria è vuota, quindi la DEK non esiste e
   `MainActivity.BiometricGate` considera l'app già sbloccata senza mai mostrare il prompt
   (`isUnlocked = sessionDek != null || !isDekInitialized`). Risultato: `sessionDek` nullo e
   "Ricevi libreria" che falliva con un messaggio per giunta fuorviante — riavviare non serviva a
   nulla. È il caso **tipico** della feature: il device che riceve è per definizione appena
   installato. Corretto in `SendLibraryViewModel` / `ReceiveLibraryViewModel`, che ora chiedono
   l'autenticazione biometrica quando la chiave manca (creandola al primo uso), seguendo lo stesso
   schema già usato dall'import da file in `TrackListViewModel`. Il percorso è verificabile solo su
   device con impronta registrata: sull'emulatore la build debug sblocca la DEK da sola.

**Prestazioni (misurate sugli emulatori, 2026-08-20)**

Segnalazione dell'utente: trasferimento lento su device reali. Misure su 36,6 MB (5 brani da 7,3 MB):

| | Prima | Dopo |
|---|---|---|
| Mittente che serve un brano (misurato dall'host) | 1,9 MB/s | **63-96 MB/s** |
| Ricevente, rete + decifratura per brano | 4.100 ms (1,8 MB/s) | **~400 ms (18 MB/s)** |
| Ricifratura + inserimento a DB | ~20 ms (irrilevante) | invariato |
| Trasferimento completo di 36,6 MB | 36 s | **15 s**, di cui ~8 s di overhead dello script di test |

Il percorso di rete regge 132 MB/s (misurato con TCP grezzo sullo stesso tragitto), quindi il limite
era tutto nel codice. Causa: **il provider AEAD di Android accumula l'input fino a `doFinal`**, per
cui il costo cresce col numero di chiamate a `update`. `CipherInputStream` legge a blocchi di 512
byte: su un file da 7 MB significa ~14.000 update, ognuna a ricopiare il buffer già accumulato.

Correzioni: `AesGcmStreams.transcodeTo` (decifra con la DEK e ricifra con la chiave di sessione
leggendo a blocchi da 64 KB, senza `CipherInputStream`); `fill()` accorpa le letture prima di ogni
`update` anche quando arrivano dalla rete a pacchetti piccoli; scrittura sul canale Ktor con
`respondBytesWriter` invece del ponte bloccante `respondOutputStream`; `Content-Length` dichiarata;
buffer a 64 KB anche in `encryptFile`/`decryptToTempFile` (giova a tutti i percorsi di import).
Aggiunto un log `perf` per brano nel ricevente (`adb logcat | grep perf`) per misurare sul campo.

Verificato dopo le modifiche: metadati identici fra i due DB, `encryptedFileSize` = originale + 28
byte, brano trasferito riprodotto sul ricevente, unit test verdi.

**Cosa resta**
- **T-01 (spike) su hardware reale**: `ktor-client-cio` su Android 8 (gli emulatori sono API 33) e
  `NsdManager` fra due telefoni sulla stessa rete — fra emulatori mDNS non attraversa il NAT, quindi
  la scoperta automatica è l'unica parte non ancora esercitata.
- **TC-12, TC-13, TC-14, TC-18, TC-19** della checklist: caduta di rete reale, DEK bloccata (sull'emulatore
  debug si sblocca da sola), spazio disco insufficiente, schermo bloccato, conflitto di porta.
- **Throughput ≥ 3 MB/s**: non misurabile in modo significativo su emulatore con libreria di 1 MB.

---

*Documento generato con la skill `claude-code-feature`.*
