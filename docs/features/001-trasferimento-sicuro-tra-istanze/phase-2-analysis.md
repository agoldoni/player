# Fase 2 — Analisi tecnica: Trasferimento sicuro della libreria tra due istanze

> Feature slug: `trasferimento-sicuro-tra-istanze`
> Documento precedente: [`phase-1-requirements.md`](./phase-1-requirements.md)
> Stato: analisi su codebase reale — in attesa di conferma per Fase 3

Tutti i percorsi, i nomi di classe e di funzione citati sotto sono stati verificati sul codice
alla revisione corrente (`main`, HEAD `397671f`). Dove qualcosa **non esiste** è dichiarato
esplicitamente.

## Sintesi dell'esplorazione

Il progetto possiede già **tutti i mattoni fondamentali** di questa feature, montati per altri
scopi:

| Mattone | Dove | Riusabile per |
|---|---|---|
| Server HTTP Ktor CIO su LAN + capability URL | [`UploadServer.kt`](../../../app/src/main/java/it/agoldoni/player/domain/upload/UploadServer.kt) | lato mittente |
| IP locale, porta libera, token casuale | [`NetworkUtils.kt`](../../../app/src/main/java/it/agoldoni/player/util/NetworkUtils.kt) | pairing e bind |
| Cifratura/decifratura AES-GCM streaming | [`CryptoManager.kt`](../../../app/src/main/java/it/agoldoni/player/domain/CryptoManager.kt) | ricifratura per hop |
| Pipeline di import da `File` + dedup | [`ImportTrackUseCase.kt`](../../../app/src/main/java/it/agoldoni/player/domain/ImportTrackUseCase.kt), [`SyncFromFtpUseCase.kt`](../../../app/src/main/java/it/agoldoni/player/domain/ftp/SyncFromFtpUseCase.kt) | lato destinatario |
| Progresso come `Flow<SyncProgress>` / `StateFlow<UploadServerState>` | stessi file | UI di trasferimento |
| Cleanup dei temporanei allo startup | [`OrphanCleanupUseCase.kt`](../../../app/src/main/java/it/agoldoni/player/domain/OrphanCleanupUseCase.kt) | `transfer_temp/` |

Quello che **manca davvero** è: un client HTTP, un handshake autenticato, un formato di manifest,
un percorso di import che **preservi i metadati** invece di riestrarli, e le query one-shot su
playlist.

---

## A. File coinvolti

### A.1 Nuovi file — dominio

| Percorso | Contenuto | Note |
|---|---|---|
| `app/src/main/java/it/agoldoni/player/domain/transfer/TransferProtocol.kt` | Versione di protocollo, costanti, rotte, modello `TransferManifest` / `ManifestTrack` / `ManifestPlaylist`, serializzazione | Nuovo package `domain/transfer/`, in analogia a `domain/ftp/` e `domain/upload/` |
| `app/src/main/java/it/agoldoni/player/domain/transfer/TransferCrypto.kt` | ECDH effimero (secp256r1), HKDF-SHA256, derivazione SAS a 6 cifre, cifratura/decifratura streaming con chiave di sessione, contatore di nonce | **Puro JCA, senza API Android** → unit-testabile su JVM come `PlaybackQueue` |
| `app/src/main/java/it/agoldoni/player/domain/transfer/TransferServer.kt` | Lato mittente: `embeddedServer(CIO)`, rotte di pairing/manifest/track/art, `StateFlow<TransferServerState>` | Ricalca 1:1 la struttura di `UploadServer` |
| `app/src/main/java/it/agoldoni/player/domain/transfer/TransferClient.kt` | Lato destinatario: chiamate HTTP a pairing/manifest/track/art, decifratura di sessione su stream | Dipende dalla scelta di F.1 |
| `app/src/main/java/it/agoldoni/player/domain/transfer/ReceiveLibraryUseCase.kt` | Orchestratore: pairing → manifest → per ogni brano download/decifra/import → mappa ID → ricostruzione playlist. Emette `Flow<TransferProgress>` | Gemello strutturale di `SyncFromFtpUseCase` |
| `app/src/main/java/it/agoldoni/player/domain/transfer/TransferSelection.kt` | Modello della selezione lato mittente (tutta la libreria / playlist / brani) + calcolo conteggio e dimensione | Piccolo, può confluire in `TransferProtocol.kt` |
| `app/src/main/java/it/agoldoni/player/domain/transfer/PeerDiscovery.kt` | `NsdManager`: registrazione del servizio `_playerxfer._tcp` sul mittente, discovery + risoluzione sul destinatario | Vedi rischio R4' |

### A.2 Nuovi file — UI

| Percorso | Contenuto |
|---|---|
| `app/src/main/java/it/agoldoni/player/ui/transfer/SendLibraryScreen.kt` | Selezione contenuti, PIN/SAS, progresso, riepilogo. `FLAG_KEEP_SCREEN_ON` come in `WifiUploadScreen.kt:59-66` |
| `app/src/main/java/it/agoldoni/player/ui/transfer/SendLibraryViewModel.kt` | `StateFlow` dal `TransferServer` + `Channel<SendLibraryEvent>` |
| `app/src/main/java/it/agoldoni/player/ui/transfer/ReceiveLibraryScreen.kt` | Lista peer trovati (o IP manuale), inserimento/conferma PIN, progresso, riepilogo |
| `app/src/main/java/it/agoldoni/player/ui/transfer/ReceiveLibraryViewModel.kt` | Avvio/cancel del `Flow<TransferProgress>`, come `FtpSyncViewModel` |

### A.3 Nuovi file — risorse e test

| Percorso | Contenuto |
|---|---|
| `app/src/main/res/xml/network_security_config.xml` | Abilitazione cleartext per il traffico LAN (vedi R2') |
| `app/src/test/java/it/agoldoni/player/domain/transfer/TransferCryptoTest.kt` | Handshake, HKDF, SAS, roundtrip GCM, chiave errata |
| `app/src/test/java/it/agoldoni/player/domain/transfer/TransferManifestTest.kt` | Serializzazione/deserializzazione, versione di protocollo incompatibile |
| `app/src/test/java/it/agoldoni/player/domain/transfer/PlaylistRemapTest.kt` | Ricostruzione playlist con mappa `idOrigine → idLocale` |

### A.4 File da modificare

| Percorso | Modifica | Motivazione |
|---|---|---|
| [`domain/CryptoManager.kt`](../../../app/src/main/java/it/agoldoni/player/domain/CryptoManager.kt) | Aggiungere primitive che accettano una `SecretKey` **arbitraria** e lavorano su `InputStream`/`OutputStream` | Oggi `encryptFile`/`decryptToTempFile` accettano sì una `SecretKey`, ma scrivono/leggono **solo `File`**: per lo streaming HTTP servono varianti su stream. Nessuna modifica alle firme esistenti |
| [`domain/ImportTrackUseCase.kt`](../../../app/src/main/java/it/agoldoni/player/domain/ImportTrackUseCase.kt) | Nuovo entry point `importTransferred(file, manifestTrack, artBytes, dek): String?` che ritorna l'**ID locale** | **Blocco reale**: `importLocalFile` (righe 65-95) riestrae i metadati con `MetadataExtractor` e genera un **nuovo UUID**, scartando `year`, `trackNumber`, `importedAt` e l'ID di origine ricevuti nel manifest |
| [`data/local/dao/PlaylistDao.kt`](../../../app/src/main/java/it/agoldoni/player/data/local/dao/PlaylistDao.kt) | Aggiungere `getAllPlaylistsOnce()`, `getTrackIdsForPlaylist(playlistId)`, `insertCrossRefs(list)` | Oggi esistono solo varianti `Flow` (`getAllPlaylists`, `getPlaylistWithTracks`): il mittente e il destinatario servono letture/scritture one-shot |
| [`data/local/dao/TrackDao.kt`](../../../app/src/main/java/it/agoldoni/player/data/local/dao/TrackDao.kt) | Aggiungere `getTracksByIds(ids)` | Serve al mittente per materializzare la selezione |
| [`data/repository/PlaylistRepository.kt`](../../../app/src/main/java/it/agoldoni/player/data/repository/PlaylistRepository.kt), [`TrackRepository.kt`](../../../app/src/main/java/it/agoldoni/player/data/repository/TrackRepository.kt) | Esporre i nuovi metodi | I repository sono pass-through puri: la convenzione è propagare ogni query |
| [`domain/OrphanCleanupUseCase.kt`](../../../app/src/main/java/it/agoldoni/player/domain/OrphanCleanupUseCase.kt) | Aggiungere `cleanupTransferTempCache()` per `cacheDir/transfer_temp` | Il file ha già un metodo per ciascuna cache (`import_temp`, `ftp_temp`, `upload_temp`): stesso schema |
| [`ui/navigation/PlayerNavGraph.kt`](../../../app/src/main/java/it/agoldoni/player/ui/navigation/PlayerNavGraph.kt) | Due `Screen` (`SendLibrary`, `ReceiveLibrary`) + due `composable` | Stesso schema di `Screen.WifiUpload` |
| [`ui/PlayerApp.kt`](../../../app/src/main/java/it/agoldoni/player/ui/PlayerApp.kt) | Due `NavigationDrawerItem` ("Invia libreria", "Ricevi libreria") | Il drawer ha già 6 voci con lo stesso pattern |
| [`app/src/main/AndroidManifest.xml`](../../../app/src/main/AndroidManifest.xml) | `android:networkSecurityConfig="@xml/network_security_config"` sull'`<application>` | Vedi R2' |
| [`app/build.gradle.kts`](../../../app/build.gradle.kts), [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) | Client HTTP e (eventuale) serializzazione | Vedi F.1 e F.2 |
| [`CLAUDE.md`](../../../CLAUDE.md) | Nuovo flusso in "Key flows", nuovo package in "Architecture" | **Nota**: il file dice `player_db v5`, ma il DB è a **v6** (`PlayerDatabase.kt:17`, `MIGRATION_5_6` aggiunge `tracks.originalExtension`). Da correggere contestualmente |
| [`ui/info/AppInfoScreen.kt`](../../../app/src/main/java/it/agoldoni/player/ui/info/AppInfoScreen.kt) | Eventuale nota d'uso | Facoltativo |

### A.5 File esplicitamente **non** toccati

`PlaybackService.kt`, `PlaybackQueue.kt`, `PlaybackManager.kt`, `PlaybackBar.kt`, tutto
`domain/ftp/`, `UploadServer.kt`, `MainActivity.kt`, `PlayerDatabase.kt` (**nessuna migrazione**:
lo schema non cambia), `DatabaseModule.kt` (i nuovi componenti sono `@Singleton` con
`@Inject constructor`, come `UploadServer` — nessun `@Provides` necessario).

---

## B. Contratti e interfacce da modificare

### B.1 Nuovo contratto: protocollo HTTP fra le due istanze

Non esiste nulla di simile oggi (l'unico "contratto" HTTP è `POST /{token}/upload?name=` verso un
browser). Proposta, tutte le rotte sotto il capability token come in `UploadServer`:

| Metodo e rotta | Richiesta | Risposta |
|---|---|---|
| `POST /{token}/pair` | chiave pubblica effimera del destinatario | chiave pubblica effimera del mittente + `sessionId` + `protocolVersion` |
| `GET /{token}/manifest` | header `X-Session: <sessionId>` | manifest JSON **cifrato** con la chiave di sessione |
| `GET /{token}/track/{trackId}` | header `X-Session` | audio in chiaro-DEK ricifrato con chiave di sessione, streaming |
| `GET /{token}/art/{trackId}` | header `X-Session` | copertina PNG cifrata con chiave di sessione |
| `POST /{token}/done` | esito | chiusura sessione, PIN invalidato |

Regole: token errato → `404` (identico a `UploadServer.kt:159-162`); `X-Session` assente o non
confermato → `401`; PIN non ancora confermato lato mittente → `409` finché l'utente non approva.

**Il PIN/SAS non viaggia mai sulla rete**: è derivato in locale dai due lati come
`HKDF(sharedSecret ‖ pubA ‖ pubB) → 6 cifre` e confrontato dall'utente sui due schermi.

### B.2 Nuovo contratto: manifest di trasferimento

```
{
  "protocolVersion": 1,
  "device": "Pixel 7",
  "trackCount": 214, "totalBytes": 1234567890,
  "tracks": [{
    "id","title","artist","album","duration","year","trackNumber",
    "originalExtension","originalFileSize","importedAt","hasArt"
  }],
  "playlists": [{ "id","name","createdAt","lastPlayedTrackId","trackIds":[...] }]
}
```

`protocolVersion` è il punto di compatibilità fra versioni diverse dell'app installate sui due
device: mismatch → errore esplicito, non tentativo di interpretazione.

### B.3 Modifiche a contratti interni (tutte **additive**, nessun breaking change)

- `CryptoManager`: nuove funzioni (es. `encryptStream(key, source, out)` / `decryptStream(key, input, out)`).
  Le esistenti `encryptFile`, `decryptToTempFile`, `encryptBytes`, `decryptBytes` restano invariate.
- `ImportTrackUseCase`: nuovo overload; i due `invoke` esistenti (`Uri` e `File`) non cambiano.
  Ritorna `String?` (ID locale) invece di `Boolean` perché serve per la mappa ID.
- `TrackDao` / `PlaylistDao` / repository: solo nuove query.
- **DB**: nessuna modifica di schema → **nessuna migrazione**, la versione resta 6.

### B.4 Punto di attenzione: rimappatura degli ID

`Track.id` e `Playlist.id` sono UUID generati all'import (`Track.kt:10`, `MetadataExtractor.kt:37`).
Il destinatario **non può** riusare gli ID di origine senza rischiare collisioni con brani già
presenti; deve quindi mantenere in memoria `idOrigine → idLocale` durante il trasferimento e
usarla per popolare `playlist_track_cross_ref` e `lastPlayedTrackId`. Per i brani saltati per
dedup la mappa punta all'**ID del brano già esistente**, così le playlist restano complete.

---

## C. Pattern da rispettare

Rilevati leggendo il codice, non ipotizzati:

1. **Commenti e messaggi in italiano**, KDoc di blocco sopra ogni classe che spiega *il perché*
   della scelta tecnica (esemplare: `UploadServer.kt:64-85`, che motiva il body grezzo al posto
   del multipart).
2. **Singleton Hilt**: `@Singleton class X @Inject constructor(...)` — nessun `@Provides` per le
   classi proprie (`DatabaseModule` fornisce solo DB e DAO).
3. **Stato come `sealed interface` nel dominio**: `SyncProgress` (`SyncFromFtpUseCase.kt:23-44`),
   `UploadServerState` (`UploadServer.kt:48-62`). I nuovi `TransferServerState` /
   `TransferProgress` seguono la stessa forma, contatori inclusi (`added` / `skipped` / `errors` /
   `current` / `total` / `currentFileName`).
4. **Due modelli di esposizione**, da scegliere per lato:
   - server long-running → `StateFlow` nel singleton, ViewModel che lo espone (`WifiUploadViewModel`);
   - operazione con inizio e fine → `Flow` emesso dallo use case + `MutableStateFlow` nel ViewModel
     e `Job` cancellabile (`FtpSyncViewModel.kt:43-55`).
5. **Eventi one-shot**: `Channel<XEvent>(Channel.BUFFERED)` + `receiveAsFlow()`, presente in 6
   ViewModel (`FtpConfigViewModel.kt:63`, `TrackListViewModel.kt:89`, …).
6. **UI**: `collectAsStateWithLifecycle()`, `Scaffold` + `TopAppBar` con freccia "Indietro",
   `hiltViewModel()`, `DisposableEffect` per start/stop delle risorse e `FLAG_KEEP_SCREEN_ON`
   (`WifiUploadScreen.kt:59-66`).
7. **Guardia DEK**: ogni flusso che tocca file cifrati legge `cryptoManager.sessionDek` e fallisce
   con messaggio esplicito se `null` (`SyncFromFtpUseCase.kt:71-75`, `UploadServer.kt:172-180`).
8. **Cancellation-aware**: `coroutineContext.ensureActive()` fra un file e il successivo, con
   `CancellationException` che produce uno stato `Done(cancelled = true)` (`SyncFromFtpUseCase.kt:109`, `:138-148`).
9. **Serializzazione degli import**: `Mutex` attorno a dedup + import per evitare race su
   `(title, artist, album)` (`UploadServer.kt:99-101`, `:229-249`). Necessario anche qui.
10. **Temporanei**: sempre `File(context.cacheDir, "<nome>_temp")`, un file per volta, `delete()`
    in ogni ramo (successo, errore, skip) e cleanup di sicurezza allo startup.
11. **Rete**: `NetworkUtils.getLocalIpAddress()`, `firstFreePort(range)`, `generateToken()` con
    alfabeto senza caratteri ambigui.
12. **Formati supportati**: sempre via `SupportedAudioExtensions` / `supportedExtensionFromPath`
    (`AudioFormats.kt`), mai stringhe letterali `"mp3"` sparse.

---

## D. Test da creare o aggiornare

**Stato attuale verificato**: esiste **un solo** file di test, `PlaybackQueueTest.kt` (JUnit 4,
JVM puro, nessun mock, `app/src/androidTest/` **non esiste**). Le dipendenze di test sono solo
`junit 4.13.2` e `kotlinx-coroutines-test 1.7.3`: niente Robolectric, MockK o Mockito.

Conseguenza progettuale: **la logica testabile va tenuta fuori dalle API Android**.
`TransferCrypto` (JCA) e la serializzazione del manifest lo permettono; `TransferServer`,
`TransferClient` e `PeerDiscovery` no.

| Area | Tipo | File | Copertura |
|---|---|---|---|
| Handshake e cifratura | unit JVM (nuovo) | `TransferCryptoTest.kt` | chiavi di sessione identiche sui due lati; SAS identica e deterministica; SAS diversa se le chiavi pubbliche cambiano (anti-MITM); roundtrip encrypt/decrypt su stream; `AEADBadTagException` con chiave o nonce sbagliati; nonce mai riusato nella stessa sessione |
| Manifest | unit JVM (nuovo) | `TransferManifestTest.kt` | roundtrip completo; campi opzionali null; `protocolVersion` diversa → errore tipizzato; caratteri speciali nei titoli (oggi l'escaping JSON è fatto a mano in `UploadServer.kt:271-286`) |
| Rimappatura playlist | unit JVM (nuovo) | `PlaylistRemapTest.kt` | brano nuovo, brano dedup, brano fallito; `lastPlayedTrackId` rimappato o azzerato; playlist omonima già presente |
| Selezione contenuti | unit JVM (nuovo) | dentro `TransferManifestTest.kt` | conteggio e byte totali per "tutta la libreria" / playlist / sottoinsieme |
| Import con metadati preservati | unit JVM **non praticabile** | — | `ImportTrackUseCase` dipende da `Context`, `MediaMetadataRetriever` e Room → copertura solo manuale |
| Flusso end-to-end | manuale (nuovo doc) | `docs/features/001-.../test-manuale.md` | checklist a due device |

**Checklist manuale minima** (due device, o device release + device debug — i package
`it.agoldoni.player` e `it.agoldoni.player.debug` convivono):
libreria vuota → piena; libreria parzialmente sovrapposta (dedup); PIN sbagliato ×3;
Wi-Fi staccato a metà trasferimento e ripresa; schermo bloccato durante l'invio; app in
background; spazio disco insufficiente; DEK non sbloccata su un lato; playlist con brani
condivisi; brano con copertina e brano senza; file `.flac` oltre a `.mp3`.

---

## E. Rischi tecnici aggiornati

I rischi della Fase 1 con l'evidenza trovata nel codice. Le voci con apice sono **nuove**.

| # | Stato | Evidenza dalla codebase |
|---|---|---|
| R1 PIN a bassa entropia | **Confermato, mitigabile** | `NetworkUtils.generateToken()` mostra che il progetto usa già capability token; ECDH/HKDF non esistono ancora. `KeyAgreement("ECDH")` su curva `secp256r1` e `Mac("HmacSHA256")` sono disponibili da API 26 → **HKDF va scritto a mano** (non esiste in JCA). `XDH`/X25519 richiede API 33: **da non usare** con `minSdk = 26` |
| R2 Cleartext bloccato | **Confermato** | Nel manifest non c'è né `usesCleartextTraffic` né `networkSecurityConfig`; `targetSdk = 34` → default `false`. Finora irrilevante perché l'app è solo *server* (il client è il browser del PC); qui per la prima volta l'app fa da **client HTTP** |
| R2' Efficacia della policy | **Nuovo** | La policy è applicata dagli stack che la consultano (`HttpURLConnection`, OkHttp). Un engine a socket puri potrebbe aggirarla di fatto: **non farci affidamento**, dichiarare esplicitamente la configurazione |
| R3 Manca il client HTTP | **Confermato** | `libs.versions.toml` ha solo `ktor-server-core` e `ktor-server-cio` (ktor 2.3.12). Nessun client, nessun OkHttp (Coil 2.5.0 lo porta transitivamente ma non è una dipendenza dichiarata: usarlo di riflesso sarebbe fragile) |
| R4 Discovery del peer | **Confermato** | Nessun uso di `NsdManager` nel progetto; `NetworkUtils` sa solo trovare l'IP locale. Fallback IP manuale sempre necessario |
| R4' Permessi rete futuri | **Nuovo, da verificare** | Con `targetSdk = 34` non servono permessi aggiuntivi per NSD/LAN. L'eventuale innalzamento del targetSdk va verificato rispetto alle restrizioni di accesso alla rete locale introdotte dalle versioni successive di Android |
| R5 Doppia cifratura | **Confermato** | `CryptoManager` usa buffer da 8 KB (`encryptFile`, `decryptToTempFile`), `UploadServer` da 64 KB. Allineare la nuova via a 64 KB e misurare |
| R5' Nessuna primitiva su stream | **Nuovo, bloccante** | `encryptFile(dek, source: File, dest: File)` e `decryptToTempFile(...): File` lavorano solo su file: senza varianti su stream il mittente dovrebbe scrivere un temp in chiaro **e** un temp ricifrato per ogni brano |
| R6 Spazio disco | **Confermato** | `ImportTrackUseCase.importLocalFile` tiene temp in chiaro + file cifrato contemporaneamente; il manifest porta `totalBytes` → controllo preventivo possibile |
| R7 Trasferimento in primo piano | **Mitigato dall'esistente** | `WifiUploadScreen` già applica `FLAG_KEEP_SCREEN_ON` e ferma il server in `onDispose`; `WifiUploadViewModel.onCleared()` fa altrettanto |
| R8 Dedup grossolana | **Confermato** | `TrackDao.getTrackByMetadata(title, artist, album)` è l'unica chiave usata da FTP e upload Wi-Fi |
| R8' Metadati riestratti | **Nuovo, bloccante** | `importLocalFile` chiama `metadataExtractor.extract()` e usa il `Track` così ottenuto: `year`, `trackNumber` e `importedAt` del mittente verrebbero **persi** e l'ID rigenerato. Senza il nuovo entry point, il criterio di accettazione "metadati identici all'origine" non è soddisfacibile |
| R9 Collisione ID | **Confermato** | Vedi B.4 |
| R9' Playlist: solo API reattive | **Nuovo** | `PlaylistDao` non ha letture one-shot delle relazioni: `getPlaylistWithTracks` ritorna `Flow`. Servono query nuove |
| R10 Nessun test strumentato | **Confermato** | Un solo test JVM; `androidTest/` assente |
| R11' Serializzazione JSON assente | **Nuovo** | Nessuna libreria JSON nel progetto: `UploadServer` costruisce il JSON a mano con escaping custom. `org.json` è nel framework Android ma **negli unit test JVM è uno stub che lancia** (servirebbe `testImplementation("org.json:json")` o Robolectric) → argomento forte a favore di kotlinx.serialization (vedi F.2) |
| R12' Collisione di porte | **Nuovo** | `UploadServer` occupa il range `8080..8090`. Se l'utente lascia aperta "Ricevi via Wi-Fi" e apre "Invia libreria", i due server competono: usare un **range distinto** (es. `8091..8100`) e non condividere il singleton |
| R13' Ktor CIO come client | **Nuovo, da verificare in spike** | `ktor-client-cio` 2.3.12 su Android con `minSdk 26` non è mai stato provato in questo progetto: va verificato in uno spike da mezza giornata prima di committare la scelta |

---

## F. Prerequisiti e task bloccanti

### F.1 — Scelta del client HTTP *(bloccante, precede M0)*

| Opzione | Pro | Contro |
|---|---|---|
| `ktor-client-cio` 2.3.12 (**consigliata**) | Stessa versione e stesso modello del server, API `suspend` e streaming naturali, nessun nuovo ecosistema | Nuova dipendenza; da validare su Android 8 (R13') |
| `HttpURLConnection` | Zero dipendenze | Codice bloccante da avvolgere a mano, gestione stream verbosa; **soggetto alla policy cleartext** |
| OkHttp esplicito | Robusto, già presente in transitiva via Coil | Dipendenza in più con versione da fissare |

### F.2 — Scelta della serializzazione del manifest *(bloccante, precede M1)*

| Opzione | Pro | Contro |
|---|---|---|
| `kotlinx-serialization-json` (**consigliata**) | Modelli tipizzati, evoluzione del formato gestita, **funziona negli unit test JVM**, plugin allineato a Kotlin 1.9.22 | Aggiunge plugin + dipendenza |
| `org.json` | Zero dipendenze a runtime | Stub negli unit test (R11'), escaping e parsing a mano |

### F.3 — Primitive di cifratura su stream in `CryptoManager` *(bloccante, precede M3)*
Aggiungere `encryptStream` / `decryptStream` con `SecretKey` arbitraria, buffer 64 KB, e i
relativi test. Prerequisito sia del mittente sia del destinatario (R5').

### F.4 — Entry point di import che preserva i metadati *(bloccante, precede M4)*
Nuovo metodo in `ImportTrackUseCase` che accetta i campi del manifest, salta `MetadataExtractor`,
salva la copertina ricevuta via `AlbumArtSaver.save(bytes)` (già adatto allo scopo) e **ritorna
l'ID locale** (R8', B.4).

### F.5 — Query DAO mancanti *(bloccante, precede M3/M4)*
`TrackDao.getTracksByIds`, `PlaylistDao.getAllPlaylistsOnce`, `getTrackIdsForPlaylist`,
`insertCrossRefs`; propagazione nei repository (R9').

### F.6 — Configurazione di rete *(bloccante, precede M4)*
`res/xml/network_security_config.xml` + attributo nel manifest, con commento che motiva la scelta
(il payload è cifrato a livello applicativo, il cleartext riguarda solo l'involucro HTTP su LAN).

### F.7 — Range di porte dedicato *(non bloccante, ma da fissare in M1)*
Costante `PORT_RANGE = 8091..8100` in `TransferServer`, distinta da quella di `UploadServer`.

### F.8 — Spike di validazione *(0,5 gg, prima di M2)*
Un ramo usa-e-getta che verifichi: `ktor-client-cio` su Android 8, `KeyAgreement("ECDH")` +
`Mac("HmacSHA256")` su API 26, e `NsdManager` fra due device sulla rete di casa.

### F.9 — Decisioni di prodotto ancora aperte *(bloccanti per la Fase 3)*
Restano le 6 domande in coda alla Fase 1. Con le evidenze raccolte, la raccomandazione tecnica è:

1. **Mittente = server** — riusa `UploadServer` quasi 1:1.
2. **SAS derivata dal transcript**, mostrata su entrambi i lati e confermata con un tap: elimina R1
   senza dipendere dall'entropia del PIN.
3. **`importedAt` preservato** dal manifest: rende fedele la schermata Statistiche; è la ragione
   principale per cui serve F.4.
4. **Dedup invariata** `(title, artist, album)`: coerenza con FTP e upload Wi-Fi; `originalFileSize`
   resta nel manifest come dato diagnostico, non come chiave.
5. **`lastPlayedTrackId` rimappato** se il brano esiste sul destinatario, altrimenti `null`.
6. **Playlist omonima → fusione** dei brani nella playlist esistente (l'inserimento è
   `OnConflictStrategy.IGNORE` sui cross-ref, quindi l'operazione è già idempotente).

### F.10 — Impatto sulla stima di Fase 1
Le voci F.3, F.4 e F.5 erano contate dentro le milestone; F.8 (spike) è **nuova**: la stima passa
da **10,5** a **11 gg/uomo**. Nessun'altra revisione: non emergono migrazioni DB né refactoring
strutturali.
