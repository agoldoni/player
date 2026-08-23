# Fase 1 — Requisiti: Esportazione della libreria su PC via WebDAV

> Feature slug: `esportazione-libreria-webdav`
> Data: 2026-08-23
> Stato: bozza requisiti — in attesa di conferma per Fase 2

## Decisioni già prese (input utente)

| Ambito | Scelta |
|---|---|
| Direzione | **App → PC**: l'app è la sorgente, una cartella sul filesystem del PC è la destinazione |
| Chi tira | **Il PC**, con un client standard; l'app espone e basta |
| Trasporto | **WebDAV su HTTP nella LAN**, così sul PC si usa `rclone` (o `rclone mount` + `rsync` vero) |
| Criterio di "già presente" | **Percorso + dimensione** — il default di `rsync`/`rclone --size-only` |
| Struttura di destinazione | `Artista/Album/NN - Titolo.ext` |
| Playlist | **Fuori scope** in questo giro |

## 1. Obiettivo e motivazione

La libreria dell'app è oggi un vicolo cieco in uscita. Le tre funzioni di rete esistenti vanno
tutte nella direzione opposta o non servono a un PC:

- `UploadServer` — **riceve** brani da un browser sul PC (PC → app);
- `SyncFromFtpUseCase` — **scarica** da un server FTP (FTP → app);
- `TransferServer`/`TransferClient` — parlano solo con **un'altra istanza dell'app**, con un
  protocollo ECDH proprietario che nessun software di terze parti sa interpretare.

Manca l'uscita verso un filesystem generico: non c'è modo di portarsi i brani su un PC per
ascoltarli altrove, archiviarli o farne un backup indipendente dal telefono.

Il vincolo che detta la forma della soluzione: i brani stanno in `filesDir/tracks/{id}` cifrati
AES-256-GCM con la DEK, a sua volta wrappata da una KEK non esportabile in AndroidKeystore e
sbloccata dall'impronta. Conseguenze concrete:

- `adb pull` non è una strada: `filesDir` è storage privato dell'app e, anche potendolo leggere,
  restituirebbe **blob cifrati indecifrabili** fuori dal device;
- copiare i file su una SD o su `/sdcard` avrebbe lo stesso problema, e decifrarli lì
  vanificherebbe la cifratura a riposo;
- **l'unico punto in cui i byte esistono in chiaro è dentro il processo dell'app, mentre è aperta
  e la DEK è sbloccata.** Qualunque esportazione deve passare da lì.

L'obiettivo è quindi che l'app esponga la propria libreria come un **filesystem di sola lettura
sulla rete locale**, in un dialetto che gli strumenti di sincronizzazione già parlano, così che
il PC possa scaricare **solo ciò che non ha ancora** senza che l'app debba tenere traccia di
nulla.

Valore: backup e ascolto fuori dal telefono, riuso di strumenti standard invece di software
dedicato, e — punto centrale della richiesta — **nessun ritrasferimento di ciò che è già a
destinazione**.

### Perché WebDAV e non uno script dedicato

Con WebDAV il diff non lo fa l'app: lo fa il client, guardando la cartella di destinazione
reale. `rclone` lista una directory con `PROPFIND`, legge `getcontentlength` di ogni file, lo
confronta con quello che trova in locale e con `--size-only` scarica soltanto ciò che manca o
differisce. L'app resta **stateless**: nessun indice degli export, nessuna cache che possa
disallinearsi dalla realtà e mentire.

In più `rclone mount --read-only` rende la libreria una directory POSIX, sulla quale si può
lanciare `rsync` vero — che era la formulazione originale della richiesta.

## 2. Scope

### Incluso

- **Server WebDAV read-only** dentro l'app, sulla LAN, avviabile e arrestabile dall'utente.
- **Albero virtuale** derivato dal DB: `Artista/Album/NN - Titolo.ext`, con nomi sanitizzati per
  filesystem Windows/macOS/Linux e disambiguazione **stabile** dei nomi collidenti.
- **Metodi WebDAV**: `OPTIONS`, `PROPFIND` (Depth 0 e 1), `HEAD`, `GET`. Tutto il resto → `405`.
- **Decifratura in streaming** al momento del `GET`: i file restano cifrati a riposo, il chiaro
  esiste solo nel flusso di rete, senza temporanei su disco.
- **Dimensione esatta** dichiarata in `getcontentlength`, coerente al byte con quanto servito
  dalla `GET`.
- **Capability URL**: tutte le rotte sotto un token casuale, come già fanno upload Wi-Fi e
  trasferimento fra istanze.
- **Schermata "Esporta su PC"** con URL, numero di brani, spazio esposto e i comandi `rclone`
  pronti da incollare; schermo tenuto acceso mentre il server è attivo.
- **Test JVM** sulla logica pura (albero dei nomi e generazione XML).
- Aggiornamento di `CLAUDE.md` e documentazione della feature.

### Escluso (out of scope)

- **Playlist come `.m3u8`** sul PC — deciso di non farlo in questo giro.
- **Copertine come file separati** (`cover.jpg`): l'artwork resta nei tag ID3 del file, che viene
  servito integro.
- **Scrittura da PC verso l'app** (`PUT`, `MKCOL`, `DELETE`): per quel verso esiste già
  "Ricevi via Wi-Fi".
- **Richieste `Range`** e ripresa a metà file: su AES-GCM in streaming un seek costringerebbe a
  decifrare e scartare tutto il prefisso.
- **HTTPS / autenticazione HTTP Basic**: il capability token è la protezione di questo giro, in
  linea con l'upload Wi-Fi. Basic auth resta un'estensione naturale successiva.
- **Foreground service**: il server vive finché la schermata è aperta, coerentemente con FTP,
  upload Wi-Fi e trasferimento fra istanze.
- **Discovery mDNS**: `rclone` non ne fa uso; l'URL si incolla una volta in `rclone config`.
- **Sincronizzazione bidirezionale o propagazione delle cancellazioni**: il PC è una
  destinazione additiva, non uno specchio.

## 3. User Stories

- **US-1** — Come possessore della libreria voglio **scaricare sul PC solo i brani che non ho
  ancora** nella cartella di destinazione, per non ritrasferire ogni volta l'intera libreria.
- **US-2** — Come ascoltatore voglio che la cartella sul PC sia organizzata in
  **`Artista/Album/NN - Titolo.ext`**, per poterla aprire con qualsiasi player senza riorganizzare
  nulla a mano.
- **US-3** — Come utente voglio usare **strumenti standard già installati** (`rclone`, `rsync`),
  senza dover installare o mantenere software specifico dell'app sul PC.
- **US-4** — Come utente attento alla sicurezza voglio che la libreria sia raggiungibile **solo
  mentre l'app è aperta e sbloccata**, e che chi sta sulla stessa Wi-Fi senza conoscere il token
  non possa leggerla né elencarla.
- **US-5** — Come utente con una libreria grande voglio poter **interrompere e riprendere** il
  trasferimento, ritrovando al riavvio solo i file ancora mancanti.

## 4. Criteri di accettazione

### US-1 — Scaricare solo ciò che manca

- [ ] Un primo `rclone copy player: <dest> --size-only` scarica tutti i brani della libreria.
- [ ] Un secondo `rclone copy` immediatamente dopo, a cartella invariata, riporta
      **`Transferred: 0 B`** e non apre alcuna connessione dati per i brani.
- [ ] Cancellando un singolo file dalla destinazione, il pull successivo riscarica **solo quello**.
- [ ] Troncando un file a metà, il pull successivo lo riscarica (la dimensione non coincide).
- [ ] `rclone check player: <dest> --size-only` riporta **0 differenze** dopo un pull completo.

### US-2 — Struttura della destinazione

- [ ] `rclone lsl player:` mostra percorsi nella forma `Artista/Album/NN - Titolo.ext`.
- [ ] `trackNumber` nella forma `"3/12"` produce il prefisso `03`; se il tag manca, il prefisso
      non compare e il nome resta valido.
- [ ] Artista o album vuoti producono rispettivamente `Sconosciuto` e `Senza album`, coerenti con
      `TrackDao.UNKNOWN_ARTIST`.
- [ ] Caratteri vietati (`/ \ : * ? " < > |`, controlli) sostituiti; nessun segmento termina con
      punto o spazio.
- [ ] Due brani che collasserebbero sullo stesso nome ricevono un suffisso che li distingue, e
      **quel suffisso non cambia** fra un pull e l'altro né aggiungendo altri brani alla libreria.
- [ ] Un file scaricato si apre in VLC, suona e mostra i tag corretti.

### US-3 — Strumenti standard

- [ ] `rclone config create player webdav url=… vendor=other` è sufficiente a configurare il remoto.
- [ ] `rclone lsl`, `rclone copy`, `rclone check` funzionano senza flag di compatibilità.
- [ ] `rclone mount player: /mnt/player --read-only` monta l'albero, e
      `rsync -av --ignore-existing /mnt/player/ <dest>/` completa senza errori.
- [ ] Nessuno script custom da installare sul PC.

### US-4 — Superficie di attacco

- [ ] Una richiesta con token errato riceve **404**, senza distinguere "token sbagliato" da
      "risorsa inesistente".
- [ ] `PUT`, `DELETE`, `MKCOL`, `MOVE`, `COPY`, `LOCK` ricevono **405** su qualsiasi path.
- [ ] Con la DEK non sbloccata (processo riavviato) le `GET` rispondono **503**, non byte cifrati.
- [ ] Chiudendo la schermata il server si arresta e la porta torna libera.
- [ ] La schermata dichiara esplicitamente che il traffico è in chiaro sulla rete locale.

### US-5 — Interruzione e ripresa

- [ ] Chiudendo la schermata durante un pull, `rclone` termina con un errore di rete pulito e i
      file già completi nella destinazione restano validi.
- [ ] Riavviando il server e ripetendo il `copy`, vengono trasferiti solo i file mancanti.
- [ ] Un file rimasto incompleto viene riscaricato per intero (nessun `.part` sporco lasciato in
      giro da parte di rclone).

## 5. Rischi e dipendenze

### Rischi tecnici

| # | Rischio | Impatto | Mitigazione |
|---|---|---|---|
| R1 | **Ktor non conosce `PROPFIND`**: il routing per metodi non standard su engine CIO va verificato, non dato per scontato | Bloccante: cambia la forma dell'intera feature | Spike da 0,5 gg **prima di ogni altra cosa** (`curl -X PROPFIND` su un server minimo). Fallback: intercettare la richiesta in un plugin `onCall` a monte del routing |
| R2 | **`getcontentlength` diverso dai byte serviti** → rclone aborta con *"corrupted on transfer: sizes differ"* | Alto: nessun file scaricabile | Derivare la dimensione da un'unica fonte, il file cifrato: il formato è `IV(12) ‖ ciphertext ‖ tag(16)`, quindi il chiaro è esattamente `length() - 28`. Mai fidarsi di `Track.originalFileSize`, che vale `0` per i brani importati prima della migrazione `1→2` |
| R3 | **Encoding degli `href`**: percent-encoding dei segmenti e XML-escaping sono due passaggi distinti e vanno applicati in quest'ordine | Alto: è il bug classico di WebDAV, e con nomi italiani (accenti, `&`) si manifesta subito | Generazione XML isolata in una classe pura con test JVM dedicati sui casi sporchi |
| R4 | **Nomi collidenti instabili**: se il suffisso di disambiguazione dipendesse da un contatore, cambierebbe al variare della libreria | Medio: riscaricamenti a ripetizione, cioè il fallimento del requisito principale | Suffisso derivato dall'UUID del brano: stabile e indipendente dal resto della libreria |
| R5 | **Audio in chiaro su HTTP nella LAN** | Medio, ma è una deviazione consapevole rispetto al trasferimento fra istanze | Capability token, bind sulla sola LAN, finestra limitata alla schermata aperta, avviso esplicito in UI. DEK e KEK non lasciano comunque il device |
| R6 | **Trappola AES-GCM**: letture a blocchi piccoli producono comportamento quadratico (~1,9 MB/s) | Alto sull'usabilità: una libreria da GB diventerebbe intrasferibile | Usare `CryptoManager.decryptingStream`, che accorpa in blocchi da 64 KB; **mai** `CipherInputStream`. Misura di controllo in collaudo — **⚠️ mitigazione errata, corretta in Fase 2 §B.5: `decryptingStream` *è* `CipherInputStream`, serve una primitiva nuova** |
| R7 | **Sessione lunga a schermo acceso**: un pull da diversi GB tiene la schermata attiva per parecchi minuti | Medio: batteria e throttling termico | `FLAG_KEEP_SCREEN_ON` come già fa la sync FTP, avviso in UI, e il fatto che un pull interrotto riprende rende il problema tollerabile |
| R8 | **Varianti di rclone**: comportamenti diversi fra versioni o `vendor` | Basso | Fissare `vendor=other` e annotare in `test-manuale.md` la versione con cui si è collaudato |

### Dipendenze

- **Nessuna nuova dipendenza Gradle**: Ktor server CIO è già nel progetto per `UploadServer` e
  `TransferServer`.
- **Nessun permesso nuovo**: `INTERNET` e `ACCESS_NETWORK_STATE` sono già dichiarati.
- **Nessuna migrazione DB**: la feature legge `tracks` e non aggiunge stato.
- `res/xml/network_security_config.xml` consente già il traffico in chiaro.
- **Lato PC**: `rclone` installato (e `fuse` per il solo scenario `rclone mount` + `rsync`).
- **Ambiente**: telefono e PC sulla stessa rete locale, con la Wi-Fi che non isoli i client fra
  loro (AP isolation).

## 6. Stima effort

| Area | Attività | Giorni/uomo |
|---|---|---|
| Spike | Validazione routing `PROPFIND` su Ktor CIO (R1) | 0,5 |
| Dominio | `LibraryTree` (albero + sanitizzazione + collisioni) | 1,0 |
| Dominio | `WebDavXml` (multistatus, encoding, escaping) | 0,5 |
| Dominio | `WebDavServer` (routing, OPTIONS/PROPFIND/HEAD/GET, 405, streaming) | 1,5 |
| UI | Schermata "Esporta su PC" + ViewModel + rotta + voce di menu | 1,0 |
| Test | Unit test JVM su `LibraryTree` e `WebDavXml` | 0,5 |
| Test | Collaudo end-to-end con rclone su device reale | 0,5 |
| Documentazione | `CLAUDE.md`, `test-manuale.md`, pagina Info | 0,5 |
| | **Totale** | **6,0** |

Riaggregato secondo la ripartizione richiesta: **dominio/server 3,0** · **UI 1,0** ·
**test 1,0** · **documentazione 0,5**, più **0,5** di spike bloccante.

Stima per **un solo sviluppatore** che conosce già la codebase. Le voci di dominio sono
sequenziali, la UI può procedere in parallelo una volta fissato lo `StateFlow` del server.

## 7. Milestones

| # | Milestone | Contenuto | Uscita |
|---|---|---|---|
| **M0** | Spike PROPFIND | Server Ktor CIO minimo che risponde `207` a un `curl -X PROPFIND` | Via libera o cambio di approccio (R1) |
| **M1** | Albero virtuale | `LibraryTree` + `LibraryTreeTest` verdi | Percorsi deterministici da una `List<Track>` |
| **M2** | XML | `WebDavXml` + `WebDavXmlTest` verdi | Multistatus valido con nomi sporchi |
| **M3** | Server | `WebDavServer`: OPTIONS, PROPFIND, HEAD, GET, 405, 503 | `rclone lsl` mostra l'albero con dimensioni corrette |
| **M4** | UI | Schermata, ViewModel, rotta, voce di menu, keep-screen-on | Feature raggiungibile e arrestabile dall'app |
| **M5** | Collaudo | Checklist di `test-manuale.md` su device reale, misura di throughput | Doppio pull con `Transferred: 0 B` al secondo giro |
| **M6** | Documentazione | `CLAUDE.md`, esito del collaudo, pagina Info | Feature chiusa |

L'ordine è vincolante su M0 → M1 → M2 → M3; M4 può sovrapporsi a M3.

## 8. Domande aperte per la Fase 2

1. **Cache dell'albero**: ricostruirlo a ogni `PROPFIND` (semplice, e la libreria non cambia
   mentre la schermata di export è aperta) o memorizzarlo all'avvio del server? Da decidere
   guardando il costo reale su una libreria di qualche migliaio di brani.
2. **Livello "Artista" con album singoli**: un artista con un solo album produce due livelli di
   annidamento per un pugno di file. Si accetta la regolarità o si collassa? La regolarità aiuta
   il diff lato client, quindi la risposta attesa è "si accetta".
3. **Brani senza artista**: finiscono tutti sotto `Sconosciuto/`, che su una libreria mal taggata
   può diventare una directory enorme. È accettabile per il primo giro?
4. **`getlastmodified`**: usare `Track.importedAt` (stabile, ma non è la data del brano) va bene,
   dato che il criterio scelto è la sola dimensione?
5. **Estensione dichiarata**: `Track.originalExtension` è affidabile per tutti i brani in libreria
   o esistono record con il default `"mp3"` su file che mp3 non sono?
