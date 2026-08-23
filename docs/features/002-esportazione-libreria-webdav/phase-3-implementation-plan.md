# Esportazione della libreria su PC via WebDAV — Implementation Plan

**Stato:** Approvato — implementato e collaudato su emulatore il 2026-08-23
**Autore:** Alberto Goldoni
**Data:** 2026-08-23
**Versione:** 1.1

Documenti collegati: [Fase 1 — Requisiti](./phase-1-requirements.md) · [Fase 2 — Analisi tecnica](./phase-2-analysis.md)

---

## 1. Executive Summary

Oggi non esiste modo di portarsi i brani della libreria su un PC: i file sono cifrati con una
chiave che non lascia il telefono, quindi copiarli con `adb` o su una scheda SD produce blob
illeggibili. Questa feature fa sì che l'app, mentre è aperta e sbloccata, si presenti alla rete
locale come una **cartella di sola lettura** che il PC può leggere con strumenti standard
(`rclone`, oppure `rsync` su una cartella montata). Il PC confronta con quello che ha già e
**scarica solo i brani mancanti**, senza ritrasferire ogni volta l'intera libreria. Stima:
**5,5 giorni/uomo**, rilasciabile come singola versione dell'app.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** la libreria è un vicolo cieco in uscita. Le tre funzioni di rete
  esistenti vanno nella direzione opposta (`UploadServer` riceve da un browser, `SyncFromFtpUseCase`
  scarica da FTP) o parlano solo con un'altra istanza dell'app (`TransferServer`, protocollo ECDH
  proprietario). Non c'è backup indipendente dal telefono né modo di ascoltare i brani altrove.
  `adb pull` non è una via d'uscita: `filesDir` è storage privato e i file sono cifrati AES-256-GCM
  con la DEK, wrappata da una KEK non esportabile in AndroidKeystore. **L'unico punto in cui i byte
  esistono in chiaro è il processo dell'app, con la DEK sbloccata.**

- **Metriche di successo:**
  - [ ] Un secondo `rclone copy` consecutivo su cartella invariata riporta **`Transferred: 0 B`**
        (è il requisito centrale: nessun ritrasferimento).
  - [ ] `rclone check --size-only` dopo un pull completo riporta **0 differenze**.
  - [ ] Throughput di un pull ≥ **20 MB/s** su Wi-Fi, cioè limitato dalla rete e non dalla
        decifratura (soglia scelta per intercettare la regressione AES-GCM da ~1,9 MB/s).
  - [ ] Nessuno script custom da installare sul PC: bastano `rclone config create` e `rclone copy`.

- **Legame con obiettivi di prodotto:** completa la matrice degli scambi della libreria. Con la
  feature 001 l'app sa migrare verso un'altra istanza; qui impara a uscire verso un filesystem
  qualsiasi, chiudendo il rischio "libreria prigioniera del device" anche quando il secondo device
  non è un telefono con l'app installata.

---

## 3. Scope

### Incluso

- **Server WebDAV read-only** dentro l'app, sulla LAN, avviabile e arrestabile dall'utente
  (`domain/webdav/WebDavServer.kt`, Ktor CIO, porte `8101..8110`).
- **Albero virtuale** `Artista/Album/NN - Titolo.ext` derivato da `tracks`, con nomi sanitizzati
  per filesystem Windows/macOS/Linux e disambiguazione **stabile** delle collisioni.
- **Metodi WebDAV**: `OPTIONS`, `PROPFIND` (Depth 0 e 1), `HEAD`, `GET`. Tutto il resto → `405`.
- **Decifratura in streaming** sul `GET`: i file restano cifrati a riposo, il chiaro esiste solo
  nel flusso di rete, senza temporanei su disco.
- **Dimensione esatta** in `getcontentlength`, coerente al byte con quanto servito.
- **Capability URL**: tutte le rotte sotto un token casuale, come upload Wi-Fi e trasferimento.
- **Cache dell'albero a 60 secondi**, invalidata all'avvio del server.
- **Schermata "Esporta su PC"** con URL, brani e spazio esposti, comandi `rclone` pronti da
  incollare, schermo tenuto acceso mentre il server è attivo.
- **Test JVM** su `LibraryTree` e `WebDavXml`.
- Aggiornamento di `CLAUDE.md`, `test-manuale.md` e della pagina Info.

### Escluso (out of scope)

- **Playlist come `.m3u8`** — deciso in Fase 1: riduce la superficie del primo giro, e la cartella
  resta comunque utilizzabile da qualsiasi player.
- **Copertine come file separati** (`cover.jpg`) — l'artwork viaggia già dentro i tag ID3 del file,
  che viene servito integro.
- **Scrittura da PC** (`PUT`, `MKCOL`, `DELETE`) — per quel verso esiste già "Ricevi via Wi-Fi";
  un server read-only ha una superficie di attacco molto più piccola.
- **Richieste `Range`** — su AES-GCM in streaming un seek costringerebbe a decifrare e scartare
  tutto il prefisso; rclone non le usa su file di pochi MB.
- **HTTPS / HTTP Basic auth** — il capability token è la protezione di questo giro, in linea con
  `UploadServer`. Basic auth resta l'estensione naturale successiva (rclone ha i campi nativi).
- **Foreground service** — il server vive finché la schermata è aperta, coerentemente con FTP,
  upload Wi-Fi e trasferimento.
- **Discovery mDNS** — rclone non ne fa uso; l'URL si incolla una volta in `rclone config`.
- **Propagazione delle cancellazioni** — il PC è una destinazione additiva, non uno specchio.

### Decisioni chiuse

Tutte le decisioni di prodotto sono state prese. Nessuna resta aperta.

| # | Decisione | Scelta | Chiusa il |
|---|---|---|---|
| 1 | Direzione e ruoli | App = sorgente, cartella PC = destinazione; il PC tira | 2026-08-23 |
| 2 | Trasporto | WebDAV su HTTP nella LAN (rclone, o `rclone mount` + rsync) | 2026-08-23 |
| 3 | Criterio di "già presente" | Percorso + dimensione (`--size-only`) | 2026-08-23 |
| 4 | Struttura di destinazione | `Artista/Album/NN - Titolo.ext` | 2026-08-23 |
| 5 | Playlist | Fuori scope | 2026-08-23 |
| 6 | Cache dell'albero | Validità a tempo, **60 secondi** | 2026-08-23 |
| 7 | Artisti con un solo album | **Regolarità**: gerarchia sempre a tre livelli | 2026-08-23 |
| 8 | Brani senza artista | Tutti sotto `Sconosciuto/` | 2026-08-23 |
| 9 | `getlastmodified` | Da `Track.importedAt` | 2026-08-23 |
| 10 | `Track.originalExtension` | Dato affidabile, nessun fallback da implementare | 2026-08-23 |

---

## 4. User Stories e criteri di accettazione

### US-001 · Scaricare solo ciò che manca
**Priorità:** Must Have

Come possessore della libreria voglio scaricare sul PC solo i brani che non ho ancora nella
cartella di destinazione, per non ritrasferire ogni volta l'intera libreria.

**Criteri di accettazione:**
- [ ] Un primo `rclone copy player: <dest> --size-only` scarica tutti i brani della libreria.
- [ ] Un secondo `rclone copy` immediatamente dopo, a cartella invariata, riporta `Transferred: 0 B`.
- [ ] Cancellando un singolo file dalla destinazione, il pull successivo riscarica **solo quello**.
- [ ] Troncando un file a metà, il pull successivo lo riscarica.
- [ ] `rclone check player: <dest> --size-only` riporta 0 differenze dopo un pull completo.

### US-002 · Struttura della destinazione
**Priorità:** Must Have

Come ascoltatore voglio che la cartella sul PC sia organizzata in `Artista/Album/NN - Titolo.ext`,
per poterla aprire con qualsiasi player senza riorganizzare nulla a mano.

**Criteri di accettazione:**
- [ ] `rclone lsl player:` mostra percorsi nella forma attesa, con dimensioni non nulle.
- [ ] `trackNumber = "3/12"` produce il prefisso `03`; se il tag manca, il prefisso non compare.
- [ ] Artista o album vuoti producono `Sconosciuto` e `Senza album`.
- [ ] Caratteri vietati sostituiti; nessun segmento termina con punto o spazio.
- [ ] Due brani che collasserebbero sullo stesso nome ricevono un suffisso stabile, che **non
      cambia** fra un pull e l'altro né aggiungendo altri brani alla libreria.
- [ ] Un file scaricato si apre in VLC, suona e mostra i tag corretti.

### US-003 · Strumenti standard
**Priorità:** Must Have

Come utente voglio usare strumenti già installati (`rclone`, `rsync`), senza mantenere software
specifico dell'app sul PC.

**Criteri di accettazione:**
- [ ] `rclone config create player webdav url=… vendor=other` basta a configurare il remoto.
- [ ] `rclone lsl`, `rclone copy`, `rclone check` funzionano senza flag di compatibilità.
- [ ] `rclone mount player: /mnt/player --read-only` monta l'albero e
      `rsync -av --ignore-existing /mnt/player/ <dest>/` completa senza errori.

### US-004 · Superficie di attacco
**Priorità:** Must Have

Come utente attento alla sicurezza voglio che la libreria sia raggiungibile solo mentre l'app è
aperta e sbloccata, e che chi sta sulla stessa Wi-Fi senza il token non possa leggerla né elencarla.

**Criteri di accettazione:**
- [ ] Token errato → `404`, indistinguibile da risorsa inesistente.
- [ ] `PUT`, `DELETE`, `MKCOL`, `MOVE`, `COPY`, `LOCK` → `405` su qualsiasi path.
- [ ] DEK non sbloccata → `503` sulle `GET`, mai byte cifrati.
- [ ] Chiudendo la schermata il server si arresta e la porta torna libera.
- [ ] La schermata dichiara esplicitamente che il traffico è in chiaro sulla rete locale.

### US-005 · Interruzione e ripresa
**Priorità:** Should Have

Come utente con una libreria grande voglio poter interrompere e riprendere il trasferimento,
ritrovando al riavvio solo i file ancora mancanti.

**Criteri di accettazione:**
- [ ] Chiudendo la schermata durante un pull, rclone termina con un errore di rete pulito e i file
      già completi restano validi.
- [ ] Riavviando il server e ripetendo il `copy`, vengono trasferiti solo i file mancanti.
- [ ] Un file rimasto incompleto viene riscaricato per intero.

---

## 5. Architettura tecnica

### Componenti coinvolti

```
   PC                                    │  Telefono (app aperta, DEK sbloccata)
                                         │
   rclone ──PROPFIND /{token}/Artista/──►│  WebDavServer (Ktor CIO, 8101..8110)
          ◄────── 207 Multi-Status ──────│      │
                                         │      ├── LibraryTree ──► TrackDao.getAllTracksOnce()
   confronto con la cartella locale      │      │   (cache 60 s)     Artista/Album/NN - Titolo.ext
   (percorso + dimensione)               │      │
                                         │      └── WebDavXml (href percent-encoded + XML-escaped)
   rclone ──GET /{token}/…/brano.mp3────►│
          ◄──── audio in chiaro ─────────│  CryptoManager.decryptTo(DEK, filesDir/tracks/{id})
                                         │      └── AesGcmStreams.fill() a blocchi da 64 KB
   scrive solo i file mancanti           │
```

Il diff **non** è fatto dall'app: lo fa rclone confrontando il manifest implicito del `PROPFIND`
con la cartella di destinazione reale. L'app resta senza stato — nessun indice degli export che
possa disallinearsi e mentire.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| — | **Nessuna** | La feature **legge** `tracks` e non aggiunge stato persistente. `player_db` resta a **versione 6**, nessuna nuova migrazione, nessun DAO nuovo |

### Nuove API o endpoint

Sottoinsieme read-only di WebDAV (RFC 4918). Tutte le rotte sotto `/{token}` — capability URL,
stesso schema di `UploadServer` e `TransferServer`.

| Metodo | Path | Descrizione | Auth richiesta |
|---|---|---|---|
| `OPTIONS` | `/{token}/**` | `200` + `DAV: 1` + `Allow: OPTIONS, HEAD, GET, PROPFIND` | Token nel path |
| `PROPFIND` | `/{token}/**` | `207 Multi-Status` XML. `Depth: 0` → la risorsa; `Depth: 1` → risorsa + figli diretti; `Depth: infinity` → `403` con `<DAV:propfind-finite-depth/>` | Token nel path |
| `HEAD` | `/{token}/…/file.ext` | Header di `GET`, corpo vuoto | Token nel path |
| `GET` | `/{token}/…/file.ext` | Audio in chiaro, `Content-Length` esatto, `Accept-Ranges: none` | Token nel path + DEK sbloccata |
| altri | qualunque | `405 Method Not Allowed` | — |

Proprietà restituite, sempre le stesse a prescindere dal corpo della richiesta (che si ignora,
legittimo per RFC 4918 §9.1): `resourcetype`, `displayname`, `getcontentlength`,
`getlastmodified` (RFC 1123 GMT da `Track.importedAt`), `getcontenttype` (`audio/mpeg` / `audio/flac`).

**Vincoli sugli `<D:href>`** — sono la fonte classica dei bug WebDAV:
1. devono includere il prefisso `/{token}`, altrimenti rclone perde il token navigando;
2. le collection devono terminare con `/`, altrimenti vengono trattate come file;
3. ogni **segmento** va percent-encoded, e **poi** il testo va XML-escaped: due passaggi distinti,
   in quest'ordine.

**Contratto di mappatura `Track` → percorso** (è un contratto a tutti gli effetti: la stabilità dei
nomi è ciò da cui dipende US-001):

```
/{token}/<artista>/<album>/<NN - titolo>.<ext>
```

- `artista` ← `Track.artist`, vuoto → `Sconosciuto` (coerente con `TrackDao.UNKNOWN_ARTIST`)
- `album` ← `Track.album`, vuoto → `Senza album`
- `NN` ← intero iniziale di `Track.trackNumber` (`"3/12"` → `03`), zero-padded; assente o non
  numerico → nessun prefisso
- `titolo` ← `Track.title`, vuoto → `Senza titolo`
- `ext` ← `Track.originalExtension`
- sanitizzazione per segmento: `/ \ : * ? " < > |` e controlli → `_`; punti e spazi finali rimossi;
  taglio a 120 caratteri
- **collisioni**: suffisso ` [xxxxxxxx]` con i primi 8 caratteri di `Track.id`. Stabile nel tempo e
  indipendente dal resto della libreria — un contatore progressivo romperebbe US-001

**Dimensione dichiarata** — `encryptedFile.length() - 28`, da un'unica funzione condivisa fra
`PROPFIND` e `GET`. Il framing `[IV 12][cifrato][tag 16]` è fissato da `AesGcmStreams`
(`IV_SIZE = 12`, `TAG_BITS = 128`) e già enunciato nel commento di `TransferServer.kt:383-386`.
**Non** si usa `Track.originalFileSize`: vale `0` per i brani anteriori alla migrazione `1→2`, e un
`getcontentlength` a zero renderebbe i file non scaricabili in silenzio.

### Breaking changes

Nessuno. L'unica modifica a codice esistente è **additiva**: la primitiva
`AesGcmStreams.decryptTo(key, file, sink)` con la sua facciata su `CryptoManager`, gemella di
`transcodeTo`. È servita perché `decryptingStream` — che il piano di Fase 1 dava per buono — si
appoggia a `CipherInputStream` e ricade nella trappola AES-GCM documentata in `CLAUDE.md`.

Per il resto: nessuna firma cambia, nessuna migrazione DB, nessuna nuova dipendenza Gradle
(`ktor-server-core` e `ktor-server-cio` 2.3.12 erano già presenti), nessun permesso nuovo
(`INTERNET` e `ACCESS_NETWORK_STATE` già dichiarati).

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da | Responsabile |
|---|---|---|---|---|---|
| T-01 | `LibraryTree`: sanitizzazione dei segmenti, composizione del nome file, disambiguazione da UUID | Dominio | 0,5 | — | Alberto Goldoni |
| T-02 | `LibraryTree`: costruzione dell'albero a tre livelli + `resolve(segments)` | Dominio | 0,5 | T-01 | Alberto Goldoni |
| T-03 | `LibraryTreeTest` (JUnit 4, JVM) | Test | 0,25 | T-02 | Alberto Goldoni |
| T-04 | `WebDavXml`: percent-encoding degli href, XML-escaping, multistatus, date RFC 1123 | Dominio | 0,5 | — | Alberto Goldoni |
| T-05 | `WebDavXmlTest` (JUnit 4, JVM) | Test | 0,25 | T-04 | Alberto Goldoni |
| T-06 | `WebDavServer`: scheletro (singleton Hilt, `StateFlow`, `start`/`stop`, IP/porta/token) + **smoke test `curl -X PROPFIND`** | Dominio | 0,25 | — | Alberto Goldoni |
| T-07 | Rotte `OPTIONS` e `PROPFIND` (Depth 0/1, `403` su infinity) + cache dell'albero a 60 s invalidata su `start()` | Dominio | 0,5 | T-02, T-04, T-06 | Alberto Goldoni |
| T-08 | Rotta `GET`: `decryptTo` + `respondBytesWriter` con `contentLength`, `503` senza DEK, `404` se il file manca | Dominio | 0,5 | T-06 | Alberto Goldoni |
| T-09 | Rotta `HEAD` esplicita + catch-all `405` per i metodi di scrittura | Dominio | 0,25 | T-08 | Alberto Goldoni |
| T-10 | `WebDavExportViewModel` | UI | 0,25 | T-06 | Alberto Goldoni |
| T-11 | `WebDavExportScreen`: URL, statistiche, comandi rclone, avvisi, `FLAG_KEEP_SCREEN_ON` | UI | 0,5 | T-10 | Alberto Goldoni |
| T-12 | Rotta `Screen.WebDavExport` nel nav graph + voce di menu nel drawer | UI | 0,25 | T-11 | Alberto Goldoni |
| T-13 | Collaudo end-to-end con rclone su device reale + misura di throughput | Test | 0,5 | T-09, T-12 | Alberto Goldoni |
| T-14 | Documentazione: `CLAUDE.md`, `test-manuale.md`, bump di versione | Doc | 0,5 | T-13 | Alberto Goldoni |
| T-15 | Primitiva `decryptTo` in `AesGcmStreams`/`CryptoManager` + 2 unit test *(emersa in analisi, vedi §5)* | Dominio | 0,25 | — | Alberto Goldoni |

**Stima totale:** 5,75 giorni/uomo
**Breakdown:** Dominio 3,25 gg · UI 1,0 gg · Test 1,0 gg · Doc 0,5 gg

**Ordine vincolante:** T-01 → T-02 → T-07 e T-04 → T-07; T-06 → T-07/T-08 → T-09. La catena UI
(T-10 → T-12) può procedere in parallelo a T-07/T-08/T-09 una volta fissato lo `StateFlow` in T-06.

---

## 7. Piano di test

**Strategia generale:** unit test JVM su **tutta** la logica che può sbagliare in silenzio — nomi,
encoding, dimensioni — e collaudo manuale con rclone per il resto. Il progetto non ha test
strumentati (vincolo dichiarato in `CLAUDE.md`), quindi `LibraryTree` e `WebDavXml` sono scritti
senza API Android proprio per restare verificabili automaticamente, sullo stampo di
`PlaylistRemapper` e `AesGcmStreams`.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | Albero a tre livelli anche per un artista con un solo album (decisione 7) | Alta |
| TC-02 | Unit | `artist`/`album`/`title` vuoti o di soli spazi → `Sconosciuto`/`Senza album`/`Senza titolo` | Alta |
| TC-03 | Unit | `trackNumber = "3/12"` → `03`; `null` → nessun prefisso; `"abc"` → nessun prefisso | Alta |
| TC-04 | Unit | Caratteri vietati e di controllo sostituiti; punti/spazi finali rimossi; segmento tagliato a 120 char | Alta |
| TC-05 | Unit | **Stabilità dei nomi collidenti**: due brani omonimi ottengono il suffisso `[id8]`; ricostruendo l'albero i nomi non cambiano; aggiungendo un terzo brano scorrelato restano invariati | **Alta — protegge US-001** |
| TC-06 | Unit | `resolve` su collection, su file, su path inesistente, su segmenti vuoti | Media |
| TC-07 | Unit | Href con spazi e accenti percent-encoded (`Fabrizio De André` → `Fabrizio%20De%20Andr%C3%A9`) | Alta |
| TC-08 | Unit | Album con `&`: percent-encoding **e poi** `&amp;` nell'XML | Alta |
| TC-09 | Unit | Collection con `/` finale e `<D:collection/>`; file con `getcontentlength` e `getcontenttype` corretti | Alta |
| TC-10 | Unit | Prefisso `/{token}` presente in ogni href, anche nei figli | Alta |
| TC-11 | Unit | `getlastmodified` in RFC 1123 GMT da un epoch millis fisso | Media |
| TC-12 | Manuale | `curl -X PROPFIND -H 'Depth: 1'` → `207` (chiude in campo il residuo di R1) | **Alta — sblocca T-07** |
| TC-13 | Manuale | `rclone lsl` mostra l'albero con dimensioni non nulle e coerenti | Alta |
| TC-14 | Manuale | **Doppio `rclone copy`**: il secondo riporta `Transferred: 0 B` | **Alta — metrica di successo** |
| TC-15 | Manuale | Cancellare un file dalla destinazione → riscaricato solo quello; troncarne uno → riscaricato | Alta |
| TC-16 | Manuale | `md5sum` del file scaricato uguale all'originale importato | Alta |
| TC-17 | Manuale | Brano con accenti, `&` e `/` nel titolo: nome sanitizzato, download OK, riproducibile | Alta |
| TC-18 | Manuale | `curl -X PUT` → `405`; token errato → `404`; DEK non sbloccata → `503` | Alta |
| TC-19 | Manuale | Chiusura della schermata durante un pull → errore di rete pulito, file completi validi, porta liberata | Media |
| TC-20 | Manuale | `rclone mount --read-only` + `rsync -av --ignore-existing` completa senza errori | Media |
| TC-21 | Manuale | Throughput su ~500 MB ≥ 20 MB/s (intercetta la regressione AES-GCM) | Alta |

### Definition of Done

- [ ] `./gradlew testDebugUnitTest` verde, con `LibraryTreeTest` e `WebDavXmlTest` inclusi.
- [ ] TC-12, TC-14, TC-16, TC-18 e TC-21 eseguiti su device reale ed esito annotato in
      `test-manuale.md`, con la versione di rclone usata.
- [ ] Nessun `Log.e` inatteso in `logcat` durante un pull completo.
- [ ] `./gradlew clean assembleDebug` compila senza warning nuovi.
- [ ] `CLAUDE.md` aggiornato: `domain/webdav/` nella mappa dei layer, il flusso in *Key flows*, la
      nuova rotta di navigazione, la nota sull'HTTP in chiaro, il rimando a questa cartella.
- [ ] Revisione del codice completata.

---

## 8. Rischi e mitigazioni

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| **R2** — `getcontentlength` diverso dai byte serviti: rclone aborta con *"corrupted on transfer: sizes differ"* | Media | Alto | Una sola funzione calcola `length() - 28` e serve sia `PROPFIND` sia `GET`. Mai `Track.originalFileSize`, che vale `0` per i brani pre-migrazione `1→2`. Coperto da TC-13 e TC-16 |
| **R3** — encoding/escaping degli href sbagliato o applicato nell'ordine sbagliato | Alta | Alto | Isolato in `WebDavXml`, puro e testato su casi sporchi reali (accenti italiani, `&`). TC-07, TC-08, TC-10 |
| **R4** — nomi collidenti instabili fra un pull e l'altro | Bassa | Alto | Suffisso derivato da `Track.id` (UUID), stabile e indipendente dal resto della libreria. TC-05 è scritto apposta per questo |
| **R6** — trappola AES-GCM: letture a blocchi piccoli → comportamento quadratico (~1,9 MB/s) | **Si è materializzato in analisi** | Alto | `decryptingStream` *ci ricade*: è `CipherInputStream`. Introdotta `decryptTo` a blocchi da 64 KB (T-15). TC-21 misurato: **97–123 MB/s** |
| **R9** — `HEAD` non risponde: in Ktor una rotta `get` non serve automaticamente `HEAD`, e rclone lo usa | Media | Medio | Rotta `head` esplicita (T-09), scelta al posto del plugin `AutoHeadResponse` per coerenza con gli altri due server, che non installano plugin |
| **R10** — cache a 60 s disallineata: un brano cancellato resta nell'albero fino a un minuto | Media | Basso | La `GET` verifica `File(track.uri)` e risponde `404` se manca, senza fidarsi della cache. La cache è invalidata a ogni `start()` |
| **R5** — audio in chiaro su HTTP nella LAN | Alta (per costruzione) | Medio | Deviazione consapevole: stessa postura di `UploadServer`, che riceve già file in chiaro su HTTP. Capability token, bind sulla sola LAN, finestra limitata alla schermata aperta, avviso esplicito in UI. DEK e KEK non lasciano il device |
| **R7** — sessione lunga a schermo acceso su librerie da GB | Media | Medio | `FLAG_KEEP_SCREEN_ON` come la sync FTP, avviso in UI; la ripresa del pull (US-005) rende tollerabile l'interruzione |
| **R11** — `originalExtension` vale `'mp3'` di default per i record anteriori alla migrazione `5→6` | Bassa | Basso | **Accettato**: l'utente ha dichiarato il dato affidabile (decisione 10), nessun fallback implementato. Residuo: un eventuale FLAC pre-v6 esposto con estensione `.mp3`, contenuto integro |
| **R8** — differenze di comportamento fra versioni o `vendor` di rclone | Bassa | Basso | `vendor=other` fissato; versione collaudata annotata in `test-manuale.md` |

**R1 (routing di `PROPFIND` su Ktor CIO) è stato chiuso in Fase 2** ispezionando il bytecode di
`ktor-http-cio` 2.3.12: `parseHttpMethod` delega a `parseHttpMethodFull`, che esegue
`new HttpMethod(nextToken(...))`; `HttpMethod` è una data class (uguaglianza per valore); e
`RoutingBuilderKt.route(Route, String, HttpMethod, …)` accetta un metodo arbitrario. Resta la sola
conferma in campo (TC-12), non più uno spike da mezza giornata.

---

## 9. Rollout e feature flag

**Strategia di rilascio:**
- [x] **Deploy diretto** — app Android senza backend, distribuita come APK firmato a un utente singolo
- [ ] Graduale con feature flag
- [ ] Canary release

**Feature flag:** nessuno. Il progetto non ha infrastruttura di flag e la feature è **inerte** finché
l'utente non apre la nuova schermata: nessun server parte all'avvio dell'app, nessun flusso
esistente viene toccato. Il rischio di regressione è confinato alle due modifiche additive alla UI
(voce di drawer e rotta di navigazione).

**Versione:** `versionCode 5 → 6`, `versionName 1.5.0 → 1.6.0`.

**Piano di rollback:**
1. Reinstallare l'APK della versione precedente (`versionCode 5`).
2. **Nessuna migrazione DB da revertire**: lo schema resta alla versione 6, quindi il downgrade non
   lascia il database in uno stato incompatibile.
3. **Nessun residuo su disco**: la feature non scrive nulla — né file temporanei né record — quindi
   non c'è cleanup da fare. La cartella già scaricata sul PC resta valida e indipendente.
4. La porta `8101..8110` torna libera al primo riavvio dell'app.

---

## 10. Checklist di approvazione

> ⚠️ DA COMPLETARE: progetto a singolo sviluppatore — i ruoli sotto coincidono nella stessa persona.
> Se la revisione deve essere fatta da qualcun altro, indicare i nomi prima di procedere.

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Alberto Goldoni (Tech Lead) | ⏳ In attesa | — |
| Revisione prodotto | Alberto Goldoni (Product Owner) | ⏳ In attesa | — |
| Stima approvata (5,75 gg) | Alberto Goldoni | ⏳ In attesa | — |
| Rischi accettati (in particolare R5) | Alberto Goldoni | ⏳ In attesa | — |
| Data di inizio confermata | — | ⏳ In attesa | — |

---

## Domande aperte

Nessuna decisione di prodotto resta aperta: le dieci elencate in §3 sono state chiuse il
2026-08-23.

Restano due verifiche **rimandate al campo**, che non bloccano l'inizio dello sviluppo:

1. **Comportamento di Ktor CIO 2.3.12 su Android 8 (API 26) con `PROPFIND`** — l'analisi statica del
   bytecode dice che funziona, ma la conferma end-to-end arriva solo con TC-12 sul device.
2. **Interoperabilità con la versione di rclone effettivamente installata sul PC** — da annotare in
   `test-manuale.md` al primo collaudo (TC-13, TC-14, TC-20).

Un punto che vale la pena riconsiderare **dopo** il primo collaudo, non prima: se il traffico in
chiaro sulla LAN (R5) risultasse fastidioso all'uso reale, l'aggiunta di HTTP Basic auth sopra le
stesse rotte è un intervento da poche ore, perché rclone ha i campi `user`/`pass` nativi e non
richiede alcun cambiamento al resto del protocollo.

---

*Documento generato con la skill `claude-code-feature`.*
