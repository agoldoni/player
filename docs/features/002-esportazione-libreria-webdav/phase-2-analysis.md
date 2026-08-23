# Fase 2 — Analisi tecnica: Esportazione della libreria su PC via WebDAV

> Feature slug: `esportazione-libreria-webdav`
> Data: 2026-08-23
> Input: `phase-1-requirements.md` + le cinque decisioni chiuse dall'utente (vedi § Decisioni)

## Decisioni chiuse dopo la Fase 1

| # | Domanda aperta | Risposta |
|---|---|---|
| D1 | Cache dell'albero virtuale | **Cache con validità a tempo: 60 secondi** |
| D2 | Livello "Artista" anche con un solo album | **Regolarità**: la gerarchia è sempre a tre livelli |
| D3 | Brani senza artista | Tutti sotto **`Sconosciuto/`**, accettato |
| D4 | `getlastmodified` da `Track.importedAt` | **Sì** |
| D5 | Affidabilità di `Track.originalExtension` | **Affidabile**, nessun fallback da implementare |

## Sintesi dell'esplorazione

L'app ha già **tre** server o client di rete, e la feature è il **quarto** — ma il primo in cui
l'app fa da **sorgente verso software di terze parti**, e il primo **read-only**:

| Componente | Direzione | Controparte | Protocollo |
|---|---|---|---|
| `UploadServer` | PC → app | browser | HTTP + pagina HTML, capability token |
| `SyncFromFtpUseCase` | FTP → app | server FTP | FTP (Commons Net) |
| `TransferServer` / `TransferClient` | app → app | l'altra istanza | HTTP + ECDH/HKDF, payload cifrati |
| **`WebDavServer`** *(nuovo)* | **app → PC** | **rclone / rsync** | **WebDAV su HTTP, capability token** |

Il pattern architetturale è consolidato e va riusato senza inventare nulla: singleton Hilt che
possiede un `ApplicationEngine` Ktor CIO, `StateFlow` di stato osservato dalla UI, capability
token come root del routing, ciclo di vita agganciato alla schermata.

### Il rischio bloccante R1 è stato risolto in questa fase

La Fase 1 dava per incognita il routing di `PROPFIND` su Ktor CIO e prevedeva uno spike da
0,5 gg (M0). L'ispezione del bytecode degli artefatti **2.3.12 già presenti nella cache Gradle**
lo chiude in senso positivo, su tre punti indipendenti:

1. **Il parser CIO accetta qualunque metodo.** In `io.ktor.http.cio.HttpParserKt`,
   `parseHttpMethod` cerca il token nell'albero dei metodi noti e, se non lo trova, delega a
   `parseHttpMethodFull`, il cui bytecode è `new HttpMethod(nextToken(...))`: qualsiasi token
   sulla request line diventa un `HttpMethod` valido.
2. **`io.ktor.http.HttpMethod` è una data class** (`component1`, `copy`, `equals`, `hashCode`):
   l'uguaglianza è per valore, quindi `HttpMethod.parse("PROPFIND")` combacia con l'istanza
   costruita dal parser.
3. **Il DSL di routing accetta un metodo arbitrario.**
   `RoutingBuilderKt.route(Route, String, HttpMethod, Function1)` e
   `method(Route, HttpMethod, Function1)` esistono e sono pubblici, e
   `HttpMethodRouteSelector` fa il match sull'`HttpMethod` ricevuto.

**Conseguenza sul piano**: M0 non è più uno spike bloccante da mezza giornata, ma uno smoke test
`curl -X PROPFIND` da fare come primo esercizio di M3. Resta da confermare sul campo un solo
dettaglio (vedi E-R9): come CIO tratta il **corpo** di una richiesta `PROPFIND` — che noi
comunque ignoriamo — e la risposta a `HEAD`.

## A. File coinvolti

### A.1 Nuovi file — dominio

| File | Tipo | Motivazione |
|---|---|---|
| `domain/webdav/LibraryTree.kt` | nuovo | Albero virtuale `Artista/Album/NN - Titolo.ext` a partire da `List<Track>`, sanitizzazione dei nomi, disambiguazione delle collisioni, risoluzione di un path in un nodo. **Nessuna API Android** → testabile su JVM |
| `domain/webdav/WebDavXml.kt` | nuovo | Costruzione della risposta `207 Multi-Status`: percent-encoding degli href, XML-escaping, formattazione RFC 1123 delle date. **Nessuna API Android** |
| `domain/webdav/WebDavServer.kt` | nuovo | Singleton Hilt con il server Ktor CIO, il ciclo di vita, la cache a 60 s dell'albero e lo streaming di decifratura |

La separazione fra i tre file non è cosmetica: `LibraryTree` e `WebDavXml` contengono **tutta**
la logica che può sbagliare in modo silenzioso (nomi, encoding, dimensioni) e sono l'unica parte
verificabile automaticamente, dato che il progetto non ha test strumentati.

### A.2 Nuovi file — UI

| File | Tipo | Motivazione |
|---|---|---|
| `ui/webdav/WebDavExportScreen.kt` | nuovo | Schermata "Esporta su PC": URL, statistiche, comandi rclone, avvisi |
| `ui/webdav/WebDavExportViewModel.kt` | nuovo | Espone `WebDavServer.state`, avvia/arresta il server, conta brani e byte esposti |

### A.3 Nuovi file — test

| File | Tipo | Motivazione |
|---|---|---|
| `app/src/test/java/it/agoldoni/player/domain/webdav/LibraryTreeTest.kt` | nuovo | Percorsi deterministici, sanitizzazione, collisioni stabili |
| `app/src/test/java/it/agoldoni/player/domain/webdav/WebDavXmlTest.kt` | nuovo | Encoding/escaping degli href, forma del multistatus |

### A.4 File da modificare

| File | Modifica |
|---|---|
| `ui/navigation/PlayerNavGraph.kt` | Aggiungere `object WebDavExport : Screen("webdav_export")` accanto a `WifiUpload`/`SendLibrary` (righe 41-43) e il blocco `composable(Screen.WebDavExport.route) { WebDavExportScreen(onBack = { navController.popBackStack() }) }` sul modello di quello di `WifiUpload` (riga 149) |
| `ui/PlayerApp.kt` | Un `NavigationDrawerItem` "Esporta su PC" nella lista del drawer, dopo "Invia libreria"; icona coerente con le altre (`Icons.Default.DriveFileMove` o `FolderShared`) |
| `CLAUDE.md` | Nuovo `domain/webdav/` nella mappa dei layer; il flusso in *Key flows*; l'aggiunta alle rotte di navigazione; nota nelle *Notes* sull'HTTP in chiaro e il rimando a `docs/features/002-…` |
| `domain/AesGcmStreams.kt` + `domain/CryptoManager.kt` | **Nuova primitiva `decryptTo(key, file, sink)`**, gemella di `transcodeTo`. Vedi B.5: `decryptingStream` non è utilizzabile per questo percorso |

### A.5 File esplicitamente **non** toccati

- `data/local/PlayerDatabase.kt` e le migrazioni — la feature **legge** `tracks` e non aggiunge
  stato persistente. Il DB resta a **versione 6**.
- `domain/OrphanCleanupUseCase.kt` — non si crea alcuna directory temporanea: la decifratura va
  direttamente sul canale di rete.
- `AndroidManifest.xml` — `INTERNET` e `ACCESS_NETWORK_STATE` sono già dichiarati.
- `res/xml/network_security_config.xml` — già consente il traffico in chiaro.
- `app/build.gradle.kts` e `gradle/libs.versions.toml` — `ktor-server-core` e `ktor-server-cio`
  2.3.12 sono già dipendenze (righe 102-103 del build file).
- `domain/MetadataExtractor.kt`, `domain/ImportTrackUseCase.kt` — la feature legge soltanto: non
  esiste alcun percorso di scrittura verso la libreria.

## B. Contratti e interfacce da modificare

### B.1 Nuovo contratto: sottoinsieme di WebDAV (RFC 4918), read-only

Tutte le rotte sotto `/{token}`, come `UploadServer` e `TransferServer`.

| Metodo | Path | Risposta |
|---|---|---|
| `OPTIONS` | `/{token}/**` | `200`, header `DAV: 1`, `Allow: OPTIONS, HEAD, GET, PROPFIND` |
| `PROPFIND` | `/{token}/**` | `207 Multi-Status`, `Content-Type: application/xml; charset=utf-8`. `Depth: 0` → solo la risorsa; `Depth: 1` → risorsa + figli diretti; `Depth: infinity` → `403` con `<DAV:propfind-finite-depth/>` |
| `HEAD` | `/{token}/…/file.ext` | Header di `GET`, corpo vuoto |
| `GET` | `/{token}/…/file.ext` | `200` + audio in chiaro, `Content-Length` esatto, `Accept-Ranges: none` |
| altri | qualunque | `405 Method Not Allowed` |
| token errato | qualunque | `404` (indistinguibile da risorsa inesistente) |
| DEK non sbloccata | `GET`/`HEAD` | `503` |

Proprietà restituite, sempre le stesse a prescindere dal corpo della richiesta (che si **ignora**,
legittimo per RFC 4918 §9.1 e sufficiente sia per `allprop` sia per `<prop>` mirate):
`resourcetype`, `displayname`, `getcontentlength`, `getlastmodified`, `getcontenttype`.

Vincoli sugli `<D:href>`, che sono la fonte classica dei bug WebDAV:
- devono includere il prefisso `/{token}`, altrimenti rclone perde il token alla navigazione;
- le collection devono terminare con `/`, altrimenti vengono trattate come file;
- ogni **segmento** va percent-encoded, e **poi** il testo risultante va XML-escaped: sono due
  passaggi distinti in quest'ordine.

### B.2 Nuovo contratto: mappatura `Track` → percorso

Non è un contratto di rete ma **è un contratto**, perché la stabilità dei nomi è ciò da cui
dipende il requisito principale: se un nome cambia fra due pull, rclone riscarica.

```
/{token}/<artista>/<album>/<NN - titolo>.<ext>
```

- `artista` ← `Track.artist`; se vuoto o solo spazi → `Sconosciuto`, coerente con
  `TrackDao.UNKNOWN_ARTIST` (che le query del DAO usano già per lo stesso scopo, vedi
  `getTracksByArtist` e `deleteTracksByArtist`).
- `album` ← `Track.album`; se vuoto → `Senza album`.
- `NN` ← intero iniziale di `Track.trackNumber`, zero-padded a 2 cifre. Il campo è `String?` e nei
  file reali vale spesso `"3/12"`: va preso solo ciò che precede la barra. Se assente o non
  numerico, il prefisso `NN - ` non compare.
- `titolo` ← `Track.title`; se vuoto → `Senza titolo`.
- `ext` ← `Track.originalExtension` (già lowercase senza punto, valori ammessi in
  `SupportedAudioExtensions` = `mp3`, `flac`).
- **Sanitizzazione** di ogni segmento: `/ \ : * ? " < > |` e i caratteri di controllo → `_`;
  rimozione di punti e spazi finali (vietati su Windows); taglio a 120 caratteri per segmento.
- **Collisioni**: se due brani producono lo stesso percorso, a ciascuno si appende ` [xxxxxxxx]`
  con i primi 8 caratteri di `Track.id` (UUID). La scelta è **stabile nel tempo e indipendente
  dal resto della libreria** — un contatore progressivo non lo sarebbe, ed è esattamente il modo
  in cui si romperebbe il requisito D1/US-1.

`getcontenttype`: `audio/mpeg` per `mp3`, `audio/flac` per `flac`.

### B.3 Modifiche a contratti interni: **una sola, additiva**

Nessuna firma esistente cambia, nessun DAO nuovo, nessuna migrazione, **nessun breaking change**.
L'unica aggiunta è la primitiva `decryptTo` descritta in B.5. Le altre letture esistono già:

- `TrackDao.getAllTracksOnce(): List<Track>` — one-shot, esattamente ciò che serve per costruire
  l'albero;
- `CryptoManager.sessionDek: SecretKey?` (proprietà pubblica, `CryptoManager.kt:55`);
- `CryptoManager.transcodeTo(...)` come modello per la nuova `decryptTo` (vedi B.5);
- `NetworkUtils.getLocalIpAddress()`, `firstFreePort(range)`, `generateToken()`.

### B.4 Punto di attenzione: la dimensione dichiarata

rclone interrompe il trasferimento con *"corrupted on transfer: sizes differ"* se
`getcontentlength` non coincide con i byte effettivamente serviti. Il formato su disco è fissato
da `AesGcmStreams` (`IV_SIZE = 12`, `TAG_BITS = 128` → tag da 16 byte) ed è già documentato nel
codice: il commento di `TransferServer.kt:383-386` lo enuncia esplicitamente — *"stesso schema
[IV 12][cifrato][tag 16]"*.

Quindi la dimensione in chiaro è **esattamente `encryptedFile.length() - 28`**, e va calcolata
così sia per `getcontentlength` sia per il `contentLength` della `GET`, dalla stessa funzione.

**Non** si usa `Track.originalFileSize`: la colonna è stata introdotta dalla migrazione `1→2`
con `DEFAULT 0` (`PlayerDatabase.kt:31`), quindi vale `0` per i brani importati prima. Un
`getcontentlength` a zero significherebbe file non scaricabili, in silenzio.

### B.5 Correzione al piano: serve una nuova primitiva di decifratura

Il piano di Fase 1 dava per scontato che la `GET` potesse usare
`CryptoManager.decryptingStream`. **È sbagliato, e va corretto prima di scrivere il server.**

`AesGcmStreams.decryptingStream` restituisce un `CipherInputStream` avvolto in un
`BufferedInputStream` da 64 KB. Il commento nel codice lo dice esplicitamente: quel buffer serve a
**risparmiare le syscall**, non le `update`. `CipherInputStream` continua a leggere a blocchi di
512 byte e a chiamare `cipher.update` una volta per blocco — cioè esattamente la trappola
documentata in `CLAUDE.md` (~1,9 MB/s su un file da 7 MB). Oggi quella funzione non è usata in
produzione: la esercita solo un test.

Il percorso veloce del progetto è `transcodeTo`, che legge a blocchi da 64 KB con `fill()` e il cui
commento dichiara di **non** usare `CipherInputStream` proprio per questo motivo.

Serve quindi il gemello di `transcodeTo` che consegna il **chiaro** invece del ricifrato:

```kotlin
suspend fun AesGcmStreams.decryptTo(key: SecretKey, sourceFile: File, sink: suspend (ByteArray) -> Unit)
suspend fun CryptoManager.decryptTo(key: SecretKey, encryptedFile: File, sink: suspend (ByteArray) -> Unit)
```

Aggiunta puramente additiva, coperta da due unit test in `AesGcmStreamsTest`.

## C. Pattern da rispettare

**Server di rete** — la forma è fissata da `UploadServer` e `TransferServer`:
- `@Singleton class … @Inject constructor(…)`, con `@ApplicationContext` se serve il context;
- `private val _state = MutableStateFlow<…State>(Idle)` + `val state = _state.asStateFlow()`;
- `sealed interface …State { Idle; Starting; Running(url, …); Failed(message) }`;
- `start()` **idempotente** (`if (engine != null) return`), che verifica IP e porta e produce
  `Failed` con messaggio in italiano rivolto all'utente ("Nessuna connessione Wi-Fi rilevata…");
- `stop()` con `engine?.stop(100, 500)`, azzeramento del riferimento e ritorno a `Idle`
  preservando un eventuale `Failed` (`UploadServer.kt:149-154`);
- `private const val TAG = "…"` a livello di file e `Log.w/e` per gli errori.

**Range di porte dedicato** — `8080..8090` upload, `8091..8100` trasferimento
(`TransferProtocol.kt`): il WebDAV prende **`8101..8110`**.

**Capability token** — `NetworkUtils.generateToken()` (6 caratteri, alfabeto senza caratteri
ambigui), token come primo segmento del path, confronto esplicito in ogni rotta e `404` se non
combacia. Da tenere: il `404` non deve distinguere "token sbagliato" da "path inesistente".

**Ciclo di vita della schermata** — `WifiUploadScreen.kt:56-64`:
`LaunchedEffect(Unit) { viewModel.start() }` e `DisposableEffect(Unit)` che aggiunge
`FLAG_KEEP_SCREEN_ON` all'ingresso e, in `onDispose`, lo rimuove **e chiama `viewModel.stop()`**.
`FtpSyncScreen.kt` usa la stessa `findActivity()` per raggiungere la `Window`.

**Logica pura fuori da Android** — è la regola dichiarata in `CLAUDE.md` e il motivo per cui
`PlaylistRemapper`, `AesGcmStreams`, `TransferCrypto` e il manifest sono testabili. `LibraryTree`
e `WebDavXml` devono seguirla: nessun `android.*`, nessun `Log`, nessun `Context`.

**Streaming AES-GCM** — la trappola documentata in `CLAUDE.md`: il provider AEAD accumula fino a
`doFinal`, quindi il costo cresce col **numero** di `update`. `AesGcmStreams` accorpa le letture
in blocchi da 64 KB con `fill()` (`AesGcmStreams.kt:171`) prima di ogni `update`; il
trasferimento usa `transcodeTo` invece di `CipherInputStream` proprio per questo.
Il `GET` deve passare dalla nuova `CryptoManager.decryptTo` (vedi B.5) — **mai**
`CipherInputStream`, e quindi nemmeno `decryptingStream`, che ci si appoggia.

**Forma della risposta in streaming** — `TransferServer.kt:386-404`:
`call.respondBytesWriter(contentType, contentLength = …) { withContext(Dispatchers.IO) { … ; channel.flush() } }`.
Qui al posto di `transcodeTo` si chiama `decryptTo`.

**Lingua** — commenti e messaggi utente in italiano, come tutto il progetto.

**Naming e collocazione** — `domain/<feature>/` per la logica, `ui/<feature>/` per Compose +
ViewModel, `Screen.<Nome> : Screen("snake_case")` nel nav graph.

## D. Test da creare o aggiornare

Convenzione esistente (`PlaylistRemapperTest.kt`): **JUnit 4** (`org.junit.Test`,
`org.junit.Assert.*`), nomi di test in italiano fra backtick, fixture costruite da helper privati
nel test stesso. Dipendenze già presenti: `libs.junit`, `libs.kotlinx.coroutines.test`.

### D.1 `LibraryTreeTest` (unit, JVM) — nuovo

- albero a tre livelli da una `List<Track>`, **anche quando l'artista ha un solo album** (D2);
- `artist` vuoto o di soli spazi → `Sconosciuto`; `album` vuoto → `Senza album`; `title` vuoto →
  `Senza titolo`;
- `trackNumber = "3/12"` → prefisso `03`; `trackNumber = null` → nessun prefisso;
  `trackNumber = "abc"` → nessun prefisso;
- caratteri vietati e di controllo sostituiti; segmento che terminerebbe con `.` o spazio ripulito;
- segmento più lungo di 120 caratteri troncato;
- **due brani omonimi** → suffisso `[id8]` su entrambi; **lo stesso albero ricostruito due volte
  dà gli stessi nomi**; e i nomi **non cambiano** aggiungendo un terzo brano scorrelato — è il
  test che protegge il requisito principale;
- `resolve` su path esistente (collection e file), inesistente, e con segmenti vuoti.

### D.2 `WebDavXmlTest` (unit, JVM) — nuovo

- href con spazi e accenti percent-encoded (`Fabrizio De André` → `Fabrizio%20De%20Andr%C3%A9`);
- album con `&` → percent-encoding **e poi** `&amp;` nell'XML, non l'uno o l'altro;
- collection: href con `/` finale e `<D:resourcetype><D:collection/></D:resourcetype>`;
- file: `getcontentlength` col valore passato, `getcontenttype` `audio/mpeg`/`audio/flac`;
- prefisso `/{token}` presente in **ogni** href, anche in quelli dei figli;
- `getlastmodified` in formato RFC 1123 GMT a partire da un epoch millis fisso;
- risposta `Depth: 0` con un solo `<D:response>`, `Depth: 1` con risorsa + figli.

### D.3 Test aggiornati

`AesGcmStreamsTest` — due casi nuovi per la primitiva `decryptTo` di B.5: round-trip di un payload
oltre la soglia dei 64 KB con conteggio dei blocchi consegnati (è ciò che distingue il percorso
lineare da quello quadratico), e rilevamento di un file alterato. Gli altri test esistenti
(`PlaybackQueueTest`, `TransferCryptoTest`, `TransferManifestTest`, `PlaylistRemapperTest`) non
sono toccati.

### D.4 Ciò che i test automatici **non** coprono

Il progetto non ha test strumentati, quindi restano necessariamente manuali: routing effettivo di
`PROPFIND` su CIO, interoperabilità con rclone, `FLAG_KEEP_SCREEN_ON`, throughput reale,
comportamento alla chiusura della schermata durante un pull. Vanno in `test-manuale.md`, come
fatto per la feature 001.

## E. Rischi tecnici aggiornati

| # | Rischio | Stato dopo l'analisi |
|---|---|---|
| R1 | Ktor CIO non instrada `PROPFIND` | **Chiuso**. Verificato sul bytecode di `ktor-http-cio` 2.3.12 (`parseHttpMethodFull` → `new HttpMethod(token)`), su `HttpMethod` data class e su `route(path, HttpMethod, …)` in `ktor-server-core`. M0 scende da spike a smoke test |
| R2 | `getcontentlength` diverso dai byte serviti | **Confermato e mitigato**: `length() - 28`, da una sola funzione condivisa fra PROPFIND e GET. Il framing è documentato in `TransferServer.kt:383-386` e fissato da `AesGcmStreams` |
| R3 | Encoding/escaping degli href | **Invariato**, alto. Isolato in `WebDavXml` con test dedicati sui casi sporchi |
| R4 | Nomi collidenti instabili | **Invariato**, mitigato dal suffisso da UUID e da un test esplicito di stabilità |
| R5 | Audio in chiaro sulla LAN | **Invariato**, deviazione consapevole. Stessa postura di `UploadServer`, che riceve già file in chiaro su HTTP |
| R6 | Trappola AES-GCM | **Rivalutato**: `decryptingStream` *non* mitiga nulla (vedi B.5), serve la nuova `decryptTo`. Con quella il rischio è mitigato per costruzione; misura di controllo in collaudo |
| R7 | Sessione lunga a schermo acceso | **Invariato**, mitigato da `FLAG_KEEP_SCREEN_ON` e dalla ripresa del pull |
| R8 | Varianti di rclone | **Invariato**, basso. Fissare `vendor=other` e annotare la versione collaudata |
| **R9** | **`HEAD` non risponde da solo** | **Nuovo**. In Ktor una rotta `get` non serve automaticamente `HEAD`: serve il plugin `AutoHeadResponse` oppure una rotta `head` esplicita. rclone usa `HEAD` per verificare le singole risorse: se manca, fallisce in modo poco leggibile. Vedi F.2 |
| **R10** | **Cache a 60 s vs. `GET` in corso** | **Nuovo**. Con la cache a tempo (D1) un brano cancellato dalla libreria può restare nell'albero fino a un minuto: la `GET` successiva deve rispondere `404` verificando il file su disco, non fidarsi della cache |
| **R11** | **`originalExtension` di default `'mp3'`** | **Nuovo, accettato**. La colonna è stata aggiunta dalla migrazione `5→6` con `DEFAULT 'mp3'` (`PlayerDatabase.kt:84`): i record anteriori riportano `mp3` a prescindere. L'utente ha dichiarato il dato affidabile (D5), quindi **non si implementa alcun fallback**; il rischio residuo è che un eventuale FLAC pre-v6 venga esposto con estensione `.mp3` (contenuto integro, nome sbagliato) |

## F. Prerequisiti e task bloccanti

### F.1 — Smoke test `PROPFIND` *(non più bloccante, primo esercizio di M3)*

Alla prima esecuzione del server, prima di scrivere il resto delle rotte:

```bash
curl -i -X PROPFIND -H 'Depth: 1' http://<ip>:8101/<token>/
```

Attesa: `207`. Se invece rispondesse `405`/`400`, il fallback resta intercettare la richiesta in
un plugin `onCall` a monte del routing.

### F.2 — Scelta su `HEAD` *(da fissare prima di M3)*

Due opzioni, entrambe accettabili: installare `AutoHeadResponse` nel modulo Ktor, oppure
dichiarare una rotta `head` esplicita che imposta gli stessi header senza corpo. La seconda è più
verbosa ma tiene tutto esplicito e non introduce un plugin nuovo nel progetto: **si propone la
rotta esplicita**, coerente con lo stile degli altri due server, che non installano plugin.

### F.3 — Forma della cache a 60 secondi *(da fissare in M3)*

Da D1: `LibraryTree` viene ricostruito da `getAllTracksOnce()` solo se l'ultima costruzione ha
più di 60 s. Serve fissare due dettagli:
- la cache va **invalidata all'avvio del server**, così che aprire la schermata dia sempre una
  vista fresca;
- come da R10, la `GET` non deve fidarsi della cache per l'esistenza del file: `File(track.uri)`
  va verificato, e se manca si risponde `404`.

### F.4 — `Content-Type` per estensione *(non bloccante)*

`audio/mpeg` per `mp3`, `audio/flac` per `flac`, coerenti con `SupportedAudioExtensions`
(`util/AudioFormats.kt`). Una funzione unica usata sia da `getcontenttype` sia dalla `GET`.

### F.5 — Icona della voce di menu *(non bloccante)*

Le voci esistenti usano `Icons.Default.*` (`Dns`, `CloudDownload`, `Wifi`, `Upload`, `Download`).
Serve sceglierne una non ancora usata per "Esporta su PC" — `DriveFileMove` o `FolderShared`.

### F.6 — Decisioni di prodotto: **tutte chiuse**

Le cinque domande aperte della Fase 1 hanno risposta (vedi § Decisioni). Nessun blocco residuo
verso la Fase 3.

### F.7 — Impatto sulla stima di Fase 1

| Voce | Fase 1 | Ora | Motivo |
|---|---|---|---|
| Spike PROPFIND | 0,5 | **0,0** | R1 chiuso in analisi; resta uno smoke test dentro M3 |
| Server WebDAV | 1,5 | **1,5** | Invariato; la rotta `head` esplicita (F.2) è marginale |
| Primitiva `decryptTo` + test | — | **+0,25** | Non prevista in Fase 1: vedi B.5 |
| Altre voci | 4,0 | **4,0** | Invariate |
| **Totale** | **6,0** | **5,75** | |

Milestone aggiornate: **M0 assorbita in M3**, l'ordine vincolante diventa M1 → M2 → M3, con M4
(UI) sovrapponibile a M3.
