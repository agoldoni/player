# Analisi tecnica codebase — `load-track-from-ftp`

> Documento generato in **Fase 2** dello skill `claude-code-feature-skill`.
> Stato: **bozza in revisione**, da confermare prima di passare alla Fase 3 (documento di implementazione formale).
> Tutte le evidenze sono state verificate leggendo direttamente i file della codebase al commit corrente (`a75c004`).

---

## Premessa: stato della codebase rilevato

Alcune divergenze rispetto a quanto dichiarato in [CLAUDE.md](../../../CLAUDE.md) — vanno considerate prima di pianificare:

- **DB version**: `CLAUDE.md` dice `player_db v2`, ma [`PlayerDatabase.kt`](../../../app/src/main/java/it/agoldoni/player/data/local/PlayerDatabase.kt) è alla **v4** con migrazioni `1→2`, `2→3`, `3→4`. La nostra eventuale migrazione sarà la **`4→5`**.
- **Entità DB**: oltre a `Track` esiste già `Playlist` + `PlaylistTrackCrossRef` (non menzionate in `CLAUDE.md`).
- **Networking**: il progetto **non ha attualmente alcuna dipendenza di rete**: niente OkHttp, niente HttpURLConnection in uso, **nessun permesso `INTERNET` nel manifest**. Si parte da zero sul layer rete.
- **`CryptoManager`**: espone solo `encryptFile`/`decryptToTempFile` (operano su `File`). Non ci sono helper per cifrare bytes/stringhe corte come le credenziali FTP.

---

## A. File coinvolti

Legenda: 🆕 nuovo · ✏️ modifica · 🔁 estensione di pattern esistente

### Manifest e build
| Path | Tipo | Motivazione |
|---|---|---|
| [app/src/main/AndroidManifest.xml](../../../app/src/main/AndroidManifest.xml) | ✏️ | Aggiungere `<uses-permission android:name="android.permission.INTERNET" />` (e probabilmente `ACCESS_NETWORK_STATE` per i controlli di connettività) |
| [gradle/libs.versions.toml](../../../gradle/libs.versions.toml) | ✏️ | Aggiungere `commons-net = "3.10.0"` (FTP/FTPS client). Per SFTP servirebbe `jsch` separato — vedi sez. F |
| [app/build.gradle.kts](../../../app/build.gradle.kts) | ✏️ | Aggiungere `implementation(libs.commons.net)` |

### Data layer
| Path | Tipo | Motivazione |
|---|---|---|
| [app/src/main/java/it/agoldoni/player/data/local/entity/Track.kt](../../../app/src/main/java/it/agoldoni/player/data/local/entity/Track.kt) | ✏️ | Aggiungere campo `sourceFtpPath: String? = null` per dedup stabile contro il path remoto. Nullable per retrocompatibilità con tracce importate da picker |
| `app/src/main/java/it/agoldoni/player/data/local/entity/FtpConfig.kt` | 🆕 | Nuova entità Room per persistere la configurazione FTP (singola riga). In alternativa: vedi nota in sez. B su `EncryptedSharedPreferences` |
| [app/src/main/java/it/agoldoni/player/data/local/dao/TrackDao.kt](../../../app/src/main/java/it/agoldoni/player/data/local/dao/TrackDao.kt) | ✏️ | Aggiungere query `getTrackBySourceFtpPath(path: String): Track?` e `getAllSourceFtpPaths(): List<String>` per dedup batch |
| `app/src/main/java/it/agoldoni/player/data/local/dao/FtpConfigDao.kt` | 🆕 | DAO con `getConfig()` / `upsertConfig(...)` / `clearConfig()` |
| [app/src/main/java/it/agoldoni/player/data/local/PlayerDatabase.kt](../../../app/src/main/java/it/agoldoni/player/data/local/PlayerDatabase.kt) | ✏️ | Bump version da 4 → **5**, aggiungere `FtpConfig` alle `entities`, registrare `MIGRATION_4_5` con `ALTER TABLE tracks ADD COLUMN sourceFtpPath TEXT` + `CREATE TABLE ftp_config` |
| `app/src/main/java/it/agoldoni/player/data/repository/FtpConfigRepository.kt` | 🆕 | Wrapper sul DAO + cifratura/decifratura dei campi sensibili (vedi sez. B) |
| [app/src/main/java/it/agoldoni/player/di/DatabaseModule.kt](../../../app/src/main/java/it/agoldoni/player/di/DatabaseModule.kt) | ✏️ | Aggiungere `MIGRATION_4_5` a `addMigrations(...)` e `provideFtpConfigDao(db)` |

### Domain layer
| Path | Tipo | Motivazione |
|---|---|---|
| `app/src/main/java/it/agoldoni/player/domain/ftp/FtpClientFactory.kt` | 🆕 | Costruisce e configura `FTPClient` (timeout, passive mode, encoding) — un solo punto di creazione per testabilità |
| `app/src/main/java/it/agoldoni/player/domain/ftp/FtpScanner.kt` | 🆕 | Walk ricorsivo su FTP a partire da una root, ritorna `List<FtpRemoteFile>` (path, size). Filtra `.mp3` case-insensitive. Operazione cancellabile via `coroutineContext.ensureActive()` |
| `app/src/main/java/it/agoldoni/player/domain/ftp/FtpDownloader.kt` | 🆕 | Scarica un singolo file remoto in cache temp. Esegue su `Dispatchers.IO`. Cancellation-aware |
| `app/src/main/java/it/agoldoni/player/domain/ImportTrackUseCase.kt` | ✏️ | Aggiungere overload `invoke(localFile: File, dek: SecretKey, sourceFtpPath: String): Boolean` per evitare il roundtrip via `Uri`. In alternativa, **estrarre** la pipeline "metadata→encrypt→DB" in una funzione condivisa per non duplicare logica con il flusso `Uri`-based |
| `app/src/main/java/it/agoldoni/player/domain/SyncFromFtpUseCase.kt` | 🆕 | Orchestratore principale: legge config → connette → scansiona → dedup → loop download+import → emette progresso via `Flow<SyncProgress>` |
| [app/src/main/java/it/agoldoni/player/domain/CryptoManager.kt](../../../app/src/main/java/it/agoldoni/player/domain/CryptoManager.kt) | ✏️ | Aggiungere `encryptBytes(dek, ByteArray): ByteArray` e `decryptBytes(dek, ByteArray): ByteArray` per cifrare le credenziali FTP riusando la DEK già esistente. Senza questi helper si dovrebbe fingere un file su disco — brutto |
| [app/src/main/java/it/agoldoni/player/domain/OrphanCleanupUseCase.kt](../../../app/src/main/java/it/agoldoni/player/domain/OrphanCleanupUseCase.kt) | ✏️ (consigliato) | Aggiungere cleanup di una nuova directory `cacheDir/ftp_temp/` per file scaricati e mai cifrati (es. crash a metà sync) |

### UI layer
| Path | Tipo | Motivazione |
|---|---|---|
| `app/src/main/java/it/agoldoni/player/ui/ftp/FtpConfigScreen.kt` | 🆕 | Form Compose: host, porta, username, password, path radice, "Test connessione", "Salva". Pattern identico a `PlaylistDetailScreen` |
| `app/src/main/java/it/agoldoni/player/ui/ftp/FtpConfigViewModel.kt` | 🆕 | `@HiltViewModel`. State per i campi del form, eventi via `Channel<FtpConfigEvent>` (pattern già usato in `TrackListViewModel`) |
| `app/src/main/java/it/agoldoni/player/ui/ftp/FtpSyncScreen.kt` | 🆕 | Schermata sync: stato `Flow<SyncProgress>`, lista file in elaborazione, contatori (aggiunti/saltati/errori), pulsante "Annulla" |
| `app/src/main/java/it/agoldoni/player/ui/ftp/FtpSyncViewModel.kt` | 🆕 | Tiene un `Job` per la sync, espone `progress: StateFlow<SyncProgress>`, `start()` / `cancel()` |
| [app/src/main/java/it/agoldoni/player/ui/navigation/PlayerNavGraph.kt](../../../app/src/main/java/it/agoldoni/player/ui/navigation/PlayerNavGraph.kt) | ✏️ | Aggiungere route `Screen.FtpConfig` e `Screen.FtpSync` (pattern identico a `Screen.Stats` / `Screen.AppInfo`) |
| [app/src/main/java/it/agoldoni/player/ui/PlayerApp.kt](../../../app/src/main/java/it/agoldoni/player/ui/PlayerApp.kt) | ✏️ | Aggiungere due `NavigationDrawerItem` ("Configura FTP", "Sincronizza da FTP") nel drawer, identici per stile a "Statistiche" / "Info app" (linee 33–56) |

---

## B. Contratti e interfacce da modificare

### B.1 — Schema DB: nuova colonna su `tracks`
```sql
ALTER TABLE tracks ADD COLUMN sourceFtpPath TEXT DEFAULT NULL;
```
Nullable per retrocompatibilità: i record esistenti restano `NULL`. Non è breaking.

### B.2 — Schema DB: nuova tabella `ftp_config`
Singola riga (singleton). Schema proposto:
```sql
CREATE TABLE ftp_config (
    id INTEGER NOT NULL PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    host TEXT NOT NULL,
    port INTEGER NOT NULL,
    username TEXT NOT NULL,
    encryptedPassword BLOB NOT NULL,  -- cifrata con CryptoManager.encryptBytes(DEK, ...)
    rootPath TEXT NOT NULL DEFAULT '/',
    updatedAt INTEGER NOT NULL
);
```
**Nota credenziali**: la password è cifrata con la DEK biometric-gated. **Conseguenza**: la config FTP è leggibile solo dopo l'autenticazione biometrica all'avvio. Va bene perché la sync FTP avviene comunque dopo lo sblocco. Alternativa scartata: `EncryptedSharedPreferences` introduce una nuova dipendenza (`androidx.security.crypto`) e una seconda KEK separata da gestire.

### B.3 — `Track` entity
Aggiunta:
```kotlin
val sourceFtpPath: String? = null
```
Nessun rename/cambio di tipo su campi esistenti → non breaking per Room.

### B.4 — `CryptoManager`: nuovi metodi pubblici
```kotlin
fun encryptBytes(dek: SecretKey, plaintext: ByteArray): ByteArray  // [iv | ciphertext+tag]
fun decryptBytes(dek: SecretKey, encrypted: ByteArray): ByteArray
```
Stesso formato `[IV (12 byte)] [data + GCM tag]` già usato in `encryptFile` (vedi righe 118–136 di `CryptoManager.kt`). Riuso massimo, zero nuova superficie cripto.

### B.5 — `ImportTrackUseCase`: pipeline parametrica
Oggi `invoke(uri: Uri, dek: SecretKey)` (riga 35) fa: copia URI→temp + estrai metadati + cifra + DB. Per FTP il file è già su disco locale (scaricato da `FtpDownloader`). Servono due strade:

- **Opzione A** (consigliata): nuovo overload `invoke(localFile: File, dek: SecretKey, sourceFtpPath: String)` che salta `audioFileCopier.copyToTemp` e usa direttamente il file ricevuto (poi lo cancella). La logica metadata+encrypt+DB resta una sola, da estrarre in una `private suspend fun importLocalFile(...)`.
- **Opzione B**: trasformare il `File` scaricato in `Uri` con `Uri.fromFile(...)` e chiamare l'overload esistente. Funziona ma comporta una doppia copia (cache → temp → encrypted), spreco di I/O su file potenzialmente grandi.

### B.6 — Nuovo tipo `SyncProgress`
```kotlin
sealed interface SyncProgress {
    object Idle : SyncProgress
    object Connecting : SyncProgress
    object Scanning : SyncProgress
    data class Importing(
        val current: Int, val total: Int,
        val currentFileName: String,
        val added: Int, val skipped: Int, val errors: Int
    ) : SyncProgress
    data class Done(val added: Int, val skipped: Int, val errors: Int) : SyncProgress
    data class Failed(val message: String) : SyncProgress
}
```

### B.7 — Permessi manifest
Nuovo permesso normale (no runtime prompt):
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## C. Pattern da rispettare (estratti dalla codebase)

1. **Hilt + `@Singleton` su domain/data**: tutti gli use case e i repository sono `@Singleton @Inject constructor(...)`. Esempi: `ImportTrackUseCase` (riga 14), `CryptoManager` (riga 26), `TrackRepository` (riga 9). Applicare lo stesso a `SyncFromFtpUseCase`, `FtpScanner`, `FtpDownloader`, `FtpConfigRepository`.

2. **ViewModel = `@HiltViewModel`**: vedi `TrackListViewModel:38`. UI state via `StateFlow` (es. `tracks: StateFlow<List<Track>>` riga 45 con `stateIn(viewModelScope, WhileSubscribed(5_000), emptyList())`). Eventi one-shot via `Channel<...>(Channel.BUFFERED)` esposto come `receiveAsFlow()` (righe 53–54). **Replicare identico** in `FtpConfigViewModel` e `FtpSyncViewModel`.

3. **I/O in `withContext(Dispatchers.IO)`**: vedi `ImportTrackUseCase:35`, `AudioFileCopier:22`, `OrphanCleanupUseCase:24`. Tutto il codice FTP (connessione, listing, download) deve girare in `Dispatchers.IO`.

4. **Cleanup `try/finally`**: pattern `tempFile.delete()` in `finally` come in `ImportTrackUseCase:65–67`. Da replicare per i file scaricati da FTP.

5. **Naming italiano nei commenti e nei messaggi UI**: vedi commenti in `ImportTrackUseCase`, `CryptoManager`, `OrphanCleanupUseCase` e stringhe Compose in italiano (`"La mia Libreria"`, `"Importa MP3"`, `"Autenticazione richiesta"`). **Mantenere coerenza** in `FtpConfigScreen` / `FtpSyncScreen`.

6. **Nessun comment "what"**: il codice esistente usa commenti solo per spiegare il "perché" (es. envelope encryption in `CryptoManager:18–25`). Rispettare la convenzione anche nei nuovi file.

7. **Navigation tramite `Screen` sealed class**: vedi `PlayerNavGraph.kt:17–28`. Aggiungere `object FtpConfig : Screen("ftp_config")` e `object FtpSync : Screen("ftp_sync")`.

8. **Drawer entries** in `PlayerApp.kt:33–56` come riferimento per UI/UX delle nuove voci.

9. **Migrazione Room come `object : Migration(from, to)`**: vedi `MIGRATION_1_2`/`2_3`/`3_4` in `PlayerDatabase.kt:26–62`. Replicare lo stile.

10. **Nessun test suite**: `CLAUDE.md` lo conferma e `find` non ha trovato directory `test/` o `androidTest/`. Non servono modifiche di setup test, ma la sez. D copre comunque quali test sarebbero opportuni.

---

## D. Test da creare o aggiornare

> ⚠️ **Stato attuale**: il progetto **non ha alcuna suite di test configurata** (né JUnit né Android instrumented). Questo lavoro può procedere senza test automatici, ma se decidiamo di avviare la prima suite, ecco dove ha senso piantare i primi paletti.

| Tipo | Cosa testare | Note |
|---|---|---|
| Unit (puro) | `FtpScanner` con un fake `FTPClient` (Apache Commons Net espone interfacce mockabili) — verifica walk ricorsivo, filtro `.mp3` case-insensitive, gestione directory vuote | Setup minimo: aggiungere `testImplementation("junit:junit:4.13.2")` e `mockito-kotlin` |
| Unit (puro) | `CryptoManager.encryptBytes`/`decryptBytes` — round-trip con DEK in-memory | Non richiede Android Keystore se la DEK è passata come parametro (è già il caso) |
| Unit (puro) | `SyncFromFtpUseCase` con tutte le dipendenze fake — verifica dedup, contatori finali, cancellazione cooperativa | Cuore della feature, vale la pena |
| Integration (Android) | `FtpConfigDao` upsert/get con DB in-memory Room | Standard pattern Room |
| Manuale E2E | Server FTP locale (es. `vsftpd` o container `fauria/vsftpd`) con struttura nidificata di MP3 reali → run completo end-to-end | Critico — non rinviabile, vedi M9 nel piano |

**Aggiornamenti a test esistenti**: nessuno (non esistono).

---

## E. Rischi tecnici aggiornati con evidenze

| # | Rischio (Fase 1) | Aggiornamento dopo analisi codebase |
|---|---|---|
| R1 | FTP in chiaro insicuro | **Confermato.** Nessuna dipendenza di rete esistente, partiamo da zero. Apache Commons Net **supporta nativamente FTPS** (`FTPSClient`). Per **SFTP** servirebbe `jsch` (separato, ~400KB). Decisione richiesta — vedi Domande aperte |
| R2 | Mancanza client FTP nativo Android | **Confermato.** Scelta consigliata: `commons-net 3.10.0` (~1.2MB nell'APK, stabile, supporta FTP+FTPS) |
| R3 | Permessi mancanti | **Confermato dopo lettura `AndroidManifest.xml`**: nessun `INTERNET` né `ACCESS_NETWORK_STATE`. Vanno aggiunti |
| R4 | Operazioni lunghe | **Mitigazione fattibile**: pattern `Dispatchers.IO` già usato ovunque. `viewModelScope` con cancellation cooperativa via `coroutineContext.ensureActive()`. **Attenzione**: una sync di centinaia di file può durare oltre il ciclo di vita di una `Activity` se l'utente cambia schermata o ruota — valutare un `Service` foreground o tenere lo `viewModelScope` fino al completamento |
| R5 | Dedup fragile | **Risolto a livello di design**: usiamo `sourceFtpPath` (path remoto assoluto) come chiave. Stabile finché l'utente non sposta i file su FTP. Se vengono rinominati/spostati, vengono re-importati come duplicati. Hash MD5 sul download eviterebbe i duplicati ma raddoppierebbe il tempo di sync. **Decisione consigliata**: path-based per la v1, hash-based in una v2 |
| R6 | Memoria/storage | **Mitigazione**: scaricare un file alla volta in `cacheDir/ftp_temp/`, cifrare, eliminare temp. **Mai** caricare in RAM — Commons Net espone `retrieveFile(remote, OutputStream)` che streama. Picco massimo = 1 file + 1 file cifrato in scrittura |
| R7 | Interazione biometric gate | **Confermato critico.** [`MainActivity.kt:36`](../../../app/src/main/java/it/agoldoni/player/MainActivity.kt) sblocca la DEK al `LaunchedEffect(Unit)`. Se l'app va in background per >N secondi/minuti, il `KeyPermanentlyInvalidatedException` può scattare. La `sessionDek` di `CryptoManager:47` è in-memory per la durata del **processo**, non dell'Activity. ⇒ Una sync lunga in foreground è OK; in background il SO può uccidere il processo e perdere la DEK. **Mitigazione**: foreground service durante la sync + warning UI all'utente di non chiudere l'app |
| **R8 (nuovo)** | `Track.uri` ambiguo | Il campo `uri` è oggi riusato per il **path locale del file cifrato** (vedi `ImportTrackUseCase:59`: `track.copy(uri = encryptedFile.absolutePath)`). Non c'è spazio per il path FTP originale → conferma necessità di una colonna dedicata `sourceFtpPath` |
| **R9 (nuovo)** | Connessione FTP non riusabile durante la sync | Apache Commons Net `FTPClient` mantiene una socket connessa. Va aperta una volta a inizio sync e chiusa in `finally`, non per file. Loop di download molto lunghi possono trigger del **timeout server-side** — gestire `FTPReply` per re-issue di `NOOP` periodici o riconnessione automatica |

---

## F. Prerequisiti e task bloccanti

Da risolvere **prima** di iniziare l'implementazione:

1. **Decisione protocollo**: solo FTP? FTP+FTPS? SFTP? Impatta direttamente la dipendenza (`commons-net` vs `commons-net + jsch`) e le righe di codice nel `FtpClientFactory`. Dato che la descrizione utente dice esplicitamente "FTP", **proposta**: v1 = FTP semplice + opzionale FTPS implicito/esplicito (sempre con `commons-net`), niente SFTP. Eventuale SFTP rimandato.

2. **Storage credenziali**: confermare l'uso di Room+CryptoManager (proposta corrente) vs `EncryptedSharedPreferences`. La proposta corrente ha il vantaggio di non aggiungere dipendenze, ma lega le credenziali allo sblocco biometrico. Va bene?

3. **Background vs foreground**: la sync deve poter continuare se l'utente lascia l'app? Se sì → serve `Service` foreground con notifica persistente (~mezza giornata extra di lavoro). Se no → schermata "tieni l'app aperta", più semplice.

4. **Gestione `Track.uri` post-feature**: non serve refactor immediato, ma vale la pena annotare nel codice la nuova semantica della colonna (oggi: path encrypted file; domani: path encrypted file + opzionalmente `sourceFtpPath` per provenienza).

5. **Allineamento `CLAUDE.md`**: il file dichiara DB v2; siamo a v4. Vale la pena aggiornarlo prima/dopo questa feature per evitare confusione futura.

Nessun refactoring strutturale è bloccante. La codebase è già ben preparata: Hilt, Clean Architecture e i pattern UI sono pronti per accogliere i nuovi moduli senza riscritture.
