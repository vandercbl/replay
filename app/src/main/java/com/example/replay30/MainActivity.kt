package com.example.replay30

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.media.MediaCodecInfo
import android.os.HandlerThread
import android.content.pm.PackageManager
import android.content.ContentValues
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
import android.view.Surface
import android.net.wifi.WifiManager
import android.media.MediaCodec
import android.media.MediaMuxer
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
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
import android.view.TextureView
import android.util.Size
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
 * Replay30 continuous capture implementation.
 * Camera2 sends frames continuously to a MediaCodec surface. Encoded H.264 access units are
 * held in a bounded rolling buffer; saving a replay muxes buffered samples without restarting camera.
 */
class MainActivity : ComponentActivity() {
    private lateinit var mainRoot: LinearLayout
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var replayButton: Button
    private lateinit var toggleButton: Button
    private lateinit var remoteInfo: TextView
    private lateinit var executor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())
    private var remoteServer: ReplayHttpServer? = null
    private var isRunning = false
    private var replayBusy = false
    private var replaySeconds = 30
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var drainThread: Thread? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioDrainThread: Thread? = null
    private var audioCaptureThread: Thread? = null
    private var audioFormat: MediaFormat? = null
    private val audioSamples = ArrayDeque<EncodedSample>()
    private var audioBufferedBytes = 0L
    private var audioStartPtsUs = 0L
    private var audioFramesCaptured = 0L
    private val audioSampleRate = 48_000
    private val maxAudioBufferBytes = 24L * 1024L * 1024L
    private var captureSize = Size(1920, 1080)
    private var rotationHint = 90
    private val bufferLock = Any()
    private val samples = ArrayDeque<EncodedSample>()
    private var videoFormat: MediaFormat? = null
    private var firstPtsUs = -1L
    private var lastPtsUs = 0L
    private var bufferedBytes = 0L
    private val maxBufferUs = 72_000_000L
    private val maxBufferBytes = 120L * 1024L * 1024L
    private val captureActive = AtomicBoolean(false)

    private data class EncodedSample(val ptsUs: Long, val flags: Int, val data: ByteArray, val isAudio: Boolean = false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true && result[Manifest.permission.RECORD_AUDIO] == true) {
            if (preview.isAvailable) startCamera() else preview.surfaceTextureListener = textureListener
        } else toast("Permita o acesso à câmera e ao microfone para gravar vídeo com áudio.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = Executors.newSingleThreadExecutor()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        remoteServer = ReplayHttpServer(this) { seconds -> runOnUiThread { saveReplay(seconds) } }.also { it.start() }
        if (hasRequiredPermissions()) {
            if (preview.isAvailable) startCamera() else preview.surfaceTextureListener = textureListener
        } else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun buildUi() {
        mainRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        mainRoot.addView(TextView(this).apply {
            text = "Replay30"; textSize = 25f; setTextColor(0xFF111111.toInt()); gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            text = "Preparando captura contínua…"; textSize = 14f; gravity = Gravity.CENTER; setPadding(0, 8, 0, 12)
        }
        preview = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        replayButton = Button(this).apply {
            text = "SALVAR REPLAY — 30 s"; textSize = 17f; isEnabled = false; setOnClickListener { saveReplay(30) }
        }
        toggleButton = Button(this).apply {
            text = "Parar captura"; setOnClickListener { if (isRunning) stopCapture() else startCamera() }
        }
        mainRoot.addView(status)
        mainRoot.addView(preview)
        mainRoot.addView(replayButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainRoot.addView(Button(this).apply { text = "SALVAR REPLAY — 60 s"; setOnClickListener { saveReplay(60) } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainRoot.addView(toggleButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        remoteInfo = TextView(this).apply { textSize = 12f; setTextColor(0xFF555555.toInt()); setPadding(0, 8, 0, 0) }
        mainRoot.addView(remoteInfo)
        mainRoot.addView(Button(this).apply {
            text = "CONTROLE REMOTO — QR CODE"; textSize = 16f; setOnClickListener { showQrScreen() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(mainRoot)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { if (hasRequiredPermissions()) startCamera() }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun showQrScreen() {
        val url = controlUrl()
        if (url.isBlank()) { toast("Não consegui identificar o IP da rede. Conecte o celular ao Wi-Fi e tente novamente."); return }
        val qrBitmap = try {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 720, 720)
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                for (x in 0 until matrix.width) for (y in 0 until matrix.height) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        } catch (e: Exception) { toast("Não foi possível gerar o QR Code: ${e.message}"); return }
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24,24,24,24); setBackgroundColor(Color.WHITE) }
        screen.addView(TextView(this).apply { text = "Controle remoto Replay30"; textSize = 24f; setTextColor(0xFF111111.toInt()); gravity = Gravity.CENTER })
        screen.addView(TextView(this).apply { text = "Conecte os dois celulares à mesma rede Wi-Fi e leia este QR Code no outro celular."; textSize = 16f; setTextColor(0xFF333333.toInt()); gravity = Gravity.CENTER; setPadding(0,16,0,12) })
        screen.addView(ImageView(this).apply { setImageBitmap(qrBitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "QR Code Replay30" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        screen.addView(TextView(this).apply { text = url; textSize = 14f; setTextColor(0xFF333333.toInt()); gravity = Gravity.CENTER; setPadding(0,12,0,12) })
        screen.addView(Button(this).apply { text = "VOLTAR À CÂMERA"; setOnClickListener { setContentView(mainRoot) } })
        setContentView(screen)
    }

    private fun controlUrl(): String {
        val wifiIp = try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val n = wm.dhcpInfo.ipAddress
            if (n == 0) null else "${n and 255}.${(n shr 8) and 255}.${(n shr 16) and 255}.${(n shr 24) and 255}"
        } catch (_: Exception) { null }
        val ip = wifiIp ?: try {
            java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence().filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap")) 0 else 1 }
                .flatMap { java.util.Collections.list(it.inetAddresses).asSequence() }.filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress != null }?.hostAddress
        } catch (_: Exception) { null }
        return if (ip == null) "" else "http://$ip:8765"
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasRequiredPermissions() = hasCameraPermission() && hasAudioPermission()

    @Suppress("DEPRECATION")
    private fun startCamera() {
        if (isRunning || !preview.isAvailable || !hasRequiredPermissions()) return
        try {
            cameraThread = HandlerThread("Replay30Camera").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id -> manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: manager.cameraIdList.first()
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            captureSize = sizes.filter { it.width >= 1280 && it.height >= 720 }.minByOrNull { kotlin.math.abs((it.width.toDouble()/it.height) - (16.0/9.0)) * 10000 + kotlin.math.abs(it.width*it.height - 1920*1080) } ?: Size(1920,1080)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            rotationHint = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) sensorOrientation else 0
            setupEncoder()
            val texture = preview.surfaceTexture ?: throw IllegalStateException("Preview indisponível")
            texture.setDefaultBufferSize(captureSize.width, captureSize.height)
            val previewSurface = Surface(texture)
            val encSurface = encoderSurface ?: throw IllegalStateException("Superfície do encoder indisponível")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        camera.createCaptureSession(listOf(previewSurface, encSurface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                cameraSession = session
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                        addTarget(previewSurface); addTarget(encSurface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30,30))
                                    }.build()
                                    session.setRepeatingRequest(request, null, cameraHandler)
                                    isRunning = true; captureActive.set(true)
                                    startDrainThread()
                                    startAudioCapture()
                                    runOnUiThread { status.text = "Captura contínua ativa · vídeo + áudio · sem reinícios periódicos"; toggleButton.text = "Parar captura" }
                                } catch (e: Exception) { runOnUiThread { status.text = "Falha ao iniciar captura de áudio/vídeo: ${e.message}" }; stopCapture() }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { runOnUiThread { status.text = "Falha ao configurar a câmera." }; stopCapture() }
                        }, cameraHandler)
                    } catch (e: Exception) { runOnUiThread { status.text = "Erro na sessão: ${e.message}" }; stopCapture() }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null; stopCapture() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null; runOnUiThread { status.text = "Erro da câmera: $error" }; stopCapture() }
            }, cameraHandler)
        } catch (e: Exception) { status.text = "Não foi possível iniciar: ${e.message}"; stopCapture() }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, captureSize.width, captureSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
        }
        synchronized(bufferLock) {
            samples.clear(); audioSamples.clear(); bufferedBytes = 0; audioBufferedBytes = 0
            videoFormat = null; audioFormat = null; firstPtsUs = -1L; lastPtsUs = 0L
        }
        setupAudioEncoder()
    }

    private fun setupAudioEncoder() {
        val minBuffer = AudioRecord.getMinBufferSize(audioSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IllegalStateException("Não foi possível inicializar o microfone (buffer $minBuffer)")
        val recordBufferBytes = maxOf(minBuffer * 2, 16_384)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, audioSampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, recordBufferBytes)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("O microfone não pôde ser inicializado")
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioEncoder = codec
            audioRecord = record
            audioFramesCaptured = 0L
        } catch (e: Exception) {
            try { codec.release() } catch (_: Exception) {}
            record.release()
            throw e
        }
    }

    private fun startAudioCapture() {
        val record = audioRecord ?: throw IllegalStateException("Microfone indisponível")
        val codec = audioEncoder ?: throw IllegalStateException("Encoder de áudio indisponível")
        audioFramesCaptured = 0L
        audioStartPtsUs = System.nanoTime() / 1_000L
        record.startRecording()
        audioDrainThread = Thread {
            val info = MediaCodec.BufferInfo()
            while (captureActive.get() || audioEncoder != null) {
                try {
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> if (!captureActive.get()) break
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(bufferLock) { audioFormat = codec.outputFormat }
                        else -> if (index >= 0) {
                            val out = codec.getOutputBuffer(index)
                            if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                out.position(info.offset); out.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size); out.get(bytes)
                                synchronized(bufferLock) {
                                    audioSamples.addLast(EncodedSample(info.presentationTimeUs, info.flags, bytes, true))
                                    audioBufferedBytes += bytes.size
                                    val cutoff = info.presentationTimeUs - maxBufferUs
                                    while (audioSamples.isNotEmpty() && (audioSamples.first().ptsUs < cutoff || audioBufferedBytes > maxAudioBufferBytes)) {
                                        audioBufferedBytes -= audioSamples.removeFirst().data.size
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                } catch (_: Exception) { if (captureActive.get()) Thread.sleep(5) else break }
            }
        }.apply { name = "Replay30AudioEncoderDrain"; isDaemon = true; start() }

        audioCaptureThread = Thread {
            val pcm = ByteArray(8_192)
            while (captureActive.get()) {
                try {
                    val count = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    var offset = 0
                    while (offset < count && captureActive.get()) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex < 0) { Thread.sleep(2); continue }
                        val input = codec.getInputBuffer(inputIndex) ?: continue
                        input.clear()
                        val toCopy = minOf(count - offset, input.remaining())
                        input.put(pcm, offset, toCopy)
                        val frames = toCopy / 2 // mono PCM16: 2 bytes per frame
                        val pts = audioStartPtsUs + audioFramesCaptured * 1_000_000L / audioSampleRate
                        codec.queueInputBuffer(inputIndex, 0, toCopy, pts, 0)
                        audioFramesCaptured += frames
                        offset += toCopy
                    }
                } catch (_: Exception) { if (captureActive.get()) Thread.sleep(5) }
            }
        }.apply { name = "Replay30AudioCapture"; isDaemon = true; start() }
    }

    private fun startDrainThread() {
        drainThread = Thread {
            val codec = encoder ?: return@Thread
            val info = MediaCodec.BufferInfo()
            while (captureActive.get()) {
                try {
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(bufferLock) { videoFormat = codec.outputFormat }
                        else -> if (index >= 0) {
                            val out = codec.getOutputBuffer(index)
                            if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                out.position(info.offset); out.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size); out.get(bytes)
                                synchronized(bufferLock) {
                                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 || samples.isNotEmpty()) {
                                        samples.addLast(EncodedSample(info.presentationTimeUs, info.flags, bytes, false)); bufferedBytes += bytes.size
                                        if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                                        lastPtsUs = info.presentationTimeUs
                                        trimBufferLocked()
                                    }
                                }
                                runOnUiThread { replayButton.isEnabled = isRunning && bufferedDurationLocked() >= 5_000_000L && !replayBusy }
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                } catch (_: Exception) { if (captureActive.get()) Thread.sleep(5) }
            }
        }.apply { name = "Replay30EncoderDrain"; isDaemon = true; start() }
    }

    private fun trimBufferLocked() {
        val cutoff = lastPtsUs - maxBufferUs
        while (samples.size > 1 && (samples.first().ptsUs < cutoff || bufferedBytes > maxBufferBytes)) {
            val first = samples.removeFirst(); bufferedBytes -= first.data.size
        }
        // Always begin the retained ring at a keyframe so any export can be decoded independently.
        while (samples.isNotEmpty() && samples.first().flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) {
            val first = samples.removeFirst(); bufferedBytes -= first.data.size
        }
    }

    private fun bufferedDurationLocked(): Long = synchronized(bufferLock) {
        if (samples.size < 2) 0L else samples.last().ptsUs - samples.first().ptsUs
    }

    private fun saveReplay(seconds: Int) {
        if (replayBusy) return
        if (!isRunning) { toast("Inicie a captura e aguarde o buffer."); return }
        replayBusy = true; replaySeconds = seconds; replayButton.isEnabled = false
        status.text = "Salvando replay de $seconds s sem interromper a captura…"
        executor.execute {
            try {
                val format: MediaFormat
                val aFormat: MediaFormat
                val chosen: List<EncodedSample>
                synchronized(bufferLock) {
                    format = videoFormat ?: throw IllegalStateException("Encoder de vídeo ainda está preparando o formato")
                    aFormat = audioFormat ?: throw IllegalStateException("Encoder de áudio ainda está preparando o formato; aguarde alguns segundos")
                    val allVideo = samples.toList()
                    val allAudio = audioSamples.toList()
                    if (allVideo.size < 2 || allAudio.isEmpty()) throw IllegalStateException("Buffer de áudio/vídeo ainda curto; aguarde alguns segundos")
                    val end = allVideo.last().ptsUs
                    val desiredStart = end - seconds * 1_000_000L
                    var startIndex = allVideo.indexOfLast { it.ptsUs <= desiredStart && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
                    if (startIndex < 0) startIndex = allVideo.indexOfFirst { it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
                    if (startIndex < 0) throw IllegalStateException("Aguardando keyframe para iniciar o replay")
                    val selectedVideo = allVideo.drop(startIndex)
                    val clipStart = selectedVideo.first().ptsUs
                    val clipEnd = selectedVideo.last().ptsUs
                    val selectedAudio = allAudio.filter { it.ptsUs >= clipStart && it.ptsUs <= clipEnd + 30_000L }
                    if (selectedAudio.isEmpty()) throw IllegalStateException("Ainda não há áudio no intervalo selecionado. Aguarde e tente novamente")
                    chosen = (selectedVideo + selectedAudio).sortedBy { it.ptsUs }
                }
                val outDir = File(getExternalFilesDir(null), "replays").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val output = File(outDir, "Replay30_${seconds}s_$stamp.mp4")
                writeReplayMp4(chosen, format, aFormat, output)
                val uri = publishVideoToGallery(output)
                if (uri != null) output.delete()
                runOnUiThread {
                    replayBusy = false; replayButton.isEnabled = isRunning
                    status.text = if (uri != null) "Replay salvo na galeria: Movies/Replay30/${output.name}" else "Replay criado, mas não foi possível publicar na galeria: ${output.absolutePath}"
                    toast(if (uri != null) "Replay salvo na galeria." else "Replay criado no armazenamento do app, mas não apareceu na galeria.")
                }
            } catch (e: Exception) {
                runOnUiThread { replayBusy = false; replayButton.isEnabled = isRunning; status.text = "Erro ao salvar replay: ${e.message}"; toast("Erro ao salvar replay: ${e.message}") }
            }
        }
    }

    private fun writeReplayMp4(clip: List<EncodedSample>, videoFmt: MediaFormat, audioFmt: MediaFormat, output: File) {
        val firstVideo = clip.firstOrNull { !it.isAudio && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
            ?: throw IllegalStateException("O trecho de vídeo não começa em keyframe")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            if (rotationHint == 90 || rotationHint == 180 || rotationHint == 270) muxer.setOrientationHint(rotationHint)
            val videoTrack = muxer.addTrack(videoFmt)
            val audioTrack = muxer.addTrack(audioFmt)
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(clip.maxOf { it.data.size }.coerceAtLeast(1024))
            val info = MediaCodec.BufferInfo()
            val base = firstVideo.ptsUs
            for (sample in clip.sortedBy { it.ptsUs }) {
                buffer.clear(); buffer.put(sample.data); buffer.flip()
                info.set(0, sample.data.size, (sample.ptsUs - base).coerceAtLeast(0L), sample.flags)
                muxer.writeSampleData(if (sample.isAudio) audioTrack else videoTrack, buffer, info)
            }
            muxer.stop()
        } finally { try { muxer.release() } catch (_: Exception) {} }
    }

    private fun publishVideoToGallery(source: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Replay30"); put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            contentResolver.openOutputStream(uri, "w")?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: throw java.io.IOException("Não foi possível abrir o destino")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null); uri
        } catch (_: Exception) { contentResolver.delete(uri, null, null); null }
    }

    private fun stopCapture() {
        isRunning = false; captureActive.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
        try { cameraSession?.stopRepeating() } catch (_: Exception) {}
        try { cameraSession?.close() } catch (_: Exception) {}
        cameraSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { encoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
        try { audioDrainThread?.join(250) } catch (_: Exception) {}
        try { audioCaptureThread?.join(250) } catch (_: Exception) {}
        audioDrainThread = null; audioCaptureThread = null
        try { cameraThread?.quitSafely() } catch (_: Exception) {}
        cameraThread = null; cameraHandler = null
        runOnUiThread { toggleButton.text = "Retomar captura"; replayButton.isEnabled = false; if (status.text.toString().startsWith("Captura contínua")) status.text = "Captura parada" }
    }

    fun setRemoteText(text: String) { if (::remoteInfo.isInitialized) remoteInfo.text = text }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) { saveReplay(30); return true }
        return super.onKeyDown(keyCode, event)
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    override fun onDestroy() {
        remoteServer?.stopServer(); stopCapture(); executor.shutdown(); super.onDestroy()
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
