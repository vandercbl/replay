package com.example.replay30

import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentValues
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.net.wifi.WifiManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.net.ServerSocket
import java.net.Socket
import java.net.Inet4Address
import java.net.NetworkInterface
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Replay30 prototype. Rotates 5-second MP4 segments and preserves the recent segment window.
 * Maintains a rolling set of short MP4 segments and joins the most recent 30/60 seconds on demand.
 */
class MainActivity : ComponentActivity() {
    private lateinit var mainRoot: LinearLayout
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var replayButton: Button
    private lateinit var toggleButton: Button
    private lateinit var remoteInfo: TextView
    private lateinit var executor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())
    private val segmentMs = 5_000L
    private val keepSegments = 16 // about 80 seconds, enough for a 60-second replay
    private val segments = ArrayDeque<File>()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRunning = false
    private var segmentStartedAt = 0L
    private var replayBusy = false
    private var replayRequested = false
    private var replaySeconds = 30
    private var remoteServer: ReplayHttpServer? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) startCamera()
        else toast("A permissão de câmera é necessária para gravar.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = Executors.newSingleThreadExecutor()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        remoteServer = ReplayHttpServer(this) { seconds -> runOnUiThread { saveReplay(seconds) } }.also { it.start() }
        if (hasPermissions()) startCamera() else permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    private fun buildUi() {
        mainRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val title = TextView(this).apply {
            text = "Replay30"
            textSize = 25f
            setTextColor(0xFF111111.toInt())
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Preparando câmera…"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 12)
        }
        preview = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        replayButton = Button(this).apply {
            text = "SALVAR REPLAY — 30 s"
            textSize = 17f
            isEnabled = false
            setOnClickListener { saveReplay(30) }
        }
        toggleButton = Button(this).apply {
            text = "Parar captura"
            setOnClickListener { if (isRunning) stopLoop() else startLoop() }
        }
        mainRoot.addView(title)
        mainRoot.addView(status)
        mainRoot.addView(preview)
        mainRoot.addView(replayButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainRoot.addView(Button(this).apply { text = "SALVAR REPLAY — 60 s"; setOnClickListener { saveReplay(60) } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainRoot.addView(toggleButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        remoteInfo = TextView(this).apply { textSize = 12f; setTextColor(0xFF555555.toInt()); setPadding(0, 8, 0, 0) }
        mainRoot.addView(remoteInfo)
        mainRoot.addView(Button(this).apply {
            text = "CONTROLE REMOTO — QR CODE"
            textSize = 16f
            setOnClickListener { showQrScreen() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(mainRoot)
    }

    private fun showQrScreen() {
        val url = controlUrl()
        if (url.isBlank()) {
            toast("Não consegui identificar o IP da rede. Conecte o celular a uma rede Wi-Fi e tente novamente.")
            return
        }
        val qrBitmap = try {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 720, 720)
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                for (x in 0 until matrix.width) {
                    for (y in 0 until matrix.height) {
                        bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
            }
        } catch (e: Exception) {
            toast("Não foi possível gerar o QR Code: ${e.message}")
            return
        }

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }
        screen.addView(TextView(this).apply {
            text = "Controle remoto Replay30"
            textSize = 24f
            setTextColor(0xFF111111.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        screen.addView(TextView(this).apply {
            text = "Conecte os dois celulares à mesma rede Wi-Fi. No outro celular, abra a câmera e leia este QR Code."
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 12)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        screen.addView(ImageView(this).apply {
            setImageBitmap(qrBitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "QR Code para abrir o controle remoto Replay30"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        screen.addView(TextView(this).apply {
            text = url
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        screen.addView(Button(this).apply {
            text = "VOLTAR À CÂMERA"
            setOnClickListener { setContentView(mainRoot) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(screen)
    }

    private fun controlUrl(): String {
        val wifiIp = try {
            val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
            val n = wm.dhcpInfo.ipAddress
            if (n == 0) null else "${n and 255}.${(n shr 8) and 255}.${(n shr 16) and 255}.${(n shr 24) and 255}"
        } catch (_: Exception) { null }
        val ip = wifiIp ?: try {
            java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap")) 0 else 1 }
                .flatMap { java.util.Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress != null }
                ?.hostAddress
        } catch (_: Exception) { null }
        return if (ip == null) "" else "http://$ip:8765"
    }

    private fun hasPermissions() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val previewUseCase = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.FHD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, videoCapture)
                startLoop()
            } catch (e: Exception) {
                status.text = "Não foi possível iniciar a câmera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun clipsDir(): File = File(getExternalFilesDir(null), "segments").apply { mkdirs() }

    private fun startLoop() {
        val capture = videoCapture ?: return
        if (isRunning) return
        isRunning = true
        replayButton.isEnabled = false
        toggleButton.text = "Parar captura"
        status.text = "Iniciando buffer… aguarde alguns segundos"
        startNextSegment(capture)
    }

    private fun startNextSegment(capture: VideoCapture<Recorder>) {
        if (!isRunning) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(clipsDir(), "seg_$stamp.mp4")
        val options = FileOutputOptions.Builder(file).build()
        var pending = capture.output.prepareRecording(this, options)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        segmentStartedAt = System.currentTimeMillis()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    status.text = "Buffer ativo · 1080p · últimos ~30 s"
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        file.delete()
                        runOnUiThread { status.text = "Falha na gravação (${event.error})" }
                    } else {
                        synchronized(segments) { segments.addLast(file) }
                        trimOldSegments()
                    }
                    recording = null
                    if (replayRequested) {
                        replayRequested = false
                        exportReplay()
                    }
                    if (isRunning) handler.postDelayed({ startNextSegment(capture) }, 120)
                    runOnUiThread { replayButton.isEnabled = isRunning && segments.size >= 2 && !replayBusy }
                }
            }
        }
        handler.postDelayed({
            if (isRunning && recording != null) {
                recording?.stop()
            }
        }, segmentMs)
    }

    private fun trimOldSegments() {
        synchronized(segments) {
            while (segments.size > keepSegments) {
                val old = segments.removeFirst()
                // Only delete after it is older than the rolling window.
                old.delete()
            }
        }
    }

    private fun saveReplay(seconds: Int = 30) {
        if (replayBusy) return
        if (!isRunning || recording == null) {
            toast("Inicie a captura e aguarde o buffer.")
            return
        }
        replayBusy = true
        replaySeconds = seconds
        replayRequested = true
        replayButton.isEnabled = false
        status.text = "Fechando segmento e salvando replay…"
        // Finalize the current segment first so we never copy an MP4 while it is still being written.
        recording?.stop()
    }

    private fun exportReplay() {
        val needed = (replaySeconds / 5) + 2
        val snapshot = synchronized(segments) { segments.toList().takeLast(needed) }
        if (snapshot.size < 2) {
            runOnUiThread {
                replayBusy = false
                replayButton.isEnabled = isRunning
                status.text = "Buffer ainda curto; aguarde mais alguns segundos."
            }
            return
        }
        executor.execute {
            try {
                val outDir = File(getExternalFilesDir(null), "replays").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val output = File(outDir, "Replay30_${replaySeconds}s_$stamp.mp4")
                val joined = Mp4Joiner.join(snapshot, output, replaySeconds * 1_000_000L)
                val galleryUri = if (joined) publishVideoToGallery(output) else null
                if (joined && galleryUri != null) output.delete()
                runOnUiThread {
                    replayBusy = false
                    replayButton.isEnabled = isRunning && segments.size >= 2
                    status.text = when {
                        !joined -> "Não foi possível montar o MP4; tente novamente."
                        galleryUri != null -> "Vídeo salvo na galeria: Movies/Replay30/${output.name}"
                        else -> "Vídeo montado, mas não foi possível enviá-lo à galeria. Arquivo local: ${output.absolutePath}"
                    }
                    toast(when {
                        !joined -> "Falha ao montar MP4. O aparelho pode precisar de ajustes de compatibilidade."
                        galleryUri != null -> "Replay salvo na galeria: ${output.name}"
                        else -> "Replay criado, mas não apareceu na galeria. O arquivo ficou no armazenamento do app."
                    })
                }
            } catch (e: Exception) {
                runOnUiThread {
                    replayBusy = false
                    replayButton.isEnabled = isRunning
                    status.text = "Erro ao salvar replay: ${e.message}"
                }
            }
        }
    }


    /** Publishes a completed MP4 to the user's Movies/Replay30 gallery folder on Android 10+. */
    private fun publishVideoToGallery(source: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Replay30")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri, "w")?.use { outputStream ->
                source.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
            } ?: throw java.io.IOException("Não foi possível abrir o arquivo de destino")
            val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun stopLoop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        recording?.stop()
        recording = null
        toggleButton.text = "Retomar captura"
        replayButton.isEnabled = false
        status.text = "Captura parada"
    }

    fun setRemoteText(text: String) { if (::remoteInfo.isInitialized) remoteInfo.text = text }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Alguns controles Bluetooth se apresentam como teclas de volume/câmera.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) {
            saveReplay(30)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        remoteServer?.stopServer()
        stopLoop()
        executor.shutdown()
        super.onDestroy()
    }
}


/** Muxes encoded samples from sequential MP4 segments into one MP4 without re-encoding. */
private object Mp4Joiner {
    fun join(files: List<File>, output: File, limitUs: Long): Boolean {
        if (files.isEmpty()) return false
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        return try {
            files.forEach { f -> extractors.add(MediaExtractor().apply { setDataSource(f.absolutePath) }) }
            val first = extractors.first()
            if (first.trackCount == 0) return false
            val formats = (0 until first.trackCount).map { first.getTrackFormat(it) }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTracks = formats.map { muxer.addTrack(it) }
            val maps = mutableListOf<IntArray>()
            maps.add(outputTracks.toIntArray())
            for (ex in extractors.drop(1)) {
                val map = IntArray(ex.trackCount) { -1 }
                for (i in 0 until ex.trackCount) {
                    val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
                    map[i] = formats.indexOfFirst { it.getString(android.media.MediaFormat.KEY_MIME) == mime }
                    if (map[i] < 0) return false
                }
                maps.add(map)
            }
            muxer.start()
            val buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var offsetUs = 0L
            for ((idx, ex) in extractors.withIndex()) {
                for (t in 0 until ex.trackCount) ex.selectTrack(t)
                var maxPts = 0L
                while (true) {
                    buffer.clear()
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    val pts = ex.sampleTime
                    val shifted = offsetUs + pts
                    if (shifted > limitUs) break
                    info.set(0, size, shifted, ex.sampleFlags)
                    muxer.writeSampleData(maps[idx][ex.sampleTrackIndex], buffer, info)
                    if (pts > maxPts) maxPts = pts
                    ex.advance()
                }
                offsetUs += maxPts + 1
                if (offsetUs >= limitUs) break
            }
            muxer.stop()
            output.exists() && output.length() > 1024
        } catch (_: Exception) {
            false
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            extractors.forEach { try { it.release() } catch (_: Exception) {} }
        }
    }
}

/** Tiny HTTP control page for another phone on the same Wi-Fi. */
private class ReplayHttpServer(private val activity: MainActivity, private val onReplay: (Int) -> Unit) {
    @Volatile private var running = true
    private var server: ServerSocket? = null
    fun start() {
        Thread {
            try {
                server = ServerSocket(8765)
                val ip = try {
                    val wm = activity.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
                    val n = wm.dhcpInfo.ipAddress
                    if (n == 0) "IP-DO-CELULAR" else "${n and 255}.${(n shr 8) and 255}.${(n shr 16) and 255}.${(n shr 24) and 255}"
                } catch (_: Exception) { "IP-DO-CELULAR" }
                activity.runOnUiThread { activity.setRemoteText("Controle remoto pronto · mesma rede Wi-Fi · toque em QR Code") }
                while (running) {
                    val client = server?.accept() ?: break
                    Thread { handle(client) }.start()
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
    }
    private fun handle(client: Socket) {
        client.use { s ->
            try {
                val reader = s.getInputStream().bufferedReader()
                val req = reader.readLine() ?: return
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                val path = req.split(" ").getOrNull(1) ?: "/"
                val body: String
                val type: String
                if (path.startsWith("/replay")) {
                    val seconds = if (path.contains("seconds=60")) 60 else 30
                    onReplay(seconds)
                    type = "application/json"
                    body = """{"ok":true,"seconds":$seconds,"message":"Replay solicitado"}"""
                } else {
                    type = "text/html; charset=utf-8"
                    body = """<!doctype html><html lang="pt-BR"><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8"><title>Replay30</title><style>body{font-family:system-ui,sans-serif;background:#101827;color:white;text-align:center;padding:24px;max-width:520px;margin:auto}button{width:100%;padding:24px 12px;margin:12px 0;font-size:21px;font-weight:700;background:#16a34a;color:white;border:0;border-radius:14px}#msg{min-height:2em;color:#cbd5e1}</style><h1>Replay30</h1><p>Controle remoto conectado à rede local</p><button onclick="replay(30)">Salvar últimos 30 segundos</button><button onclick="replay(60)">Salvar últimos 60 segundos</button><p id="msg" role="status"></p><script>async function replay(seconds){const m=document.getElementById('msg');m.textContent='Enviando comando…';try{const r=await fetch('/replay?seconds='+seconds);if(!r.ok)throw new Error();m.textContent='Comando enviado: replay de '+seconds+' s. Confira o celular que está gravando.';}catch(e){m.textContent='Não foi possível enviar. Verifique se os celulares estão na mesma rede Wi-Fi.';}}</script></html>"""
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val out = s.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(bytes); out.flush()
            } catch (_: Exception) { }
        }
    }
    fun stopServer() { running = false; try { server?.close() } catch (_: Exception) {} }
}
