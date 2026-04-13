# load-track-from-ftp — Implementation Plan

**Stato:** Bozza — in attesa di approvazione
**Autore:** Alberto Goldoni
**Data:** 2026-04-11
**Versione:** 1.0
**Repo / branch base:** `player` / `main` (commit `a75c004`)

---

## 1. Executive Summary

Aggiunge all'app `player` la possibilità di importare automaticamente nella libreria locale tutti gli MP3 presenti su un server FTP personale. L'utente configura l'endpoint una sola volta e poi avvia la sincronizzazione con un pulsante: l'app percorre ricorsivamente le cartelle remote, scarica ogni file, ne legge i metadati ID3 e — se non già presente in libreria (dedup per `titolo|artista|album`) — lo cifra con la pipeline esistente e lo inserisce nel DB. La feature non introduce streaming né sincronizzazione bidirezionale: è un meccanismo di **import bulk on-demand**. Stima: ~5.5 giorni/uomo.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** L'unico modo attuale per popolare la libreria è il file picker Android, che richiede di trasferire manualmente i file sul dispositivo. Per archivi musicali grandi (centinaia/migliaia di brani) ospitati su un NAS o server casalingo è un workflow inutilizzabile.
- **Metriche di successo:**
  - [ ] Sync di una libreria di test da 100 MP3 nidificati su 5 livelli completata in un'unica passata, senza intervento utente oltre il pulsante "Sincronizza"
  - [ ] Tasso di errore atteso < 1% su file MP3 validi (escluse rotture di rete)
  - [ ] Re-run della sync sulla stessa libreria importa **0** tracce (dedup funzionante)
  - [ ] Nessun crash dell'app su sync di lunga durata (> 10 minuti)
- **Legame con obiettivi di prodotto:** Rimuove l'attrito principale all'adozione dell'app per chi ha già una collezione musicale digitalizzata. È prerequisito per qualunque scenario di uso "serio" oltre alla demo.

---

## 3. Scope

### Incluso
- Schermata "Configura FTP" accessibile dal drawer di navigazione
- Persistenza di host, porta, username, password (cifrata) e path radice in nuova tabella Room `ftp_config`
- Pulsante "Test connessione" nel form di configurazione
- Schermata "Sincronizza da FTP" accessibile dal drawer
- Walk ricorsivo del filesystem FTP a partire dal `rootPath` configurato, raccogliendo tutti i file con estensione `.mp3` (case-insensitive)
- Per ogni file: download in `cacheDir/ftp_temp/`, estrazione metadati, dedup per `(title, artist, album)`, cifratura con la pipeline esistente, inserimento nel DB
- Feedback UI in tempo reale: file corrente, contatore N/M, totali aggiunti/saltati/errori
- Pulsante "Annulla" che interrompe la sync in modo cooperativo
- Cleanup dei file temporanei in caso di crash/cancellazione (estensione di `OrphanCleanupUseCase`)
- Aggiunta permesso `INTERNET` al manifest

### Escluso (out of scope)
- **FTPS / SFTP**: scelta esplicita per la v1 (decisione utente). Solo FTP plain. *Motivo:* riduce dipendenze e codice, il server è personale e acceso on-demand
- **Sincronizzazione in background / foreground service**: la sync funziona solo con app in primo piano (decisione utente). *Motivo:* semplifica enormemente la gestione del lifecycle e dei permessi; per sync lunghe l'utente tiene l'app aperta
- **Sincronizzazione bidirezionale (upload verso FTP)**
- **Cancellazione locale di tracce non più presenti su FTP** (no mirror)
- **Sync schedulata o automatica**
- **Più endpoint FTP** (single-config)
- **Streaming diretto da FTP senza download**
- **Risoluzione conflitti diversa dallo skip per dedup**

### Decisioni aperte
Tutte le decisioni bloccanti della Fase 2 sono state risolte. Nessuna decisione aperta residua.

| # | Decisione | Risolta | Esito |
|---|-----------|---------|-------|
| 1 | Protocollo FTP/FTPS/SFTP | ✅ | FTP plain |
| 2 | Storage credenziali | ✅ | Room + `CryptoManager` (encryptBytes/decryptBytes) |
| 3 | Background vs foreground | ✅ | App aperta — nessun service |
| 4 | Strategia dedup | ✅ | Tupla metadati `(title, artist, album)` da ID3 |

---

## 4. User Stories e criteri di accettazione

### US-001 · Configurazione endpoint FTP
**Priorità:** Must Have

Come utente voglio configurare host, credenziali e path radice del mio server FTP per non doverli reinserire ad ogni sincronizzazione.

**Criteri di accettazione:**
- [ ] Voce "Configura FTP" presente nel drawer di navigazione (`PlayerApp.kt`)
- [ ] Form Compose con campi: host (text), porta (numeric, default `21`), username (text), password (password field, mascherata), root path (text, default `/`)
- [ ] Validazione: host non vuoto, porta intera 1–65535, username non vuoto
- [ ] Pulsante "Test connessione": apre `FTPClient`, fa login, chiude. Esito visualizzato in snackbar
- [ ] Pulsante "Salva": persiste in `ftp_config` (singola riga, `id=1`). Password cifrata con `CryptoManager.encryptBytes(sessionDek, ...)`
- [ ] Alla riapertura della schermata i campi sono ripopolati con i valori salvati (password mascherata `•••••••`)
- [ ] La password in chiaro non compare mai nei log

### US-002 · Sincronizzazione da FTP
**Priorità:** Must Have

Come utente voglio premere un bottone "Sincronizza" e vedere l'app scaricare automaticamente tutti gli MP3 nuovi per popolare la libreria senza intervento manuale.

**Criteri di accettazione:**
- [ ] Voce "Sincronizza da FTP" nel drawer apre `FtpSyncScreen`
- [ ] Se non esiste config in `ftp_config`, la schermata mostra messaggio "Configura prima il server FTP" + link alla schermata di config
- [ ] Pulsante "Avvia sincronizzazione" connette al server, percorre ricorsivamente tutte le directory a partire da `rootPath`
- [ ] Vengono raccolti tutti i path remoti con estensione `.mp3` (case-insensitive)
- [ ] Per ogni file: download in `cacheDir/ftp_temp/<uuid>.mp3` → estrai metadati con `MetadataExtractor` → query `getTrackByMetadata(title, artist, album)` → se presente, elimina temp e incrementa "saltati"; altrimenti chiama `ImportTrackUseCase` (overload `(localFile, dek)`)
- [ ] Errori per singolo file (download fallito, metadati illeggibili, cifratura fallita) vengono loggati e contati ma non interrompono il loop
- [ ] A fine sync compare riepilogo: "X aggiunti, Y saltati, Z errori"
- [ ] La connessione FTP viene aperta una sola volta a inizio sync e chiusa in `finally`

### US-003 · Feedback progresso in tempo reale
**Priorità:** Must Have

Come utente voglio vedere in tempo reale il progresso (quanti file trovati, quanti scaricati, quanti saltati perché già presenti) per sapere cosa sta succedendo durante operazioni lunghe.

**Criteri di accettazione:**
- [ ] Stato visualizzato come `Flow<SyncProgress>` reattivo nello schermo
- [ ] Stati visibili: `Connecting`, `Scanning`, `Importing(current/total, currentFileName, added, skipped, errors)`, `Done(...)`, `Failed(message)`
- [ ] Indicatore di progresso lineare proporzionale a `current/total`
- [ ] Nome del file corrente sempre visibile
- [ ] Avviso fisso in cima alla schermata durante la sync: "Non chiudere l'app durante la sincronizzazione"

### US-004 · Sicurezza credenziali
**Priorità:** Must Have

Come utente voglio che le mie credenziali FTP siano cifrate sul dispositivo per non esporle in caso di accesso non autorizzato al telefono.

**Criteri di accettazione:**
- [ ] Il campo `encryptedPassword` in `ftp_config` è di tipo `BLOB` e contiene il payload `[IV (12 byte) | ciphertext+GCM tag]` prodotto da `CryptoManager.encryptBytes`
- [ ] La password decifrata vive solo nello scope di una singola sync e non viene mai persistita in chiaro
- [ ] La password non compare mai in `Log.d/i/w/e`
- [ ] Apertura della schermata di config dopo il biometric gate funziona perché `cryptoManager.sessionDek` è già disponibile

### US-005 · Cancellazione sync in corso
**Priorità:** Should Have

Come utente voglio poter interrompere una sincronizzazione in corso per non bloccare l'app se la connessione è lenta o se cambio idea.

**Criteri di accettazione:**
- [ ] Pulsante "Annulla" sempre visibile durante stato `Importing`
- [ ] Tap sul pulsante chiama `Job.cancel()` sul job della sync
- [ ] Il loop di download verifica `coroutineContext.ensureActive()` tra un file e l'altro
- [ ] Il file in download al momento della cancellazione viene scartato (temp eliminato)
- [ ] Le tracce già importate prima della cancellazione restano in libreria
- [ ] Stato finale dopo cancel: `Done(added, skipped, errors)` con i contatori parziali (no `Failed`)
- [ ] Back-press durante `Importing` mostra dialog di conferma "Annullare la sincronizzazione?"

---

## 5. Architettura tecnica

### Componenti coinvolti

```
            ┌────────────────────────┐
            │  FtpConfigScreen       │  (Compose)
            │  FtpConfigViewModel    │
            └──────────┬─────────────┘
                       │
                       ▼
            ┌────────────────────────┐
            │  FtpConfigRepository   │  ← cifra/decifra password con CryptoManager
            └──────────┬─────────────┘
                       │
                       ▼
            ┌────────────────────────┐
            │  FtpConfigDao (Room)   │  ── tabella ftp_config (1 riga)
            └────────────────────────┘


            ┌────────────────────────┐
            │  FtpSyncScreen         │  (Compose)
            │  FtpSyncViewModel      │  ── Job cancellabile
            └──────────┬─────────────┘
                       │ start() / cancel()
                       ▼
   ┌──────────────────────────────────────────┐
   │  SyncFromFtpUseCase                      │  emette Flow<SyncProgress>
   │  ┌────────────────────────────────────┐  │
   │  │ 1. read FtpConfig                  │  │
   │  │ 2. open FTPClient (Factory)        │  │
   │  │ 3. FtpScanner.walk(rootPath)       │  │
   │  │ 4. for each remote .mp3:           │  │
   │  │     a. FtpDownloader.download      │  │
   │  │     b. MetadataExtractor.extract   │  │
   │  │     c. getTrackByMetadata?         │  │
   │  │        ├─ exists → skip + delete   │  │
   │  │        └─ new → ImportTrackUseCase │  │
   │  │             .invoke(file, dek)     │  │
   │  │     d. emit progress               │  │
   │  │ 5. close FTPClient                 │  │
   │  └────────────────────────────────────┘  │
   └──────────────────────────────────────────┘
              │              │              │
              ▼              ▼              ▼
       FtpClientFactory  TrackDao    ImportTrackUseCase
                                          (riusato)
```

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `tracks` | Nessuna | Dedup è metadata-based: nessuna nuova colonna richiesta |
| `ftp_config` | **Nuova** | Singola riga (`CHECK id = 1`). Schema in sez. B.2 della tech-analysis |
| `Track` (entity) | Nessuna | Invariata |
| `FtpConfig` (entity) | **Nuova** | Mirror Room della tabella |

**Migrazione DB:** `MIGRATION_4_5` registrata in `PlayerDatabase` e `DatabaseModule`. Solo `CREATE TABLE ftp_config`. Nessun `ALTER TABLE tracks`.

### Nuove API o endpoint

N/A — feature interna all'app, nessun endpoint HTTP.

### Nuovi tipi pubblici / contratti Kotlin

| Tipo | Descrizione |
|---|---|
| `FtpConfig` (entity) | `id, host, port, username, encryptedPassword: ByteArray, rootPath, updatedAt` |
| `FtpRemoteFile` (data class) | `path: String, sizeBytes: Long` — output dello scanner |
| `SyncProgress` (sealed interface) | Stati: `Idle`, `Connecting`, `Scanning`, `Importing(...)`, `Done(...)`, `Failed(message)` |
| `FtpClientFactory.create(config: FtpConfig): FTPClient` | Factory configurata con timeout 30s, passive mode, encoding UTF-8 |
| `FtpScanner.walk(client, rootPath): List<FtpRemoteFile>` | Walk ricorsivo, filtra `.mp3`. Cancellation-aware via `ensureActive()` |
| `FtpDownloader.download(client, remotePath, destFile): Boolean` | Stream da FTP a `File`, no buffering in RAM |
| `SyncFromFtpUseCase.invoke(): Flow<SyncProgress>` | Orchestratore principale |
| `CryptoManager.encryptBytes(dek, ByteArray): ByteArray` | Helper per cifrare credenziali |
| `CryptoManager.decryptBytes(dek, ByteArray): ByteArray` | Helper inverso |
| `TrackDao.getTrackByMetadata(title, artist, album): Track?` | Query per dedup |
| `ImportTrackUseCase.invoke(localFile: File, dek: SecretKey): Boolean` | Nuovo overload — bypassa `audioFileCopier.copyToTemp` |

### Breaking changes

Nessuno. Tutte le modifiche sono additive:
- `Track` invariata
- Nuovi metodi pubblici (non rimuovono né cambiano firme esistenti)
- DB migration additiva (`CREATE TABLE`)
- Manifest: aggiunta permesso normale, no rimozioni

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | Aggiungere `commons-net 3.10.0` in `libs.versions.toml` e `app/build.gradle.kts`. Aggiungere permessi `INTERNET` e `ACCESS_NETWORK_STATE` in manifest | Infra | 0.25 | — |
| T-02 | Estendere `CryptoManager` con `encryptBytes` / `decryptBytes` riusando il formato `[IV \| ciphertext+tag]` di `encryptFile` | Domain | 0.25 | — |
| T-03 | Creare entity `FtpConfig`, `FtpConfigDao`, `FtpConfigRepository`. Bump DB version 4→5, registrare `MIGRATION_4_5`, aggiornare `DatabaseModule` con il nuovo DAO provider | Data | 0.75 | T-02 |
| T-04 | Aggiungere `TrackDao.getTrackByMetadata(title, artist, album): Track?` (semplice `SELECT WHERE` con `LIMIT 1`) | Data | 0.25 | — |
| T-05 | Creare `FtpClientFactory` (config singola, timeout 30s, passive mode, UTF-8). Smoke test connessione hardcoded contro server di prova | Domain | 0.5 | T-01 |
| T-06 | Creare `FtpScanner.walk(client, rootPath)`. Walk ricorsivo, filtro `.mp3` case-insensitive, gestione directory vuote, `ensureActive()` tra directory | Domain | 0.75 | T-05 |
| T-07 | Creare `FtpDownloader.download(client, remotePath, destFile)` su `Dispatchers.IO`, stream-based (no RAM buffering), cancellation-aware | Domain | 0.5 | T-05 |
| T-08 | Refactor `ImportTrackUseCase`: estrarre la logica metadata→encrypt→DB in `private suspend fun importLocalFile(file, dek)` e creare overload pubblico `invoke(localFile, dek)`. Mantenere overload `Uri` esistente che chiama il nuovo metodo dopo la copia | Domain | 0.5 | T-04 |
| T-09 | Creare `SyncFromFtpUseCase`. Orchestratore: legge config, apre client, scanna, loop dedup+import, chiude client, emette `Flow<SyncProgress>`. Tutto in `Dispatchers.IO` | Domain | 1.0 | T-03,T-06,T-07,T-08 |
| T-10 | Estendere `OrphanCleanupUseCase` aggiungendo cleanup di `cacheDir/ftp_temp/` (analogamente a `cleanupImportCache`) | Domain | 0.25 | — |
| T-11 | Creare `FtpConfigViewModel` (`@HiltViewModel`) e `FtpConfigScreen` (form Compose con validazione, test connessione, salva) | UI | 1.0 | T-03,T-05 |
| T-12 | Creare `FtpSyncViewModel` (gestisce `Job`, espone `progress: StateFlow<SyncProgress>`, `start()`, `cancel()`) e `FtpSyncScreen` (stato reattivo, progress bar, pulsanti, dialog conferma annulla) | UI | 1.0 | T-09 |
| T-13 | Estendere `Screen` sealed class con `FtpConfig` e `FtpSync`. Aggiungere route in `PlayerNavGraph`. Aggiungere due `NavigationDrawerItem` in `PlayerApp.kt` | UI | 0.25 | T-11,T-12 |
| T-14 | Test manuale end-to-end contro server FTP locale con libreria nidificata reale. Coprire: sync iniziale, re-sync (zero import), credenziali errate, server irraggiungibile, cancellazione a metà, file MP3 corrotto | Test | 1.0 | T-13 |
| T-15 | Aggiornare `CLAUDE.md` con: nuova DB version 5, nuovi moduli `domain/ftp/` e `ui/ftp/`, nuova dipendenza `commons-net`, nuovo permesso `INTERNET` | Doc | 0.25 | T-14 |

**Stima totale:** **~8.5 gg/uomo** (revisione al rialzo rispetto ai 6.5 iniziali della Fase 1: il refactor di `ImportTrackUseCase` e l'orchestratore della sync sono più articolati di quanto stimato a freddo; in compenso il dedup metadata-based ha eliminato le complicazioni dello schema su `Track`).

**Breakdown:** Domain 3.75gg · UI 2.25gg · Data 1.0gg · Infra 0.25gg · Test 1.0gg · Doc 0.25gg

---

## 7. Piano di test

**Strategia generale:** Il progetto **non ha attualmente alcuna suite di test automatici** (`./build.sh` non ha target test, nessuna directory `test/` o `androidTest/`). La verifica della feature sarà **prevalentemente manuale end-to-end** contro un server FTP locale (es. container `fauria/vsftpd` o `vsftpd` nativo). Se in seguito si decide di avviare la prima suite di test del progetto, le prime tre voci della tabella sotto sono i candidati naturali.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Manuale E2E | Sync iniziale di una libreria con almeno 50 MP3 nidificati su 3+ livelli di directory. Verifica: tutti importati, metadati corretti, ordinati in libreria | Alta |
| TC-02 | Manuale E2E | Re-run della sync sulla stessa libreria → 0 nuovi import, N saltati = N tracce | Alta |
| TC-03 | Manuale E2E | Aggiungere 1 nuovo MP3 sul server, re-run → solo 1 import, resto saltato | Alta |
| TC-04 | Manuale E2E | Credenziali errate → stato `Failed` con messaggio leggibile, nessun crash | Alta |
| TC-05 | Manuale E2E | Server irraggiungibile (host inesistente) → `Failed` con messaggio, timeout entro 30s | Alta |
| TC-06 | Manuale E2E | Tap su "Annulla" durante sync → loop si ferma entro 1 file, contatori parziali mostrati, file in download eliminato | Alta |
| TC-07 | Manuale E2E | File MP3 corrotto sul server → contato come errore, sync prosegue con il file successivo | Media |
| TC-08 | Manuale E2E | Sync con app in primo piano per > 10 minuti → nessun crash, nessuna degradazione visibile | Media |
| TC-09 | Manuale E2E | Backgrounding dell'app durante sync → comportamento atteso documentato (rischio R7: la sync può fallire al ritorno) | Media |
| TC-10 | Manuale (sicurezza) | Ispezione del DB SQLite con `adb shell` post-config: il campo `encryptedPassword` è binario non leggibile, nessun valore in chiaro | Alta |
| TC-11 | Manuale | Cleanup orfani: kill dell'app durante sync, riavvio, verifica che `cacheDir/ftp_temp/` venga svuotato all'avvio | Media |
| TC-12 | Unit (futuro) | Round-trip `CryptoManager.encryptBytes`/`decryptBytes` con DEK in-memory | Bassa |
| TC-13 | Unit (futuro) | `FtpScanner` con fake `FTPClient` — walk, filtro estensioni, directory vuote | Bassa |
| TC-14 | Unit (futuro) | `SyncFromFtpUseCase` con tutte le dipendenze fake — dedup, contatori, cancellazione | Bassa |

### Definition of Done

- [ ] Tutti i TC manuali ad alta priorità (TC-01..TC-06, TC-10) eseguiti con esito positivo
- [ ] Build debug e release passano (`./build.sh debug` e `./build.sh release`)
- [ ] Nessun warning `Lint` nuovo introdotto rispetto al baseline `main`
- [ ] `CLAUDE.md` aggiornato (T-15)
- [ ] Nessun riferimento a credenziali in chiaro nel codice o nei log
- [ ] Cleanup orfani verificato funzionante anche per `ftp_temp/`

---

## 8. Rischi e mitigazioni

| # | Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|---|
| R1 | FTP plain → credenziali sniffabili sulla rete | Alta | Medio | Out of scope per la v1, esplicitamente accettato. Server personale acceso on-demand. Documentare in `CLAUDE.md` come limitazione nota |
| R2 | `commons-net` incompatibile con qualche server FTP esotico (encoding nomi file, modalità attiva/passiva) | Media | Medio | Forzare passive mode + UTF-8 nel `FtpClientFactory`. Loggare `client.replyString` su ogni operazione fallita per facilitare debug |
| R3 | Sync di lunga durata uccisa dal SO se l'utente esce dall'app | Alta | Alto | UX: warning fisso "Non chiudere l'app". Decisione esplicita di non usare foreground service nella v1. Documentare nel test plan (TC-09) |
| R4 | Picchi di disco con file molto grandi (FLAC convertito o MP3 ad alto bitrate) | Bassa | Medio | Stream-based download, mai full-load in RAM. Cifratura immediata + delete temp dopo ogni file. Picco massimo = ~2× dimensione del file più grande |
| R5 | DEK non disponibile (`sessionDek == null`) all'apertura della schermata FTP | Bassa | Alto | Verifica all'`init` del ViewModel: se `sessionDek == null`, mostra messaggio "Riavvia l'app per autenticarti" e blocca le azioni. Non dovrebbe accadere perché il biometric gate è il punto di ingresso, ma è difensivo |
| R6 | Connessione FTP che va in timeout durante una sync lunga (server-side idle) | Media | Medio | Inviare `NOOP` ogni N file (es. ogni 10) come keep-alive. In alternativa, riconnessione automatica al primo errore di trasferimento |
| R7 | Dedup metadata-based produce falsi positivi (due brani con `(title, artist, album)` identici ma file diversi — es. live vs studio) | Bassa | Basso | Limitazione accettata della scelta. L'utente può importare manualmente con il file picker se necessario. Documentare nel `CLAUDE.md` |
| R8 | Dedup metadata-based produce falsi negativi (stesso file, ma metadati ID3 modificati) | Bassa | Basso | Stessa nota di R7. Per la v1 si considera "nuovo brano" |
| R9 | Spreco di banda: i duplicati vengono comunque scaricati prima di essere riconosciuti come tali | Alta | Basso | Limitazione strutturale del dedup metadata-based (i tag ID3 sono nei file, non nei nomi). Accettato perché il caso d'uso target è una rete locale veloce |
| R10 | Refactor di `ImportTrackUseCase` (T-08) rompe il flusso di import esistente da file picker | Bassa | Alto | Mantenere identica la signature pubblica `invoke(uri, dek)`; il refactor estrae solo una helper privata. Test manuale del flusso picker post-refactor obbligatorio prima di proseguire |

---

## 9. Rollout e feature flag

**Strategia di rilascio:** Deploy diretto. Trattandosi di un'app Android personale single-user senza backend, non c'è infrastruttura per feature flag né canary release.

**Versioning:**
- Bump `versionCode` 1 → 2 e `versionName` `1.0` → `1.1` in `app/build.gradle.kts`
- DB migration `4 → 5` testata manualmente prima del primo run su un device con DB esistente

**Piano di rollback:**
1. Disinstallazione e reinstallazione della versione precedente (l'utente perde la libreria locale)
2. **In alternativa**, se il bug è isolato: hotfix release immediato — il codice nuovo è additivo e isolato in `domain/ftp/` + `ui/ftp/`, può essere disabilitato rimuovendo le voci nel drawer (`PlayerApp.kt`) senza rimuovere il codice
3. La migrazione `4 → 5` non è reversibile automaticamente; in caso di problemi gravi serve una migrazione `5 → 4` manuale che faccia `DROP TABLE ftp_config`

---

## 10. Checklist di approvazione

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Alberto Goldoni | ⏳ In attesa | — |
| Stima accettata | Alberto Goldoni | ⏳ In attesa | — |
| Rischi accettati (in particolare R1, R3, R7) | Alberto Goldoni | ⏳ In attesa | — |
| Data di inizio confermata | Alberto Goldoni | ⏳ In attesa | — |

> Nota: questo è un progetto solo, quindi tutte le approvazioni convergono su un singolo responsabile. Eliminare questa sezione o adattarla nel caso il progetto diventi multi-persona.

---

## Domande aperte

Tutte le decisioni bloccanti sono state risolte. Restano due **domande non bloccanti** che possono essere chiarite durante o dopo l'implementazione senza impatti sul piano:

1. **`CLAUDE.md` è disallineato** (dichiara DB v2, attuale v4). Aggiornarlo come parte di T-15 o aprire un task separato di manutenzione documentale?
2. **Strategia per i brani live vs studio omonimi** (R7): per la v1 vengono trattati come duplicati. Vale la pena ipotizzare per v2 una strategia più raffinata (es. dedup per `title|artist|album|duration` con tolleranza ±2s)? Da decidere solo se l'utente incontra il problema in pratica.

---

*Documento generato con la skill `claude-code-feature-skill`. Riferimenti alle Fasi precedenti: [plan.md](plan.md), [tech-analysis.md](tech-analysis.md).*
