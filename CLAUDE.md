# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./build.sh debug          # Build debug APK
./build.sh release        # Build release APK (requires KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
./build.sh clean          # Clean build artifacts
./gradlew assembleDebug   # Direct Gradle build
```

No test suite is currently configured.

## Running on device

Il buildType debug ha `applicationIdSuffix = ".debug"`: il package debug è `it.agoldoni.player.debug` (il release `it.agoldoni.player` è installato separatamente sullo stesso device). Per testare le modifiche lanciare SEMPRE il package debug, altrimenti le modifiche non compaiono e la build sembra stale.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop it.agoldoni.player.debug
adb shell monkey -p it.agoldoni.player.debug -c android.intent.category.LAUNCHER 1
```

- L'avvio è dietro `BiometricGateScreen`: serve l'impronta digitale (interazione fisica sul device).
- `adb shell input tap` può essere negato (`INJECT_EVENTS` SecurityException): in tal caso usare solo `uiautomator dump` + `screencap` per ispezionare la UI, non si possono simulare i tocchi via adb.
- La compilazione incrementale Kotlin a volte resta stale (Gradle dice UP-TO-DATE a torto): in caso di dubbio usare `./gradlew clean assembleDebug`.

## Architecture

Single-module Android app (`it.agoldoni.player`) using Clean Architecture with MVVM, Jetpack Compose UI, Hilt DI, and Room database.

**Layers:**
- **data/** — Room DB (`player_db` v5), `TrackDao`/`PlaylistDao`/`FtpConfigDao` with reactive `Flow` queries, `TrackRepository`/`PlaylistRepository`/`FtpConfigRepository`
- **domain/** — Use cases: `ImportTrackUseCase` (copy → metadata extraction → encrypt → DB insert; accetta `Uri` o `File`), `CryptoManager` (envelope encryption: AndroidKeystore KEK + AES-256-GCM DEK; helpers `encryptBytes`/`decryptBytes` per credenziali), `OrphanCleanupUseCase` (startup file cleanup, include `ftp_temp/`), `CsvExportUseCase`
- **domain/ftp/** — `FtpClientFactory`, `FtpScanner` (walk ricorsivo), `FtpDownloader` (stream-based), `SyncFromFtpUseCase` (orchestratore, emette `Flow<SyncProgress>`)
- **ui/** — Compose screens with ViewModels. Navigation: `TrackList` → `TrackDetail/{trackId}`, `PlaylistList`, `PlaylistDetail`, `Stats`, `AppInfo`, `FtpConfig`, `FtpSync`. App entry gated by `BiometricGateScreen`
- **di/** — Hilt `DatabaseModule` providing singletons

**Key flows:**
- Biometric auth unlocks DEK on launch via `CryptoManager.prepareBiometricCipher()` / `obtainDek()`
- File import: URI → temp copy → metadata extract → album art save → AES-GCM encrypt to `filesDir/tracks/{id}` → DB insert → temp cleanup
- FTP sync: leggi config cifrata → connetti FTP → walk ricorsivo → download in `cacheDir/ftp_temp/` → estrai metadati → dedup per `(title, artist, album)` → `ImportTrackUseCase(File)` o skip
- Playback: decrypt to temp file → `MediaPlayer` → cleanup on completion
- ViewModels use `Channel<Event>` for one-shot UI events

**Tech stack:** Kotlin 1.9.22, AGP 8.2.2, Compose BOM 2024.02.00, Hilt 2.50, Room 2.6.1, Coil 2.5.0, Biometric 1.1.0, Apache Commons Net 3.10.0. Targets SDK 34, min SDK 26, JVM 17.

**Permissions:** `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` (storage), `INTERNET` + `ACCESS_NETWORK_STATE` (FTP sync).

## Notes

- Code comments and build script messages are in Italian.
- DB migrations: `1→2` aggiunge `originalFileSize`/`encryptedFileSize`; `2→3` crea `playlists` + `playlist_track_cross_ref`; `3→4` aggiunge `playlists.lastPlayedTrackId`; `4→5` crea `ftp_config`.
- FTP sync supporta solo FTP plain (no FTPS/SFTP) per la v1; le credenziali sono cifrate a riposo con la DEK biometric-gated; la sync funziona solo con app in primo piano (nessun foreground service); dedup basata su metadati ID3 `(title, artist, album)`.
