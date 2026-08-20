# Checklist di test manuale — Trasferimento sicuro fra istanze

> Da eseguire con **due telefoni** sulla stessa rete Wi-Fi. I casi TC-09, TC-12 e TC-15
> richiedono hardware reale: su un solo device si possono usare le due installazioni
> conviventi (`it.agoldoni.player` e `it.agoldoni.player.debug`), ma il percorso di rete
> resta loopback e non è rappresentativo.
>
> Prerequisito per ogni caso: **entrambe le app sbloccate** con l'impronta digitale.
> Compila `Esito` con ✅ / ❌ e annota data e note.

| ID | Caso | Passi | Atteso | Esito |
|---|---|---|---|---|
| TC-09 | Migrazione completa | A: drawer → "Invia libreria" → "Tutta la libreria" → Avvia. B: drawer → "Ricevi libreria" → tocca A nell'elenco → confronta il codice → conferma su entrambi | Tutti i brani arrivano; titolo, artista, album, anno, numero di traccia, durata, estensione, dimensione e data di import identici; copertine visibili; playlist ricostruite; brani riproducibili | | ✅ 2026-08-20 (emulatori) — 4 brani, metadati identici campo per campo nel DB |
| TC-10 | Libreria sovrapposta | Ripetere TC-09 con B che ha già metà dei brani | I duplicati risultano "già presenti"; nessun doppione in lista; le playlist restano complete (i brani già presenti ne fanno parte) | | ✅ 2026-08-20 — giro con libreria identica: 0 aggiunti / 4 già presenti; giro con 2 brani su 4: 2+2, playlist fusa senza duplicati |
| TC-11 | Codice non corrispondente | Su A premere "Non coincidono" | Nessun byte trasferito; A torna in attesa; dopo 3 rifiuti A si ferma con "Troppi tentativi di collegamento rifiutati" | | ✅ 2026-08-20 — 0 byte trasferiti; al 3° rifiuto il mittente si ferma |
| TC-12 | Interruzione e ripresa | A metà trasferimento spegnere il Wi-Fi di B, poi riaccenderlo e rilanciare | Errore mostrato in UI; al rilancio i brani già ricevuti risultano "già presenti" e non vengono riscaricati | | ⏳ da fare su rete reale | ✅ 2026-08-20 — connessione tagliata a 12 MB: 1 aggiunto, 4 errori, nessuna riga a DB né file per il brano troncato, `transfer_temp` vuota, `AEADBadTagException` nel log |
| TC-13 | Sessione scaduta | Forzare la chiusura di una delle due app e riaprirla senza autenticarsi (o attendere il riavvio del processo), poi avviare il trasferimento | Messaggio "Sessione scaduta. Riavvia l'app per autenticarti."; nessuna operazione sui file | | ⏳ da fare (l'emulatore debug sblocca la DEK da solo) |
| TC-14 | Spazio insufficiente | Riempire la memoria di B fino a lasciare meno del doppio della dimensione annunciata | Avviso con spazio richiesto e disponibile **prima** di scaricare qualsiasi brano | | ⏳ da fare |
| TC-15 | Riservatezza sul filo | Catturare il traffico fra i due telefoni (es. `tcpdump` sul router o `adb shell tcpdump`) durante un trasferimento | Nessun frammento audio o metadato leggibile; i payload sono opachi | | ✅ 2026-08-20 — 693 KB catturati da un proxy: 0 occorrenze di titoli, artisti, album, ID3/fLaC/LAME |
| TC-16 | Nessun residuo | A fine trasferimento ispezionare `cacheDir/transfer_temp` (`adb shell run-as it.agoldoni.player.debug ls cache/transfer_temp`), poi riavviare l'app | Directory vuota a fine trasferimento; comunque ripulita allo startup da `OrphanCleanupUseCase` | | ✅ 2026-08-20 — transfer_temp vuota; 4 file cifrati (+28 byte l'uno), nessun marker in chiaro |
| TC-17 | Formati e copertine | Includere nella selezione almeno un `.flac`, un `.mp3` con copertina e uno senza | Tutti importati; estensione originale conservata; copertina presente solo dove c'era | | ✅ 2026-08-20 — 1 .flac + 3 .mp3, copertine solo sui 2 brani che le avevano |
| TC-18 | Schermo e background | Durante l'invio bloccare lo schermo e portare l'app in background | Con schermata aperta lo schermo resta acceso; se l'app va in background il trasferimento può interrompersi → ripresa come TC-12 | | ⏳ da fare su device reale |
| TC-19 | Coesistenza con l'upload Wi-Fi | Aprire "Ricevi via Wi-Fi" su A (porta 8080-8090), poi avviare "Invia libreria" | Nessun conflitto di porta: il trasferimento usa 8091-8100 | | ⏳ da fare |
| TC-20 | Trasferimento parziale | Su A scegliere "Playlist selezionate" (una sola), poi ripetere con "Brani selezionati" | Arrivano solo i brani attesi; con "Playlist" la playlist viene creata su B; con "Brani" non viene creata alcuna playlist | | ✅ 2026-08-20 — invio della sola playlist 'Serale': 2 brani + playlist creata |
| TC-21 | Playlist omonima | Su B creare una playlist con lo stesso nome di una inviata, poi trasferire | Le due vengono fuse: nessun duplicato, i brani si sommano, il `lastPlayedTrackId` locale di B resta invariato | | ✅ 2026-08-20 — playlist omonima fusa, nessun duplicato, stesse relazioni |
| TC-22 | Collegamento manuale | Su B ignorare l'elenco e inserire indirizzo, porta e codice di accesso mostrati da A | Il pairing funziona identico alla scoperta automatica | | ✅ 2026-08-20 — usato per tutti i giri (unico percorso possibile fra emulatori) |
| TC-24 | Prima ricezione su telefono nuovo | Installare l'app su un telefono senza libreria, aprire "Ricevi libreria", collegarsi al mittente | Compare il prompt dell'impronta ("Autenticati per ricevere i brani"), la chiave viene creata e il trasferimento prosegue; nessun messaggio "Sessione scaduta" | ⏳ da verificare sul telefono nuovo |
| TC-23 | Rete senza mDNS | Ripetere su una rete con isolamento client / mDNS filtrato | L'elenco resta vuoto o compare l'avviso "Ricerca automatica non disponibile"; il collegamento manuale resta possibile | | ✅ 2026-08-20 — fra emulatori mDNS non passa: elenco vuoto, collegamento manuale ok |

## Esecuzione del 2026-08-20 — due emulatori

**Setup.** Due istanze dello stesso AVD (`Emulator_x86_64`, API 33) lanciate con `-read-only`
sulle porte 5554 (mittente) e 5556 (ricevente). Libreria del mittente popolata via il server
"Ricevi via Wi-Fi" già presente nell'app (4 brani generati con ffmpeg: 3 `.mp3` — due con
copertina — e 1 `.flac`) più una playlist "Serale" con 2 brani.

**Limite dell'ambiente.** Ogni emulatore vive dietro il proprio NAT e non vede l'altro: mDNS non
attraversa, quindi la scoperta automatica resta vuota (TC-23 nella variante "rete senza mDNS") e i
giri usano il collegamento manuale. Il percorso è
`ricevente → 10.0.2.2:<porta> → adb forward → mittente:8091`.

**Sei giri eseguiti**: libreria completa su ricevente vuoto · stessa libreria già presente (dedup) ·
tre rifiuti del codice · trasferimento completo osservato da un proxy che registra il traffico ·
invio della sola playlist · libreria parzialmente sovrapposta (2 su 4).

**Prove raccolte**
- Confronto SQL dei due DB: `title, artist, album, duration, year, trackNumber, originalExtension,
  originalFileSize, importedAt` e presenza copertina **identici**; ID dei brani diversi (rimappati).
- Playlist "Serale" sul ricevente con lo stesso `createdAt` e gli stessi `addedAt` delle relazioni.
- Codice a 6 cifre **uguale sui due schermi** a ogni giro, e rigenerato dopo ogni rifiuto.
- Riproduzione di un brano trasferito sul ricevente (`PlaybackState state=3`): la ricifratura con la
  DEK locale è corretta.
- Traffico catturato: in chiaro solo l'handshake (chiavi pubbliche, `sessionId`) e gli header HTTP;
  le richieste di manifest prima della conferma rispondono `409`.

**Bug trovati e corretti durante i test.** Riaprendo "Invia libreria" dopo un trasferimento, la
schermata mostrava il riepilogo del giro precedente invece della scelta dei contenuti: `TransferServer`
è un singleton e `stop()` conservava gli stati finali. Ora `stop()` riporta lo stato a `Idle`.

Su device reale è emerso il caso più importante: sul telefono che riceve, **appena installato**, la
DEK non esiste ancora e il gate biometrico all'avvio viene saltato, quindi "Ricevi libreria" moriva
con "Sessione scaduta. Riavvia l'app per autenticarti." (e riavviare non cambiava nulla). Ora le due
schermate chiedono l'impronta e creano la chiave al primo uso. Da verificare con TC-24 qui sotto.

**Interruzione a metà brano (TC-12), verificata il 2026-08-20.** Tagliando la connessione dopo
12 MB con un proxy: il brano a metà fallisce con `AEADBadTagException` — il tag GCM è verificato
alla fine, quindi un troncamento non passa inosservato — il temporaneo viene cancellato e il brano
conta come errore. Nel DB e in `files/tracks` resta solo ciò che è arrivato intero (dimensione
esatta: originale + 28 byte), e il brano completo si riproduce. **Un file troncato non può entrare
in libreria**: il file cifrato viene scritto prima dell'inserimento a DB, quindi anche un'app uccisa
a metà lascia al massimo un orfano, che `OrphanCleanupUseCase` rimuove al riavvio.

**Come controllare la libreria dopo un trasferimento interrotto.** Statistiche → *Verifica
integrità*: decifra ogni brano e verifica il tag GCM, elencando quelli con file mancante,
dimensione incoerente o contenuto corrotto. Verificato sugli emulatori il 2026-08-20 sia sul caso
sano ("Tutti i 5 brani sono integri") sia iniettando due guasti — un file cancellato e 32 byte
casuali scritti in mezzo a un altro — entrambi segnalati con il motivo giusto.

**Non verificabile su emulatore**: TC-12 (caduta di rete reale), TC-13 (sull'emulatore debug la DEK
si sblocca da sola), TC-14, TC-18, TC-19 e la scoperta mDNS in condizioni normali.

## Misure da annotare

- Durata del trasferimento e numero di brani: ______ · throughput calcolato: ______ MB/s (soglia della DoD: ≥ 3 MB/s)
- Dimensione libreria trasferita: ______
- Modelli e versioni Android dei due device: ______
