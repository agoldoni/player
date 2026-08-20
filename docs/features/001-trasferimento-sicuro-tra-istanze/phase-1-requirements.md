# Fase 1 — Requisiti: Trasferimento sicuro della libreria tra due istanze dell'app

> Feature slug: `trasferimento-sicuro-tra-istanze`
> Stato: bozza requisiti — in attesa di conferma per Fase 2

## Decisioni già prese (input utente)

| Ambito | Scelta |
|---|---|
| Scenario | **Device → device**: due telefoni con l'app installata (tipicamente migrazione su telefono nuovo) |
| Trasporto | **HTTP su LAN**, riusando il pattern del `UploadServer` Ktor già presente |
| Contenuto | File audio + **metadati brano/statistiche** + **playlist** (config FTP **esclusa**) |
| Pairing | **PIN numerico** + chiave di sessione derivata, payload cifrato end-to-end |

## 1. Obiettivo e motivazione

Oggi la libreria è **prigioniera del dispositivo**. I file audio sono cifrati con la DEK
(`CryptoManager`), a sua volta wrappata da una KEK non esportabile che vive in AndroidKeystore ed
è vincolata all'autenticazione biometrica. Conseguenze concrete:

- Copiare `filesDir/tracks/` su un altro telefono produce file **indecifrabili**: la KEK non lascia
  il Keystore del device di origine.
- Non esiste alcun backup: reset di fabbrica, telefono perso o cambio device = **perdita totale**
  della libreria, delle playlist e dei metadati di import.
- L'unico modo per ripopolare un'installazione nuova è ripartire dalle sorgenti esterne
  (file picker, sync FTP, upload Wi-Fi da PC), riperdendo playlist e date di import.

L'obiettivo è un canale **istanza → istanza** che trasferisca la libreria (audio + metadati +
playlist) da un telefono all'altro sulla rete locale, mantenendo la stessa postura di sicurezza
del resto dell'app: i dati in chiaro **non transitano mai sulla rete** e sul disco esistono solo
come temporanei di breve durata, esattamente come già avviene per la sync FTP e per l'upload Wi-Fi.

Valore: continuità in caso di cambio telefono, riduzione del rischio di perdita dati, e riuso di
un'infrastruttura (server Ktor + capability URL + pipeline di import) che l'app possiede già.

## 2. Scope

### Incluso

- **Modalità "Invia libreria"** su un device: espone un server HTTP sulla LAN e mostra un PIN.
- **Modalità "Ricevi libreria"** sull'altro device: si collega al mittente, digita il PIN e scarica.
- **Pairing autenticato**: scambio di chiavi effimero + PIN a 6 cifre come verifica, da cui si
  deriva una chiave AES-256-GCM **di sessione** usata per cifrare tutto ciò che passa sul filo.
- **Manifest di trasferimento** (JSON cifrato): elenco brani con metadati completi
  (`title`, `artist`, `album`, `duration`, `year`, `trackNumber`, `originalExtension`,
  `originalFileSize`, `importedAt`) + copertine + playlist e relazioni playlist↔brano.
- **Ricifratura per hop**: il mittente decifra con la **propria** DEK e ricifra con la chiave di
  sessione; il destinatario decifra con la chiave di sessione e ricifra con la **propria** DEK.
- **Dedup lato destinatario** su `(title, artist, album)`, coerente con la sync FTP e l'upload Wi-Fi.
- **Selezione di cosa inviare**: tutta la libreria oppure una o più playlist / un sottoinsieme di brani.
- **Progresso per-file e totale** su entrambi i lati, con esito finale (aggiunti / già presenti / errori).
- **Ripresa di un trasferimento interrotto**: i brani già importati vengono saltati al secondo tentativo
  (garantito dalla dedup, senza stato persistente aggiuntivo).
- Due nuove schermate Compose raggiungibili dal menu, con relativi ViewModel.
- Aggiornamento della documentazione (`CLAUDE.md`, pagina Info dell'app).

### Escluso (out of scope)

- **Trasferimento della DEK/KEK**: ogni installazione mantiene le proprie chiavi. Non si "clona"
  un'identità crittografica, si migra il contenuto.
- **Trasferimento della configurazione FTP** (credenziali): escluso su decisione esplicita.
- **Sincronizzazione bidirezionale o continua**: il flusso è one-shot e unidirezionale
  (mittente → destinatario). Nessun merge, nessuna risoluzione di conflitti oltre alla dedup.
- **Trasferimento via Internet / relay cloud**: solo LAN, entrambi i device sulla stessa rete.
- **Bluetooth, Wi-Fi Direct, Nearby Connections**: scartati (throughput / dipendenze aggiuntive).
- **Backup su file verso PC**: è una feature diversa, valutabile in seguito riusando il formato manifest.
- **Trasferimento in background**: come per la sync FTP e l'upload Wi-Fi, il trasferimento vive
  finché la schermata è aperta. Nessun foreground service dedicato.
- **Cancellazione automatica della libreria sul mittente** dopo la migrazione.

## 3. User Stories

**US-1 — Migrazione su telefono nuovo**
> Come utente che ha appena comprato un telefono nuovo, voglio trasferire l'intera libreria dal
> vecchio device al nuovo tramite Wi-Fi, per non dover reimportare i brani uno a uno e non perdere
> le playlist che ho costruito.

**US-2 — Certezza di parlare col device giusto**
> Come utente, voglio confermare un PIN mostrato su entrambi i telefoni prima che parta il
> trasferimento, per essere sicuro che i miei brani vadano al mio device e non a un altro
> dispositivo sulla stessa rete (Wi-Fi di casa condiviso, ufficio, rete pubblica).

**US-3 — Riservatezza dei contenuti sulla rete**
> Come utente attento alla privacy, voglio che i brani viaggino cifrati anche sulla LAN, per essere
> certo che chi è connesso alla stessa rete non possa intercettarli, coerentemente col fatto che
> l'app li tiene cifrati a riposo.

**US-4 — Trasferimento parziale**
> Come utente con una libreria grande, voglio poter scegliere di inviare solo alcune playlist o
> alcuni brani, per copiare sul secondo telefono solo ciò che mi serve senza saturarne lo spazio.

**US-5 — Visibilità e recuperabilità**
> Come utente, voglio vedere quanti brani mancano, a che punto è il file corrente e cosa è andato
> storto, e voglio poter rilanciare il trasferimento dopo un'interruzione senza ricominciare da capo.

## 4. Criteri di accettazione

### US-1 — Migrazione su telefono nuovo
- [ ] Dal menu dell'app sono raggiungibili le voci "Invia libreria" e "Ricevi libreria".
- [ ] Il device mittente mostra il proprio indirizzo sulla LAN (o è individuabile automaticamente) e un PIN.
- [ ] Al termine, il destinatario ha nel DB tutti i brani inviati con `title`, `artist`, `album`,
      `duration`, `year`, `trackNumber`, `originalExtension` e `originalFileSize` identici all'origine.
- [ ] Le copertine sono presenti sul destinatario e visibili in lista e nel dettaglio brano.
- [ ] Le playlist inviate esistono sul destinatario con lo stesso nome, gli stessi brani e lo stesso ordinamento.
- [ ] I brani trasferiti sono riproducibili sul destinatario dopo lo sblocco biometrico.
- [ ] La schermata Statistiche del destinatario riporta conteggi e dimensioni coerenti con quanto ricevuto.

### US-2 — Pairing autenticato
- [ ] Il trasferimento non parte finché il PIN non è confermato su entrambi i lati.
- [ ] Un PIN errato fa fallire il pairing con messaggio esplicito, senza trasferire alcun byte.
- [ ] Dopo 3 tentativi errati il mittente invalida il PIN e ne genera uno nuovo.
- [ ] Il PIN ha validità limitata (max 5 minuti) e decade alla chiusura della schermata.
- [ ] Una richiesta senza pairing valido riceve `404`/`401` e non espone alcun metadato.

### US-3 — Riservatezza sulla rete
- [ ] Nessun byte di audio o di metadati transita in chiaro: manifest e file sono cifrati con la
      chiave di sessione (AES-256-GCM) prima di essere scritti sul socket.
- [ ] La chiave di sessione è effimera: nuova a ogni pairing, mai persistita su disco.
- [ ] La DEK non lascia mai il device: non compare in nessun payload di rete né nel manifest.
- [ ] Un dump del traffico (es. tcpdump sulla LAN) non permette di ricostruire i file audio.
- [ ] I file temporanei in chiaro (mittente e destinatario) sono cancellati al termine di ogni brano,
      e i residui sono rimossi da `OrphanCleanupUseCase` all'avvio successivo.

### US-4 — Trasferimento parziale
- [ ] Il mittente può scegliere "tutta la libreria", "playlist selezionate" o "brani selezionati".
- [ ] Selezionando una playlist vengono inviati automaticamente tutti i brani che contiene.
- [ ] Il conteggio dei brani e la dimensione totale stimata sono mostrati prima di confermare l'invio.

### US-5 — Progresso e ripresa
- [ ] Entrambi i lati mostrano: brano corrente, `n/tot`, percentuale del file in corso.
- [ ] Al termine è mostrato un riepilogo `aggiunti / già presenti / errori`.
- [ ] Se la connessione cade, la UI mostra l'errore e consente di rilanciare.
- [ ] Al rilancio i brani già presenti sul destinatario risultano "già presenti" e non vengono riscaricati.
- [ ] Se la DEK non è sbloccata su uno dei due lati, l'operazione è bloccata con messaggio
      "Sessione scaduta", coerente con `playQueue()`/`playSingle()`.

## 5. Rischi e dipendenze

### Rischi tecnici

| # | Rischio | Impatto | Mitigazione |
|---|---|---|---|
| R1 | **PIN a 6 cifre = bassa entropia.** Un semplice `HKDF(PIN)` è forzabile offline da chi cattura il traffico | Alto | Chiave di sessione da scambio **ECDH effimero** (secp256r1, `KeyAgreement` di piattaforma) + HKDF-SHA256; il PIN è la **verifica corta** (SAS) derivata dall'hash del transcript e confrontata dall'utente sui due schermi → blocca il MITM senza dipendere dall'entropia del PIN |
| R2 | **Cleartext HTTP bloccato lato client.** Da API 28 il traffico `http://` in uscita è vietato di default e nel manifest non c'è né `usesCleartextTraffic` né una network security config | Alto (blocca il lato ricevente) | Network security config mirata al solo range LAN, oppure `usesCleartextTraffic` solo per il traffico verso IP privati. Il payload è comunque cifrato a livello applicativo (R1), quindi HTTP resta accettabile |
| R3 | **Manca un client HTTP.** In `libs.versions.toml` ci sono solo `ktor-server-core` e `ktor-server-cio` | Medio | Aggiungere `ktor-client-cio` (stessa versione 2.3.12, coerente col resto) oppure usare `HttpURLConnection`: scelta da fissare in Fase 2 |
| R4 | **Individuare l'altro device.** Digitare a mano un IP è pessima UX | Medio | `NsdManager` (mDNS, framework Android, nessuna dipendenza) per pubblicare/scoprire il servizio; fallback con IP digitato manualmente |
| R5 | **Doppia cifratura CPU-bound**: decifra DEK → cifra sessione → decifra sessione → cifra DEK per ogni file | Medio | Streaming a blocchi (64 KB) come nel `UploadServer`, mai il file intero in memoria; misurare il throughput su libreria reale |
| R6 | **Spazio disco sul destinatario**: temp in chiaro + file cifrato coesistono durante l'import | Medio | Un brano alla volta, cancellazione immediata del temp; controllo preventivo dello spazio libero contro la dimensione totale annunciata nel manifest |
| R7 | **Trasferimento lungo con app in primo piano**: schermo che si spegne o app in background → connessione interrotta | Medio | Wake lock / `keepScreenOn` sulla schermata, ripresa via dedup; foreground service esplicitamente fuori scope |
| R8 | **Dedup per `(title, artist, album)` troppo grossolana**: brani con metadati identici ma file diversi (versioni live, remaster) vengono saltati | Basso | Comportamento noto e coerente con FTP/upload; il manifest può portare `originalFileSize` come discriminante secondaria — decisione da confermare in Fase 3 |
| R9 | **Collisione di ID**: gli ID brano/playlist sono UUID generati all'import; reimportare rigenera l'ID e rompe i riferimenti delle playlist | Medio | Il manifest trasporta gli ID di origine e il destinatario mantiene una mappa `idOrigine → idLocale` per ricostruire `playlist_track_cross_ref` e `lastPlayedTrackId` |
| R10 | **Nessun test strumentato nel progetto**: il flusso è per natura end-to-end su due device | Medio | Coprire con unit test JVM handshake, HKDF, serializzazione manifest e mappatura ID; il resto con checklist di test manuale a due device documentata |

### Dipendenze

- **Interne**: `CryptoManager` (nuove primitive per cifratura con chiave arbitraria, oggi tutto passa dalla DEK),
  `ImportTrackUseCase` (import da `File`, già disponibile), `TrackDao` / `PlaylistDao`,
  `NetworkUtils`, `UploadServer` (pattern Ktor + capability URL), `OrphanCleanupUseCase`.
- **Esterne**: client HTTP (`ktor-client-cio` 2.3.12 o `HttpURLConnection`); nessun'altra libreria
  prevista — ECDH, HKDF e AES-GCM arrivano da `javax.crypto` / JCA, mDNS da `NsdManager`.
- **Ambientali**: entrambi i device sulla stessa LAN, con isolamento client Wi-Fi (AP isolation)
  disattivato; entrambe le installazioni sbloccate biometricamente.

## 6. Stima effort

Stima per **un singolo sviluppatore**, giorni/uomo.

| Area | Attività | Stima |
|---|---|---|
| Core / domain | Handshake ECDH + SAS/PIN + HKDF, cifratura di sessione, primitive `CryptoManager` con chiave arbitraria | 1,5 gg |
| Core / domain | `TransferServer` (routing Ktor: `pair`, `manifest`, `track/{id}`, `art/{id}`), gestione stato e contatori | 1,5 gg |
| Core / domain | `TransferClient` + `ReceiveLibraryUseCase`: manifest → download → decifra → import → mappa ID → playlist | 1,5 gg |
| Core / domain | Modello + serializzazione del manifest, selezione contenuti, stima dimensioni | 0,5 gg |
| Core / domain | Discovery `NsdManager` + fallback IP manuale + network security config | 1 gg |
| UI | Schermata "Invia libreria" (selezione contenuti, PIN, progresso, riepilogo) + ViewModel | 1 gg |
| UI | Schermata "Ricevi libreria" (scoperta peer, inserimento PIN, progresso, riepilogo) + ViewModel | 1 gg |
| UI | Voci di menu, navigazione, permessi, `keepScreenOn`, stringhe | 0,5 gg |
| Test | Unit JVM: handshake/HKDF, manifest, mappatura ID, dedup | 1 gg |
| Test | Sessione di test manuale a due device (+ emulatore/telefono), casi di errore e ripresa | 0,5 gg |
| Documentazione | `CLAUDE.md`, pagina Info, checklist di test manuale | 0,5 gg |
| **Totale** | | **10,5 gg/uomo** |

Non incluso: eventuale hardening crittografico oltre lo schema ECDH+SAS (es. SPAKE2), da valutare
solo se emerge in review.

## 7. Milestones

| # | Milestone | Contenuto | Dipende da |
|---|---|---|---|
| M0 | **Prerequisiti** | Scelta client HTTP, network security config per la LAN, primitive `CryptoManager` con chiave arbitraria (cifra/decifra stream con `SecretKey` passata) | — |
| M1 | **Protocollo e manifest** | Definizione delle rotte, formato del manifest JSON, versione di protocollo, modello dati condiviso | M0 |
| M2 | **Handshake sicuro** | ECDH effimero + HKDF + SAS a 6 cifre, rate limit tentativi, scadenza pairing; coperto da unit test | M1 |
| M3 | **Lato mittente** | `TransferServer` Ktor: pairing, manifest cifrato, streaming brano decifrato-DEK/ricifrato-sessione, contatori di stato | M2 |
| M4 | **Lato destinatario** | `TransferClient` + use case di ricezione: manifest → download → import via `ImportTrackUseCase` → dedup → mappa ID → playlist | M3 |
| M5 | **Discovery** | `NsdManager` per pubblicare/scoprire il peer, con fallback a IP manuale | M3 |
| M6 | **UI** | Schermate Invia/Ricevi, selezione contenuti, progresso, riepilogo, gestione errori e "Sessione scaduta" | M4, M5 |
| M7 | **Robustezza** | Interruzione e ripresa, spazio disco insufficiente, DEK bloccata, cleanup temporanei, `keepScreenOn` | M6 |
| M8 | **Test e documentazione** | Unit test, checklist manuale a due device, aggiornamento `CLAUDE.md` e pagina Info | M7 |

## 8. Domande aperte per la Fase 2

1. **Chi espone il server**: mittente (il destinatario "tira") o destinatario (il mittente "spinge")?
   La proposta è **mittente = server**, coerente con `UploadServer` e con il fatto che il device
   nuovo è quello che cerca.
2. **PIN generato o SAS derivata**: PIN random mostrato dal mittente e digitato dal destinatario,
   oppure 6 cifre derivate dal transcript ECDH e confrontate a vista su entrambi gli schermi?
   La seconda è più robusta (vedi R1) ma richiede una UI di conferma su entrambi i lati.
3. **`importedAt`**: preservare la data di import originale (storia fedele) o usare la data del
   trasferimento (semantica letterale del campo)? Impatta la schermata Statistiche.
4. **Dedup**: aggiungere `originalFileSize` alla chiave `(title, artist, album)` per questo flusso,
   o restare identici al comportamento FTP/upload?
5. **`lastPlayedTrackId`** delle playlist: trasferirlo o azzerarlo sul destinatario?
6. **Playlist già esistenti** sul destinatario con lo stesso nome: fondere, duplicare o rinominare?
