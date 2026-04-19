# Feature: `vista-autori` — Piano (Fase 1)

**Stato:** in attesa di approvazione per Fase 2
**Data:** 2026-04-18
**Progetto:** player (Android, `it.agoldoni.player`)

---

## 1. Obiettivo e motivazione

Permettere all'utente di accedere rapidamente a tutte le tracce di un singolo artista/gruppo come se fosse una playlist virtuale, senza dover creare manualmente una playlist dedicata né cercare manualmente nella lista globale.

**Problema risolto:** oggi per ascoltare "tutto di un artista" l'utente deve scorrere/filtrare la `TrackList` o creare a mano una playlist, operazione ripetitiva e fragile (le nuove tracce importate non vengono aggiunte).

**Valore:** navigazione rapida per artista, esperienza tipo "library by artist" comune nei player musicali, sempre aggiornata automaticamente.

---

## 2. Scope

### Incluso
- Nuova schermata **`AuthorList`** con elenco di tutti gli artisti distinti (campo `artist` di `Track`), ordinati alfabeticamente, con conteggio tracce per artista.
- Nuova schermata **`AuthorDetail/{artistName}`** con la lista delle tracce dell'artista selezionato.
- Azioni in `AuthorDetail`: **Play sequenziale** e **Random play**, con stesso engine di playback usato dalle playlist.
- Voce di navigazione "Autori" accessibile dal menu/entry point principale (al pari di `PlaylistList`).
- Aggiornamento reattivo via `Flow` (nuove tracce importate compaiono automaticamente).

### Escluso (out of scope)
- Modifica/rimozione tracce dalla vista autore (è read-only by design).
- Persistenza della vista come playlist materializzata in DB.
- Gestione "artista vuoto/sconosciuto" come categoria speciale (eventuale TBD in Fase 2).
- Album view / raggruppamento per album dentro l'autore.
- Artwork dell'artista (si userà solo l'artwork delle singole tracce).
- Ricerca/filtro testuale dentro la lista autori (rimandato a v1.1).
- Merge di varianti dello stesso artista (es. "The Beatles" vs "Beatles").

---

## 3. User Stories

1. **Come utente** voglio **vedere l'elenco di tutti gli artisti presenti nella mia libreria** per **navigare rapidamente la collezione per autore**.
2. **Come utente** voglio **toccare un artista e vedere tutte le sue tracce** per **scegliere cosa ascoltare senza cercare nella lista globale**.
3. **Come utente** voglio **avviare la riproduzione (sequenziale o random) di tutte le tracce di un artista con un tap** per **ascoltare "tutto di X" senza dover creare una playlist dedicata**.
4. **Come utente** voglio **che le nuove tracce importate compaiano automaticamente sotto il loro artista** per **non dover mantenere manualmente la vista**.

---

## 4. Criteri di accettazione

**US1 — Lista autori**
- [ ] La schermata mostra tutti gli artisti distinti presenti in `tracks`.
- [ ] Ogni riga mostra il nome dell'artista e il numero di tracce.
- [ ] Ordinamento alfabetico case-insensitive.
- [ ] La lista si aggiorna in tempo reale all'import di nuove tracce.
- [ ] Tracce con `artist` nullo/vuoto sono raggruppate sotto un'etichetta dedicata (es. "Sconosciuto") oppure escluse — decisione in Fase 2.

**US2 — Dettaglio autore**
- [ ] La schermata mostra le tracce dell'artista selezionato, ordinate per titolo (default).
- [ ] Header con nome artista e conteggio tracce.
- [ ] Tap su una traccia avvia la riproduzione di quella traccia (coerente con `TrackList`).
- [ ] Nessuna azione di delete/edit disponibile (UI read-only).

**US3 — Play / Random play**
- [ ] Pulsante "Play" avvia la riproduzione sequenziale di tutte le tracce dell'artista nell'ordine visualizzato.
- [ ] Pulsante "Random" avvia la riproduzione in ordine casuale.
- [ ] Il comportamento di playback (decrypt → MediaPlayer → cleanup) è identico a quello delle playlist.

**US4 — Aggiornamento reattivo**
- [ ] Importando una nuova traccia con artista esistente, il conteggio nella lista autori si incrementa senza riavvio.
- [ ] Importando una nuova traccia con artista nuovo, l'artista compare in lista senza riavvio.

---

## 5. Rischi e dipendenze

**Rischi tecnici**
- **Normalizzazione artista**: stringhe duplicate per varianti ("Beatles" / "the beatles" / "The Beatles ") possono frammentare la lista. Mitigazione iniziale: trim + confronto case-insensitive solo a livello di query, niente migrazione DB.
- **Tracce con `artist` null/vuoto**: scelta UX da definire (gruppo "Sconosciuto" vs esclusione).
- **Engine di playback playlist riutilizzabile**: serve verificare in Fase 2 se l'attuale `PlaylistDetailViewModel`/playback layer espone API riusabili o se il random/sequenziale è accoppiato a `Playlist` come entità.
- **Performance lista autori** su libreria grande: una `GROUP BY artist` su Room è O(n) ma con indice assente potrebbe essere lenta oltre qualche migliaio di tracce — verificare schema in Fase 2.

**Dipendenze**
- Nessuna dipendenza esterna nuova.
- Dipende dallo schema attuale di `Track` (campo `artist`) — da verificare in Fase 2.
- Dipende dal layer di playback esistente — da mappare in Fase 2.

---

## 6. Stima effort

Stima preliminare (singolo dev, da raffinare dopo analisi codebase in Fase 2):

| Area | Effort |
|---|---|
| Data layer (DAO query distinct + by-artist) | 0.5 gg |
| Domain (eventuali use case wrapper) | 0.5 gg |
| UI Compose (`AuthorListScreen` + `AuthorDetailScreen` + ViewModel) | 1.5 gg |
| Navigation + entry point | 0.25 gg |
| Refactor/estrazione layer playback condiviso (se necessario) | 0.5–1 gg ⚠️ dipende da Fase 2 |
| Test manuali + edge case (artista vuoto, libreria vuota) | 0.25 gg |
| **Totale** | **~3–4 gg/uomo** |

Niente FE separato (single-module Android). Niente test suite automatica configurata nel progetto (cfr. `CLAUDE.md`), quindi i test sono manuali.

---

## 7. Milestones

1. **M1 — Data layer**: aggiungere a `TrackDao` query `getDistinctArtistsWithCount(): Flow<List<ArtistSummary>>` e `getTracksByArtist(name): Flow<List<Track>>`.
2. **M2 — Domain (se serve)**: eventuale `GetArtistsUseCase` / `GetTracksByArtistUseCase`, o uso diretto del repository.
3. **M3 — Playback condiviso**: verificare/estrarre da `PlaylistDetailViewModel` un componente di playback riusabile (sequenziale + shuffle) parametrizzato su `List<Track>` invece che su `Playlist`.
4. **M4 — UI lista autori**: `AuthorListScreen` + `AuthorListViewModel` + entry di navigazione.
5. **M5 — UI dettaglio autore**: `AuthorDetailScreen` + `AuthorDetailViewModel` con play/random e tap-on-track.
6. **M6 — Navigazione**: aggiungere route `AuthorList` e `AuthorDetail/{artistName}` al graph, aggiungere voce nel menu principale.
7. **M7 — Edge case & polish**: gestione artista vuoto/null, libreria vuota, conteggio aggiornato in real time.
8. **M8 — Verifica manuale** end-to-end e bump versione.
