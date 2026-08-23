# Checklist di test manuale — Esportazione della libreria su PC via WebDAV

> Da eseguire con **un telefono e un PC sulla stessa rete Wi-Fi**, con la rete che non isoli i
> client fra loro (AP isolation disattivata). Sull'emulatore il percorso di rete resta loopback:
> va bene per verificare il protocollo, non per misurare il throughput reale della Wi-Fi.
>
> Prerequisiti: app **sbloccata** (su emulatore debug la DEK si sblocca da sola), schermata
> "Esporta su PC" aperta — il server vive solo finché quella schermata è in primo piano — e
> `rclone` installato sul PC. Compila `Esito` con ✅ / ❌ e annota data e note.

## Setup

Sul telefono, package **debug** (`it.agoldoni.player.debug`):

```bash
./build.sh debug
adb install -r app/build/outputs/apk/debug/player.apk
adb shell am force-stop it.agoldoni.player.debug
adb shell monkey -p it.agoldoni.player.debug -c android.intent.category.LAUNCHER 1
```

Sbloccare, aprire il drawer → **"Esporta su PC"**, annotare l'URL mostrato. L'ispezione della UI
si fa con `uiautomator dump` + `screencap`: su un telefono fisico i tap via `adb shell input`
possono essere negati con `SecurityException` su `INJECT_EVENTS`.

Sul PC:

```bash
rclone config create player webdav url=http://<ip>:<porta>/<token> vendor=other
```

Su emulatore l'IP `10.0.2.15` non è raggiungibile dall'host: usare
`adb forward tcp:18101 tcp:8101` e puntare rclone a `http://127.0.0.1:18101/<token>`.

## Casi

| ID | Caso | Passi | Atteso | Esito |
|---|---|---|---|---|
| TC-12 | Routing di `PROPFIND` | `curl -i -X PROPFIND -H 'Depth: 1' <base>/` | `207 Multi-Status` con un `<D:response>` per la root e uno per ogni artista | ✅ 2026-08-23 (emulatore) — `207`, href già percent-encoded |
| TC-13 | Elenco e dimensioni | `rclone lsl player:` | Percorsi `Artista/Album/NN - Titolo.ext`, nessuna dimensione a zero, righe pari ai brani | ✅ 2026-08-23 — 8 brani, dimensioni **identiche** agli originali (49041, 7201760, 45282…) |
| TC-14 | **Doppio pull** | `rclone copy player: <dest> --size-only` due volte | Primo giro scarica tutto; **secondo `Transferred: 0 B`**; `rclone check --size-only` → 0 differenze | ✅ 2026-08-23 — 7,192 MiB / 8 file, poi "There was nothing to transfer", check `0 differences found, 8 matching files` |
| TC-15 | File mancante e file troncato | Cancellarne uno, troncarne un altro (`truncate -s 1000`), ripetere il `copy` | Riscaricati **solo** quei due | ✅ 2026-08-23 — 2/2 trasferiti (95,751 KiB), gli altri 6 saltati |
| TC-16 | Integrità byte a byte | `md5sum` scaricati contro originali | Hash identici | ✅ 2026-08-23 — 8/8 identici |
| TC-17 | Nomi sporchi | Brani con accenti, `&` e `/` nel titolo o nell'artista | Nomi sanitizzati, download OK, tag corretti | ✅ 2026-08-23 — `Fabrizio De André/Creuza de mä/01 - Crêuza de mä.mp3`, `Simon & Garfunkel/…`, `AC/DC` → `AC_DC`; `ffprobe` sul file scaricato riporta i tag originali |
| TC-17b | **Collisione di nomi** | Due brani i cui titoli si sanificano allo stesso modo (`Uguale?` e `Uguale*`) | Entrambi con suffisso stabile derivato dall'ID | ✅ 2026-08-23 — `Uguale_ [60546bac].mp3` e `Uguale_ [7183604b].mp3`, corrispondenti ai file cifrati omonimi in `files/tracks` |
| TC-18 | Superficie di attacco | Metodi di scrittura; token errato; percorso inesistente; `Depth: infinity` | `405` sui metodi di scrittura; `404` sul token errato e sul percorso inesistente; `403` + `propfind-finite-depth` | ✅ 2026-08-23 — `PUT/DELETE/MKCOL/MOVE/COPY/LOCK/UNLOCK/PROPPATCH` → tutti `405`; token errato → `404`; `Depth: infinity` → `403` con `<D:propfind-finite-depth/>` |
| TC-18b | DEK non sbloccata → `503` | Riavviare il processo senza autenticarsi e ritentare una `GET` | `503`, mai byte cifrati | ⏳ non verificabile su emulatore (`autoUnlockForDebug` sblocca la DEK da solo) — da fare su telefono |
| TC-19 | Chiusura durante il pull | Avviare un `copy` rallentato e uscire dalla schermata | Vedi nota "Comportamento alla chiusura" | ✅ 2026-08-23 — nuove connessioni rifiutate **subito**, la risposta già in volo arriva a termine, nessuna richiesta successiva servita |
| TC-20 | rsync vero via mount | `rclone mount --read-only` + `rsync -av --ignore-existing` | Mount con l'albero; rsync completa; secondo giro non ricopia nulla | ✅ 2026-08-23 — 8 file / 7.541.131 byte, secondo giro 0 file, md5 identici agli originali |
| TC-21 | **Throughput** | Cronometrare la `GET` di un file grande | ≥ **20 MB/s**. Intorno a **2 MB/s** significa che si è finiti nella trappola AES-GCM di `CLAUDE.md`: verificare che la `GET` passi da `CryptoManager.decryptTo` e **non** da `CipherInputStream`/`decryptingStream` | ✅ 2026-08-23 — 7,2 MB in 0,054–0,072 s = **97–123 MB/s** (loopback: è il limite della decifratura, non della rete) |
| TC-22 | Coesistenza con gli altri server | Aprire "Ricevi via Wi-Fi" (8080-8090) e "Esporta su PC" | Nessun conflitto di porta | ✅ 2026-08-23 — upload su 8080, WebDAV su 8101. Nota: le due schermate non possono essere aperte insieme (ciascuna ferma il proprio server all'uscita), quindi i range disgiunti proteggono dai socket in `TIME_WAIT` |
| TC-23 | Schermo acceso | Avviare un pull lungo e non toccare il telefono | Lo schermo resta acceso finché la schermata è aperta; il pull non si interrompe | ⏳ da fare su telefono |
| TC-24 | Libreria vuota | Aprire la schermata con zero brani e `rclone lsl player:` | Nessun errore, root valida e vuota | ⏳ da fare (il percorso è coperto dallo unit test `una libreria vuota produce una root senza figli`) |
| TC-25 | Cache a 60 s | Far sparire il file cifrato di un brano senza chiudere la schermata, poi `GET` sul suo percorso | `404`: la `GET` verifica il file su disco e non si fida della cache (R10); il `PROPFIND` sull'album lo omette | ✅ 2026-08-23 — `rm files/tracks/<id>` via `run-as`: `GET` → `404`, `PROPFIND` sull'album elenca solo la collection |

## Comportamento alla chiusura della schermata (TC-19)

Misurato con un download rallentato a 50 KB/s su un file da 7,2 MB (≈144 s), chiudendo la
schermata a t=6 s:

- **nuove connessioni**: rifiutate immediatamente (a t=8 s il `PROPFIND` non ottiene risposta);
- **risposta già in volo**: prosegue e consegna tutti i 7.201.760 byte;
- **richieste successive**, anche riusando la connessione: non servite.

È il comportamento di `engine.stop(100, 500)`, lo stesso già adottato da `UploadServer` e
`TransferServer`. **L'attesa iniziale del piano — "rclone fallisce con un errore di rete pulito" —
era sbagliata**: il file in corso arriva intero, e solo i successivi falliscono. Nella pratica è
preferibile, perché chiudere la schermata non lascia un file troncato a destinazione; la garanzia
"il server vive finché la schermata è aperta" resta valida per tutto ciò che non è già in volo.

## Esecuzione del 2026-08-23 — emulatore

**Setup.** `Emulator_x86_64` (API 34, `sdk_gphone64_x86_64`), APK debug, DEK sbloccata da
`autoUnlockForDebug`. Libreria popolata con 8 brani generati con `ffmpeg` e caricati tramite la
schermata "Ricevi via Wi-Fi" (`POST /{token}/upload`), scelti per coprire i casi sporchi:

| File | Artista | Album | Titolo | Traccia | Cosa esercita |
|---|---|---|---|---|---|
| 01 | Fabrizio De André | Creuza de mä | Crêuza de mä | `1/8` | accenti in tutti e tre i segmenti, `NN` da `n/tot` |
| 02 | Simon & Garfunkel | Bookends | America | `3` | `&` nell'artista |
| 03 | AC/DC | Back in Black | Hells Bells | `2` | `/` nell'artista |
| 04, 05 | Test | Omonimi | `Uguale?` / `Uguale*` | — | **collisione**: due titoli diversi che si sanificano allo stesso modo |
| 06 | Test | Vari | Senza numero | — | assenza del tag traccia |
| 07 | Test | Lungo | Traccia lunga | `1` | 7,2 MB, misura di throughput |
| 08 | Test | Formati | Lossless | `1` | `.flac`, estensione originale |

I casi 04/05 meritano una nota: la dedup all'import è su `(title, artist, album)`, quindi due
brani con **gli stessi identici tag** non possono coesistere in libreria. La collisione di nomi si
raggiunge invece con titoli *diversi* che collassano sullo stesso nome file dopo la
sanitizzazione, che è esattamente il caso reale.

**Non coperto dall'emulatore** e rimandato al telefono: TC-18b (la DEK si sblocca da sola in
debug su emulatore), TC-23 (schermo acceso), e una misura di throughput sulla Wi-Fi vera — quella
qui riportata è sul loopback e misura la decifratura, non la rete.

## Misure da annotare

- Versione di `rclone` usata: **v1.73.3** (`rclone version`)
- Modello e versione Android: emulatore `sdk_gphone64_x86_64` — *da ripetere su telefono*: ______
- Brani in libreria e dimensione totale esposta: **8 brani · 7 MB**
- Durata del primo pull: **0,2 s** · throughput: **~36 MB/s** (loopback)
- Durata del secondo pull (a destinazione piena): **0,1 s** · byte trasferiti: **0 B**
- Throughput della sola `GET` su file da 7,2 MB: **97–123 MB/s**
