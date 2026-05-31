package it.agoldoni.player.domain.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import it.agoldoni.player.data.local.dao.TrackDao
import it.agoldoni.player.domain.CryptoManager
import it.agoldoni.player.domain.ImportTrackUseCase
import it.agoldoni.player.domain.MetadataExtractor
import it.agoldoni.player.util.NetworkUtils
import it.agoldoni.player.util.supportedExtensionFromPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UploadServer"
private val PORT_RANGE = 8080..8090
private const val COPY_BUFFER_SIZE = 64 * 1024

/**
 * Stato del server di ricezione Wi-Fi, osservato dalla UI.
 */
sealed interface UploadServerState {
    data object Idle : UploadServerState
    data object Starting : UploadServerState

    /** Server attivo: [url] è l'indirizzo (con token) da aprire dal PC. */
    data class Running(
        val url: String,
        val added: Int,
        val skipped: Int,
        val errors: Int,
        val lastFileName: String?
    ) : UploadServerState

    data class Failed(val message: String) : UploadServerState
}

/**
 * Espone un piccolo server HTTP sulla rete locale per ricevere brani da una
 * sorgente esterna (es. un PC) senza alcun software lato PC: basta un browser.
 *
 * Flusso:
 * 1. all'avvio genera un token casuale e si mette in ascolto su 0.0.0.0
 * 2. tutte le route sono montate sotto `/{token}` (capability URL): richieste
 *    senza il token corretto → 404
 * 3. `GET /{token}` serve una pagina con drag & drop
 * 4. `POST /{token}/upload?name=<file>` riceve **un singolo file** come body
 *    grezzo (niente multipart) e lo importa riusando la pipeline esistente
 *    (estrai metadati → dedup per (title, artist, album) → [ImportTrackUseCase])
 *
 * Scelta del body grezzo + lettura suspending del [ByteReadChannel]: il parsing
 * multipart con `streamProvider()` bloccante su engine CIO è patologicamente
 * lento (~1 MB/s) e va in stallo con `Expect: 100-continue`. Leggere il canale
 * a blocchi è veloce e robusto. La pagina invia un file per richiesta, così da
 * mostrare il progresso per-file.
 *
 * Il server vive finché la schermata "Ricevi via Wi-Fi" è aperta (nessun
 * foreground service, coerente con la sync FTP che opera solo in primo piano).
 */
@Singleton
class UploadServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataExtractor: MetadataExtractor,
    private val importTrackUseCase: ImportTrackUseCase,
    private val trackDao: TrackDao,
    private val cryptoManager: CryptoManager
) {
    private val _state = MutableStateFlow<UploadServerState>(UploadServerState.Idle)
    val state: StateFlow<UploadServerState> = _state.asStateFlow()

    private var engine: ApplicationEngine? = null

    // Serializza dedup + import per evitare race su (title, artist, album)
    // quando il browser invia più file in rapida successione.
    private val importMutex = Mutex()

    private val tempDir: File
        get() = File(context.cacheDir, "upload_temp").also { it.mkdirs() }

    private enum class FileStatus { ADDED, SKIPPED, UNSUPPORTED, ERROR }

    /** Avvia il server e pubblica l'URL nello stato. Idempotente. */
    fun start() {
        if (engine != null) return
        _state.value = UploadServerState.Starting

        val ip = NetworkUtils.getLocalIpAddress()
        if (ip == null) {
            _state.value = UploadServerState.Failed(
                "Nessuna connessione Wi-Fi rilevata. Connetti il telefono alla stessa rete del PC."
            )
            return
        }

        val port = NetworkUtils.firstFreePort(PORT_RANGE)
        if (port == null) {
            _state.value = UploadServerState.Failed("Nessuna porta disponibile nell'intervallo $PORT_RANGE.")
            return
        }

        val token = NetworkUtils.generateToken()
        try {
            engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                uploadModule(token)
            }.start(wait = false)
        } catch (e: Exception) {
            Log.e(TAG, "Avvio server fallito", e)
            engine = null
            _state.value = UploadServerState.Failed("Avvio del server fallito: ${e.message}")
            return
        }

        _state.value = UploadServerState.Running(
            url = "http://$ip:$port/$token",
            added = 0,
            skipped = 0,
            errors = 0,
            lastFileName = null
        )
    }

    /** Ferma il server e ripristina lo stato Idle (mantiene eventuale Failed). */
    fun stop() {
        engine?.let { runCatching { it.stop(100, 500) } }
        engine = null
        if (_state.value !is UploadServerState.Failed) {
            _state.value = UploadServerState.Idle
        }
    }

    private fun Application.uploadModule(token: String) {
        routing {
            get("/{token}") {
                if (call.parameters["token"] != token) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.respondText(uploadPageHtml(), ContentType.Text.Html)
            }

            post("/{token}/upload") {
                if (call.parameters["token"] != token) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                val dek = cryptoManager.sessionDek
                if (dek == null) {
                    call.respondText(
                        fileResultJson("upload", "error"),
                        ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable
                    )
                    return@post
                }

                val name = call.request.queryParameters["name"] ?: "upload"
                val ext = supportedExtensionFromPath(name)
                if (ext == null) {
                    // Drena il body per non lasciare il client in attesa, poi rifiuta.
                    runCatching { call.receiveChannel().discard(Long.MAX_VALUE) }
                    val status = recordOutcome(FileStatus.UNSUPPORTED, name)
                    call.respondText(fileResultJson(name, status.jsonValue), ContentType.Application.Json)
                    return@post
                }

                val channel = call.receiveChannel()
                val status = receiveAndImport(channel, name, ext, dek)
                call.respondText(fileResultJson(name, status.jsonValue), ContentType.Application.Json)
            }
        }
    }

    private suspend fun receiveAndImport(
        channel: ByteReadChannel,
        name: String,
        ext: String,
        dek: SecretKey
    ): FileStatus {
        val temp = File(tempDir, "${UUID.randomUUID()}.$ext")
        try {
            withContext(Dispatchers.IO) {
                temp.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read == -1) break
                        if (read > 0) out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Errore ricezione di $name", e)
            temp.delete()
            return recordOutcome(FileStatus.ERROR, name)
        }

        val candidate = withContext(Dispatchers.IO) { metadataExtractor.extract(Uri.fromFile(temp)) }
        if (candidate == null) {
            temp.delete()
            return recordOutcome(FileStatus.ERROR, name)
        }

        val status = importMutex.withLock {
            val existing = trackDao.getTrackByMetadata(
                title = candidate.title,
                artist = candidate.artist,
                album = candidate.album
            )
            if (existing != null) {
                temp.delete()
                FileStatus.SKIPPED
            } else {
                val imported = try {
                    importTrackUseCase.invoke(temp, dek, deleteSource = true)
                } catch (e: Exception) {
                    Log.w(TAG, "Errore import di $name", e)
                    temp.delete()
                    false
                }
                if (imported) FileStatus.ADDED else FileStatus.ERROR
            }
        }
        return recordOutcome(status, name)
    }

    /** Aggiorna atomicamente i contatori nello stato Running. */
    private fun recordOutcome(status: FileStatus, fileName: String): FileStatus {
        _state.update { current ->
            if (current is UploadServerState.Running) {
                current.copy(
                    added = current.added + if (status == FileStatus.ADDED) 1 else 0,
                    skipped = current.skipped + if (status == FileStatus.SKIPPED) 1 else 0,
                    errors = current.errors + if (status == FileStatus.ERROR || status == FileStatus.UNSUPPORTED) 1 else 0,
                    lastFileName = fileName
                )
            } else {
                current
            }
        }
        return status
    }

    private val FileStatus.jsonValue: String get() = name.lowercase()

    private fun fileResultJson(name: String, status: String): String =
        """{"name":"${jsonEscape(name)}","status":"$status"}"""

    private fun jsonEscape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    private fun uploadPageHtml(): String = """
<!DOCTYPE html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Player — Carica brani</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: system-ui, sans-serif; max-width: 640px; margin: 0 auto; padding: 24px; line-height: 1.5; }
  h1 { font-size: 1.4rem; }
  #drop { border: 2px dashed #888; border-radius: 12px; padding: 40px 16px; text-align: center; color: #888; transition: .15s; }
  #drop.over { border-color: #2e7d32; color: #2e7d32; background: rgba(46,125,50,.08); }
  .row { display: flex; gap: 12px; align-items: center; margin: 16px 0; flex-wrap: wrap; }
  button { font-size: 1rem; padding: 10px 20px; border-radius: 8px; border: none; background: #2e7d32; color: #fff; cursor: pointer; }
  button:disabled { opacity: .5; cursor: default; }
  ul { list-style: none; padding: 0; }
  li { padding: 8px 12px; border-radius: 8px; margin-bottom: 6px; background: rgba(128,128,128,.12); display: flex; justify-content: space-between; gap: 12px; }
  .added { color: #2e7d32; } .skipped { color: #ef6c00; } .error, .unsupported { color: #c62828; }
  .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .progress { margin: 8px 0 16px; }
  .pname { font-size: .95rem; margin-bottom: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .bar { height: 10px; border-radius: 6px; background: rgba(128,128,128,.25); overflow: hidden; }
  .fill { height: 100%; width: 0; background: #2e7d32; transition: width .1s; }
  .pinfo { font-size: .8rem; color: #888; margin-top: 4px; }
</style>
</head>
<body>
  <h1>Carica brani su Player</h1>
  <p>Trascina qui i file <b>.mp3</b> o <b>.flac</b>, oppure selezionali. Verranno importati nel telefono.</p>
  <div id="drop">Trascina i file qui</div>
  <div class="row">
    <input type="file" id="files" multiple accept=".mp3,.flac,audio/*">
    <button id="send" disabled>Carica</button>
  </div>
  <div id="progress" class="progress" hidden>
    <div class="pname" id="pname"></div>
    <div class="bar"><div class="fill" id="fill"></div></div>
    <div class="pinfo" id="pinfo"></div>
  </div>
  <ul id="results"></ul>
<script>
  var dropEl = document.getElementById('drop');
  var input = document.getElementById('files');
  var sendBtn = document.getElementById('send');
  var resultsEl = document.getElementById('results');
  var progressEl = document.getElementById('progress');
  var pnameEl = document.getElementById('pname');
  var fillEl = document.getElementById('fill');
  var pinfoEl = document.getElementById('pinfo');
  var chosen = [];

  var uploadUrl = location.pathname.replace(/\/+${'$'}/, '') + '/upload';
  var labels = { added: 'aggiunto', skipped: 'già presente', unsupported: 'formato non supportato', error: 'errore' };
  var MAX_RETRIES = 3;

  function setFiles(list) {
    chosen = Array.prototype.slice.call(list);
    sendBtn.disabled = chosen.length === 0;
    dropEl.textContent = chosen.length ? (chosen.length + ' file pronti') : 'Trascina i file qui';
  }

  function addRow(name, status) {
    var li = document.createElement('li');
    var n = document.createElement('span'); n.className = 'name'; n.textContent = name;
    var s = document.createElement('span'); s.className = status; s.textContent = labels[status] || status;
    li.appendChild(n); li.appendChild(s); resultsEl.appendChild(li);
  }

  input.addEventListener('change', function () { setFiles(input.files); });
  ['dragenter','dragover'].forEach(function (ev) {
    dropEl.addEventListener(ev, function (e) { e.preventDefault(); dropEl.classList.add('over'); });
  });
  ['dragleave','drop'].forEach(function (ev) {
    dropEl.addEventListener(ev, function (e) { e.preventDefault(); dropEl.classList.remove('over'); });
  });
  dropEl.addEventListener('drop', function (e) { setFiles(e.dataTransfer.files); });

  // Carica un file con barra di avanzamento e retry automatico su errore di rete.
  function uploadOne(f, index, total) {
    return new Promise(function (resolve) {
      var attempt = 0;
      function attemptUpload() {
        attempt++;
        var xhr = new XMLHttpRequest();
        xhr.open('POST', uploadUrl + '?name=' + encodeURIComponent(f.name));
        xhr.timeout = 0;
        xhr.upload.onprogress = function (e) {
          if (!e.lengthComputable) return;
          var pct = Math.round(e.loaded / e.total * 100);
          fillEl.style.width = pct + '%';
          var info = (index + 1) + '/' + total + ' • ' + pct + '%';
          if (attempt > 1) info += ' • tentativo ' + attempt + '/' + MAX_RETRIES;
          pinfoEl.textContent = info;
        };
        xhr.onload = function () {
          if (xhr.status >= 200 && xhr.status < 300) {
            var data = null;
            try { data = JSON.parse(xhr.responseText); } catch (err) {}
            fillEl.style.width = '100%';
            resolve(data || { name: f.name, status: 'error' });
          } else {
            retryOrFail();
          }
        };
        xhr.onerror = retryOrFail;
        xhr.ontimeout = retryOrFail;
        xhr.send(f);
      }
      function retryOrFail() {
        if (attempt < MAX_RETRIES) {
          fillEl.style.width = '0%';
          pinfoEl.textContent = (index + 1) + '/' + total + ' • connessione persa, nuovo tentativo ' + (attempt + 1) + '/' + MAX_RETRIES + '…';
          setTimeout(attemptUpload, 1000);
        } else {
          resolve({ name: f.name, status: 'error' });
        }
      }
      attemptUpload();
    });
  }

  sendBtn.addEventListener('click', function () {
    if (!chosen.length) return;
    sendBtn.disabled = true;
    var queue = chosen.slice();
    var total = queue.length;
    var index = 0;
    progressEl.hidden = false;
    function next() {
      if (index >= total) {
        progressEl.hidden = true;
        dropEl.textContent = 'Fatto! Trascina altri file qui';
        setFiles([]); input.value = '';
        return;
      }
      var f = queue[index];
      pnameEl.textContent = f.name;
      fillEl.style.width = '0%';
      pinfoEl.textContent = (index + 1) + '/' + total + ' • in attesa…';
      uploadOne(f, index, total).then(function (data) {
        addRow(data.name || f.name, data.status || 'error');
        index++;
        next();
      });
    }
    next();
  });
</script>
</body>
</html>
""".trimIndent()
}
