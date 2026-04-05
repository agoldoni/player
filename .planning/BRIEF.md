# MP3 Player - Project Brief

## Vision
App Android per gestire una libreria musicale locale. L'utente importa file MP3 dal dispositivo; l'app legge automaticamente i metadati ID3 e li salva come entità nel database locale. Ogni traccia è identificata da un UUID univoco.

## Core Problem
Gli utenti vogliono una libreria musicale offline, senza dipendenze da cloud o servizi esterni, dove ogni file MP3 è catalogato automaticamente leggendone i metadati.

## Target Users
Utenti Android che gestiscono una collezione musicale locale (file MP3 sul dispositivo).

## Key Features (v1.0)
1. **Import MP3** — file picker per selezionare uno o più file MP3 dal dispositivo
2. **Lettura metadati** — estrazione automatica con `MediaMetadataRetriever`: titolo, artista, album, durata, anno, copertina
3. **Persistenza locale** — salvataggio in Room DB, entità `Track` con UUID primario
4. **Lista tracce** — schermata principale con lista scrollabile delle tracce importate
5. **Dettaglio traccia** — visualizzazione completa dei metadati di una traccia

## Technical Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Database:** Room (SQLite)
- **Metadata:** MediaMetadataRetriever (Android SDK)
- **DI:** Hilt
- **ID:** UUID (java.util.UUID)
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)

## Out of Scope (v1.0)
- Riproduzione audio
- Sincronizzazione cloud
- Modifica manuale dei metadati
- Playlist

## Success Criteria
- Import di un file MP3 → metadati visibili in lista entro 2 secondi
- UUID univoco per ogni traccia nel DB
- App stabile senza crash su import multipli
