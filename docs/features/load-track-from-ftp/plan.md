# Piano feature — `load-track-from-ftp`

> Documento generato in **Fase 1** dello skill `claude-code-feature-skill`.
> Stato: **bozza in revisione**, da confermare prima di procedere con la Fase 2 (analisi tecnica della codebase).

## 1. Obiettivo e motivazione

Permettere all'utente di popolare la libreria locale dell'app importando automaticamente tutti gli MP3 presenti su un server FTP personale, senza doverli trasferire manualmente sul dispositivo. Risolve il problema dell'onboarding iniziale e dell'aggiornamento incrementale della libreria quando l'utente aggiunge nuovi brani al proprio archivio remoto.

## 2. Scope

### Incluso
- Schermata di configurazione dell'endpoint FTP (host, porta, username, password, path radice opzionale)
- Persistenza sicura delle credenziali FTP (riusando `CryptoManager` / AndroidKeystore)
- Pulsante "Sincronizza da FTP" che avvia la connessione e la scansione ricorsiva
- Walk ricorsivo di tutte le directory a partire dalla root configurata
- Filtro file con estensione `.mp3` (case-insensitive)
- Deduplicazione: skip dei file già presenti nella libreria locale
- Download dei nuovi file in area temporanea, poi passaggio al flusso `ImportTrackUseCase` esistente (estrazione metadati → cifratura AES-GCM → inserimento DB)
- Feedback UI durante la sincronizzazione: progresso (file N di M), elenco aggiunti/saltati/errori
- Gestione errori di rete e di autenticazione con messaggi comprensibili

### Escluso (out of scope)
- Sincronizzazione bidirezionale (upload verso FTP)
- Cancellazione locale di tracce non più presenti su FTP
- Sincronizzazione automatica in background o schedulata
- Supporto a più endpoint FTP contemporanei
- Supporto a formati diversi da MP3
- Risoluzione conflitti su file con stesso nome ma contenuto diverso (oltre il semplice skip per identificatore)
- Streaming diretto da FTP senza download

## 3. User Stories

1. **Come utente** voglio configurare host, credenziali e path radice del mio server FTP **per** non doverli reinserire ad ogni sincronizzazione.
2. **Come utente** voglio premere un bottone "Sincronizza" e vedere l'app scaricare automaticamente tutti gli MP3 nuovi **per** popolare la libreria senza intervento manuale.
3. **Come utente** voglio vedere in tempo reale il progresso (quanti file trovati, quanti scaricati, quanti saltati perché già presenti) **per** sapere cosa sta succedendo durante operazioni lunghe.
4. **Come utente** voglio che le mie credenziali FTP siano cifrate sul dispositivo **per** non esporle in caso di accesso non autorizzato al telefono.
5. **Come utente** voglio poter interrompere una sincronizzazione in corso **per** non bloccare l'app se la connessione è lenta o se cambio idea.

## 4. Criteri di accettazione

**US1 — Configurazione endpoint**
- [ ] Esiste una schermata accessibile dal menu principale per inserire host, porta (default 21), username, password, path radice (default `/`)
- [ ] I campi sono validati: host non vuoto, porta numerica 1–65535
- [ ] Le credenziali vengono salvate cifrate (non in chiaro in `SharedPreferences`/Room)
- [ ] È possibile testare la connessione con un pulsante "Test connessione" che restituisce esito immediato

**US2 — Sincronizzazione**
- [ ] Un pulsante "Sincronizza da FTP" è visibile nella schermata principale o nelle impostazioni
- [ ] Premendo il pulsante l'app si connette, percorre ricorsivamente tutte le directory a partire dalla root e raccoglie i path di tutti i `.mp3`
- [ ] Per ogni file non presente in libreria, scarica → estrae metadati → cifra → inserisce nel DB usando `ImportTrackUseCase`
- [ ] I file già presenti vengono saltati senza ridownload
- [ ] A fine sync compare un riepilogo: "X aggiunti, Y saltati, Z errori"

**US3 — Feedback progresso**
- [ ] Durante la sync è visibile un indicatore di progresso (testo e/o barra) con il file corrente
- [ ] L'utente non può chiudere accidentalmente la schermata senza conferma

**US4 — Sicurezza credenziali**
- [ ] Le credenziali sono cifrate a riposo con `CryptoManager`
- [ ] La password non è mai loggata né mostrata in chiaro nell'UI dopo il salvataggio

**US5 — Cancellazione**
- [ ] Esiste un pulsante "Annulla" durante la sync
- [ ] L'annullamento interrompe il loop in modo pulito; le tracce già importate restano in libreria

## 5. Rischi e dipendenze

**Rischi tecnici**
- **FTP semplice è insicuro**: le credenziali e i dati passano in chiaro. Valutare se richiedere FTPS o SFTP. *La descrizione utente dice "ftp" — da chiarire se accettabile.*
- **Librerie FTP su Android**: non c'è un client FTP nativo. Servirà una dipendenza esterna (Apache Commons Net è la più comune). Aggiunge ~1 MB all'APK e potrebbe avere problemi con alcuni server.
- **Permessi di rete**: serve `INTERNET` permission e possibilmente `ACCESS_NETWORK_STATE`. Da verificare se già presenti nel manifest.
- **Operazioni lunghe**: una libreria grande può richiedere minuti/ore. Va eseguita in coroutine su `Dispatchers.IO`, con gestione del lifecycle per non perdere lo stato sul ruoto schermo.
- **Deduplicazione fragile**: confrontare per nome file può portare a falsi positivi. Servirebbe un hash o un identificatore stabile (path remoto?). Da decidere.
- **Memoria/storage**: file scaricati in temp prima della cifratura — picchi di occupazione disco. Cleanup robusto necessario.
- **Interazione con biometric gate**: la sync è una operazione lunga: cosa succede se l'app va in background e il gate biometrico si riattiva? Il DEK potrebbe essere invalidato a metà sync.

**Dipendenze**
- Aggiunta libreria FTP (probabilmente `commons-net`)
- Riuso di `CryptoManager`, `ImportTrackUseCase`, `TrackRepository` esistenti
- Possibile estensione dello schema DB per memorizzare il path FTP originale (utile per dedup futura)

## 6. Stima effort

Assumendo team = 1 sviluppatore part-time:

| Area | Effort |
|---|---|
| **Backend/Domain** (FTP client wrapper, walk ricorsivo, integrazione `ImportTrackUseCase`, persistenza credenziali cifrate) | 2.5 gg |
| **UI/Compose** (schermata configurazione, pulsante e schermata sync con progresso, gestione stati) | 1.5 gg |
| **Test manuali** (server FTP locale di prova, scenari errore: credenziali sbagliate, rete lenta, file corrotti, annullamento) | 1 gg |
| **Documentazione** (aggiornamento `CLAUDE.md`, eventuali note utente) | 0.5 gg |
| **Buffer rischi** (problemi libreria FTP, edge case dedup) | 1 gg |
| **Totale** | **~6.5 gg/uomo** |

## 7. Milestones

1. **M1 — Setup dipendenza FTP**: aggiungere `commons-net` (o alternativa), permessi manifest, smoke test di connessione hardcoded
2. **M2 — Persistenza credenziali**: estensione schema o storage cifrato per host/porta/user/pass, con `CryptoManager`
3. **M3 — UI configurazione**: schermata Compose + ViewModel per inserimento e test connessione
4. **M4 — Walk ricorsivo + raccolta MP3**: funzione che, dato un client connesso, restituisce la lista di tutti i path `.mp3`
5. **M5 — Logica dedup**: definire chiave (path remoto? nome? hash?) ed estendere `TrackDao` se serve
6. **M6 — Pipeline download → import**: per ogni nuovo file, download in temp e chiamata a `ImportTrackUseCase`, con cleanup
7. **M7 — UI sync con progresso**: schermata dedicata con stato reattivo (Flow), pulsante annulla, riepilogo finale
8. **M8 — Gestione errori e edge case**: timeout, credenziali errate, file corrotti, rotazione, background
9. **M9 — Test manuali end-to-end** contro un server FTP reale
10. **M10 — Documentazione e cleanup**
