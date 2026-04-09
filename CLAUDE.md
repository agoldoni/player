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

## Architecture

Single-module Android app (`it.agoldoni.player`) using Clean Architecture with MVVM, Jetpack Compose UI, Hilt DI, and Room database.

**Layers:**
- **data/** — Room DB (`player_db` v2), `TrackDao` with reactive `Flow` queries, `TrackRepository`
- **domain/** — Use cases: `ImportTrackUseCase` (copy → metadata extraction → encrypt → DB insert), `CryptoManager` (envelope encryption: AndroidKeystore KEK + AES-256-GCM DEK), `OrphanCleanupUseCase` (startup file cleanup), `CsvExportUseCase`
- **ui/** — Compose screens with ViewModels. Navigation: `TrackList` → `TrackDetail/{trackId}`, `Stats`, `AppInfo`. App entry gated by `BiometricGateScreen`
- **di/** — Hilt `DatabaseModule` providing singletons

**Key flows:**
- Biometric auth unlocks DEK on launch via `CryptoManager.prepareBiometricCipher()` / `obtainDek()`
- File import: URI → temp copy → metadata extract → album art save → AES-GCM encrypt to `filesDir/tracks/{id}` → DB insert → temp cleanup
- Playback: decrypt to temp file → `MediaPlayer` → cleanup on completion
- ViewModels use `Channel<Event>` for one-shot UI events

**Tech stack:** Kotlin 1.9.22, AGP 8.2.2, Compose BOM 2024.02.00, Hilt 2.50, Room 2.6.1, Coil 2.5.0, Biometric 1.1.0. Targets SDK 34, min SDK 26, JVM 17.

## Notes

- Code comments and build script messages are in Italian.
- DB migration 1→2 adds `originalFileSize` and `encryptedFileSize` columns.
