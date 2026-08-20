# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./build.sh debug          # Build debug APK
./build.sh release        # Build release APK (requires KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
./build.sh clean          # Clean build artifacts
./gradlew assembleDebug   # Direct Gradle build
```

Unit test (JVM): `./gradlew testDebugUnitTest`. Coprono `PlaybackQueue`, `AesGcmStreams` (cifratura a stream), `TransferCrypto` (handshake ECDH/HKDF/SAS), il manifest di trasferimento e `PlaylistRemapper`; non esistono test strumentati, quindi la logica critica va tenuta fuori dalle API Android per restare testabile su JVM.

## Running on device

Il buildType debug ha `applicationIdSuffix = ".debug"`: il package debug è `it.agoldoni.player.debug` (il release `it.agoldoni.player` è installato separatamente sullo stesso device). Per testare le modifiche lanciare SEMPRE il package debug, altrimenti le modifiche non compaiono e la build sembra stale.

```bash
adb install -r app/build/outputs/apk/debug/player.apk
adb shell am force-stop it.agoldoni.player.debug
adb shell monkey -p it.agoldoni.player.debug -c android.intent.category.LAUNCHER 1
```

- L'avvio è dietro `BiometricGateScreen`: serve l'impronta digitale (interazione fisica sul device).
- `adb shell input tap` può essere negato (`INJECT_EVENTS` SecurityException): in tal caso usare solo `uiautomator dump` + `screencap` per ispezionare la UI, non si possono simulare i tocchi via adb.
- La compilazione incrementale Kotlin a volte resta stale (Gradle dice UP-TO-DATE a torto): in caso di dubbio usare `./gradlew clean assembleDebug`.

## Architecture

Single-module Android app (`it.agoldoni.player`) using Clean Architecture with MVVM, Jetpack Compose UI, Hilt DI, and Room database.

**Layers:**
- **data/** — Room DB (`player_db` v6), `TrackDao`/`PlaylistDao`/`FtpConfigDao` with reactive `Flow` queries, `TrackRepository`/`PlaylistRepository`/`FtpConfigRepository`
- **domain/** — Use cases: `ImportTrackUseCase` (copy → metadata extraction → encrypt → DB insert; accetta `Uri` o `File`; `importTransferred()` salta l'estrazione e usa i metadati ricevuti da un'altra istanza), `CryptoManager` (envelope encryption: AndroidKeystore KEK + AES-256-GCM DEK; helpers `encryptBytes`/`decryptBytes` per credenziali, `encryptStream`/`decryptStream`/`decryptingStream` con chiave arbitraria), `AesGcmStreams` (primitive AES-GCM pure, senza Android: è la parte testabile su JVM di `CryptoManager`), `OrphanCleanupUseCase` (startup file cleanup: `import_temp/`, `ftp_temp/`, `upload_temp/`, `transfer_temp/`), `VerifyLibraryUseCase` (verifica integrità: decifra ogni brano e controlla il tag GCM, emette `Flow<VerifyProgress>`; la UI è una card in Statistiche), `CsvExportUseCase`, `PlaybackManager` (façade su `MediaController` per i ViewModel)
- **domain/playback/** — `PlaybackService` (Media3 `MediaSessionService` + ExoPlayer + MediaSession: riproduzione in background e controlli su lock screen/notifica), `PlaybackQueue` (singleton condiviso: ordine/indice/shuffle, sorgente di verità della coda, usato sia dai ViewModel sia dal service)
- **domain/ftp/** — `FtpClientFactory`, `FtpScanner` (walk ricorsivo), `FtpDownloader` (stream-based), `SyncFromFtpUseCase` (orchestratore, emette `Flow<SyncProgress>`)
- **domain/upload/** — `UploadServer` (server Ktor CIO sulla LAN per ricevere brani da un browser su PC, capability URL con token)
- **domain/transfer/** — trasferimento libreria fra due istanze dell'app: `TransferProtocol` (rotte, manifest, `PROTOCOL_VERSION`), `TransferCrypto` (ECDH secp256r1 + HKDF-SHA256 + codice SAS a 6 cifre), `TransferServer` (lato mittente, Ktor CIO), `TransferClient` (lato destinatario, Ktor client CIO), `ReceiveLibraryUseCase` (orchestratore, emette `Flow<TransferProgress>`), `TransferSelectionResolver` (cosa inviare → manifest), `PlaylistRemapper` (logica pura di ricostruzione playlist), `PeerDiscovery` (mDNS via `NsdManager`)
- **ui/** — Compose screens with ViewModels. Navigation: `TrackList` → `TrackDetail/{trackId}`, `PlaylistList`, `PlaylistDetail`, `AuthorList`, `AuthorDetail`, `Stats`, `AppInfo`, `FtpConfig`, `FtpSync`, `WifiUpload`, `SendLibrary`, `ReceiveLibrary`. App entry gated by `BiometricGateScreen`
- **di/** — Hilt `DatabaseModule` providing singletons

**Key flows:**
- Biometric auth unlocks DEK on launch via `CryptoManager.prepareBiometricCipher()` / `obtainDek()`
- File import: URI → temp copy → metadata extract → album art save → AES-GCM encrypt to `filesDir/tracks/{id}` → DB insert → temp cleanup
- FTP sync: leggi config cifrata → connetti FTP → walk ricorsivo → download in `cacheDir/ftp_temp/` → estrai metadati → dedup per `(title, artist, album)` → `ImportTrackUseCase(File)` o skip
- Playback (Media3): ViewModel imposta `PlaybackQueue` → `PlaybackManager` invia comando custom `PLAY_CURRENT` al `PlaybackService` via `MediaController` → il service decifra il brano corrente in temp (`decryptToTempFile`), lo imposta come `MediaItem` su ExoPlayer e suona; avanzamento (fine brano / "successivo" da notifica) gestito dal service leggendo `PlaybackQueue`; cleanup del temp precedente al cambio brano. Il pulsante "successivo" su lock screen è abilitato via `ForwardingPlayer` che espone `COMMAND_SEEK_TO_NEXT`. Se la DEK non è sbloccata (es. processo riavviato), `playQueue()`/`playSingle()` ritornano false → evento "Sessione scaduta".
- Trasferimento fra istanze (LAN): il mittente risolve la selezione in un manifest, apre `TransferServer` su una porta di `8091..8100`, si annuncia via mDNS e mostra indirizzo + codice di accesso; il destinatario lo trova (o lo inserisce a mano), fa l'handshake ECDH e mostra il codice a 6 cifre derivato dal transcript. Confermato il codice su entrambi i telefoni, scarica manifest → brani → copertine, tutti cifrati con la chiave di sessione: il mittente decifra con la propria DEK e ricifra al volo, il destinatario decifra in `cacheDir/transfer_temp/` e reimporta con `importTransferred()`. Dedup per `(title, artist, album)`; gli ID vengono rimappati (`idOrigine → idLocale`, i brani già presenti puntano a quello locale) e le playlist ricostruite o fuse per nome
- ViewModels use `Channel<Event>` for one-shot UI events

**Tech stack:** Kotlin 1.9.22, AGP 8.2.2, Compose BOM 2024.02.00, Hilt 2.50, Room 2.6.1, Coil 2.5.0, Biometric 1.1.0, Media3 1.3.1 (ExoPlayer + Session), Apache Commons Net 3.10.0, Ktor 2.3.12 (server CIO + client CIO), kotlinx.serialization JSON 1.6.2. Targets SDK 34, min SDK 26, JVM 17.

**Permissions:** `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` (storage), `INTERNET` + `ACCESS_NETWORK_STATE` (FTP sync, upload Wi-Fi, trasferimento fra istanze), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `POST_NOTIFICATIONS` (riproduzione background + notifica media), `WAKE_LOCK` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (riproduzione a schermo bloccato).

`res/xml/network_security_config.xml` consente il traffico HTTP in chiaro: serve al lato client del trasferimento, che parla con un IP privato non elencabile in una network security config. I payload sono comunque cifrati a livello applicativo.

## Notes

- Code comments and build script messages are in Italian.
- DB migrations: `1→2` aggiunge `originalFileSize`/`encryptedFileSize`; `2→3` crea `playlists` + `playlist_track_cross_ref`; `3→4` aggiunge `playlists.lastPlayedTrackId`; `4→5` crea `ftp_config`; `5→6` aggiunge `tracks.originalExtension`.
- FTP sync supporta solo FTP plain (no FTPS/SFTP) per la v1; le credenziali sono cifrate a riposo con la DEK biometric-gated; la sync funziona solo con app in primo piano (nessun foreground service); dedup basata su metadati ID3 `(title, artist, album)`.
- Trasferimento fra istanze: solo LAN, app in primo piano su entrambi i telefoni (nessun foreground service, coerente con FTP e upload Wi-Fi); DEK e KEK non lasciano mai il device — si migra il contenuto, non l'identità crittografica; la config FTP non viene trasferita. Il codice a 6 cifre è una *short authentication string* derivata dal transcript ECDH, non la sorgente della chiave: confrontarlo a vista è ciò che blocca l'uomo in mezzo. Documentazione della feature in `docs/features/001-trasferimento-sicuro-tra-istanze/`.
- **Trappola di prestazioni con AES-GCM su Android**: il provider AEAD accumula l'input fino a `doFinal`, quindi il costo cresce col **numero** di chiamate a `update`, non solo con i byte. `CipherInputStream` legge a blocchi di 512 byte e su un file da 7 MB genera ~14.000 update → comportamento quadratico (~1,9 MB/s misurati). In `AesGcmStreams` le letture sono perciò accorpate in blocchi da 64 KB (`fill()`) prima di ogni `update`, e il trasferimento usa `transcodeTo` invece di `CipherInputStream`: stesso file, ~60-95 MB/s. Vale per qualsiasi nuovo codice che cifri a stream, incluse le letture dalla rete (una `read` su socket può restituire pochi KB).
