# MP3 Player - Roadmap

## Milestone: v1.0 — Libreria MP3 Locale

### Phase 01 — Foundation (Progetto Android + Room DB)
Setup del progetto Android con Hilt, Room, e l'entità `Track`.

| Plan | Description | Status |
|------|-------------|--------|
| 01-01 | Scaffold progetto Android (Kotlin, Compose, Hilt, Room) | ⬜ TODO |
| 01-02 | Entità Room `Track` + DAO + Database | ⬜ TODO |
| 01-03 | Repository pattern + Hilt modules | ⬜ TODO |

### Phase 02 — Metadata Import (File Picker + Lettura ID3)
Import di file MP3 e lettura metadati con `MediaMetadataRetriever`.

| Plan | Description | Status |
|------|-------------|--------|
| 02-01 | File picker (ActivityResultContracts) + permessi storage | ⬜ TODO |
| 02-02 | MetadataExtractor service con MediaMetadataRetriever | ⬜ TODO |
| 02-03 | Salvataggio copertina album (bitmap → file locale) | ⬜ TODO |

### Phase 03 — UI (Compose Screens)
Schermate Jetpack Compose: lista tracce e dettaglio.

| Plan | Description | Status |
|------|-------------|--------|
| 03-01 | TrackListScreen con LazyColumn + ViewModel | ⬜ TODO |
| 03-02 | TrackDetailScreen con metadati completi e copertina | ⬜ TODO |
| 03-03 | Navigation (NavHost) + FAB per import | ⬜ TODO |

---

## Status Legend
- ⬜ TODO
- 🔄 IN PROGRESS
- ✅ DONE
- ⏸ BLOCKED
