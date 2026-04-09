# Feature: Playlist — Piano (Fase 1)

## 1. Obiettivo e motivazione

Permettere all'utente di organizzare le tracce importate in playlist personalizzate con nome. Attualmente tutte le tracce sono mostrate in un'unica lista piatta — le playlist aggiungono un livello di organizzazione che migliora l'esperienza d'uso man mano che la libreria cresce.

## 2. Scope

### Incluso
- Creazione, rinomina e cancellazione di playlist
- Aggiunta e rimozione di tracce esistenti da una playlist
- Una stessa traccia può appartenere a più playlist
- Visualizzazione lista playlist nella schermata principale (toggle tramite menu superiore)
- Visualizzazione contenuto di una singola playlist (lista tracce)
- Navigazione da playlist → dettaglio traccia (riuso `TrackDetailScreen`)

### Escluso (out of scope)
- Ordinamento manuale delle tracce dentro una playlist (drag & drop)
- Import diretto di tracce dentro una playlist
- Copertina personalizzata per la playlist
- Condivisione/export di playlist

## 3. User Stories

1. **Come** utente **voglio** creare una nuova playlist con un nome a mia scelta **per** raggruppare le tracce per genere, mood o occasione.
2. **Come** utente **voglio** aggiungere tracce dalla libreria a una playlist **per** comporre la mia selezione personalizzata.
3. **Come** utente **voglio** passare dalla vista "Tracce" alla vista "Playlist" tramite un menu in alto **per** navigare velocemente tra le due modalità.
4. **Come** utente **voglio** aprire una playlist e vederne il contenuto **per** consultare e accedere alle tracce che ho raggruppato.
5. **Come** utente **voglio** rimuovere tracce da una playlist **per** tenere aggiornate le mie selezioni.
6. **Come** utente **voglio** rinominare o cancellare una playlist **per** gestire le mie liste nel tempo.
7. **Come** utente **voglio** premere un pulsante play nella playlist **per** riprodurre i brani in sequenza a partire dall'ultimo brano ascoltato.

## 4. Criteri di accettazione

- [ ] L'utente può creare una playlist inserendo un nome (non vuoto, non duplicato)
- [ ] L'utente può aggiungere una o più tracce a una playlist dalla libreria
- [ ] L'utente può rimuovere tracce da una playlist senza eliminare la traccia dalla libreria
- [ ] L'utente può rinominare una playlist esistente
- [ ] L'utente può cancellare una playlist (le tracce in libreria restano intatte)
- [ ] Nella `TrackListScreen` un selettore in alto permette di alternare tra "Tracce" e "Playlist"
- [ ] La vista playlist mostra nome e numero di tracce per ogni playlist
- [ ] Toccando una playlist si apre la lista delle sue tracce
- [ ] Dalla lista tracce di una playlist si può navigare al `TrackDetailScreen`
- [ ] La cancellazione di una traccia dalla libreria la rimuove automaticamente da tutte le playlist
- [ ] Nella schermata dettaglio playlist è presente un pulsante play
- [ ] Il play avvia la riproduzione sequenziale delle tracce della playlist
- [ ] La playlist ricorda l'ultimo brano riprodotto (persistito in DB)
- [ ] Ogni successivo play riparte dal brano memorizzato
- [ ] Al termine dell'ultima traccia la posizione si resetta alla prima traccia

## 5. Rischi e dipendenze

| Rischio | Impatto | Mitigazione |
|---|---|---|
| Migrazione DB (v2 → v3) con nuove tabelle | Perdita dati se mal gestita | Room migration esplicita con test |
| Complessità UI nel toggle Tracce/Playlist | UX confusa | Usare `TabRow` Material3, pattern standard |
| Relazione many-to-many Track ↔ Playlist | Query più complesse | Junction table con indici |
| Riproduzione sequenziale con cifratura | Latenza tra brani per decifratura | Decifrare il brano successivo in anticipo non è necessario per v1 |

## 6. Stima effort

| Area | Giorni/uomo |
|---|---|
| Data layer (entity, DAO, migration, repository) | 1 |
| Domain layer (use case CRUD playlist) | 0.5 |
| UI — Lista playlist + toggle | 1.5 |
| UI — Contenuto playlist + aggiunta/rimozione tracce | 1 |
| Riproduzione sequenziale playlist + persistenza posizione | 1 |
| Test manuali e fix | 0.5 |
| **Totale** | **5.5** |

## 7. Milestones

1. **DB & Data layer** — Entity `Playlist`, junction table `PlaylistTrackCrossRef`, DAO, migration v2→v3, repository
2. **Domain layer** — Use case per CRUD playlist e gestione tracce nella playlist
3. **UI — Vista playlist** — `PlaylistListScreen` + `PlaylistListViewModel`, toggle Tracce/Playlist nella toolbar
4. **UI — Contenuto playlist** — Schermata dettaglio playlist (lista tracce), dialoghi per aggiunta tracce
5. **UI — Gestione** — Dialoghi per creazione, rinomina, cancellazione playlist
6. **Navigazione** — Aggiornamento `PlayerNavGraph` con nuove rotte
7. **Riproduzione playlist** — Pulsante play, riproduzione sequenziale, campo `lastPlayedTrackId` in entity Playlist, migrazione DB v3→v4, persistenza posizione
8. **Pulizia e test** — Verifica coerenza dati, cascade delete, edge case
