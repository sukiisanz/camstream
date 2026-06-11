package com.camstream.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.camstream.app.encoder.H264Encoder
import com.camstream.app.net.NsdAnnouncer
import com.camstream.app.rtsp.RtspServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Servicio en primer plano que mantiene vivo el pipeline completo:
 * cámara → encoder H.264 → servidor RTSP, más el anuncio mDNS.
 */
class StreamService : Service() {

    data class Status(
        val running: Boolean = false,
        val clients: Int = 0,
        val usbClient: Boolean = false,
        val mdnsRegistered: Boolean = false,
        val backCamera: Boolean = true,
        val localIp: String? = null,
        val port: Int = RtspServer.DEFAULT_PORT,
        val error: String? = null,
        val width: Int = 1280,
        val height: Int = 720,
        val measuredFps: Int = 0,
        val measuredKbps: Int = 0,
    )

    inner class LocalBinder : Binder() {
        val service: StreamService get() = this@StreamService
    }

    private val binder = LocalBinder()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> get() = _status

    private var encoder: H264Encoder? = null
    private var rtspServer: RtspServer? = null
    private var cameraEngine: CameraEngine? = null
    private var nsdAnnouncer: NsdAnnouncer? = null
    private var previewSurface: Surface? = null

    private val prefs by lazy { getSharedPreferences("camstream", Context.MODE_PRIVATE) }

    /** 0 = 1080p, 1 = 720p (defecto), 2 = 480p. */
    val qualityIndex: Int get() = prefs.getInt(KEY_QUALITY, 1)

    private fun qualityFor(index: Int): Triple<Int, Int, Int> = when (index) {
        0 -> Triple(1920, 1080, 8_000_000)
        2 -> Triple(848, 480, 2_500_000)
        else -> Triple(1280, 720, 6_000_000)
    }

    fun setQuality(index: Int) {
        if (index == qualityIndex) return
        prefs.edit().putInt(KEY_QUALITY, index).apply()
        if (_status.value.running) {
            // Reinicia el pipeline con la nueva calidad
            teardownPipeline()
            _status.value = _status.value.copy(
                running = false, clients = 0, usbClient = false,
                mdnsRegistered = false, measuredFps = 0, measuredKbps = 0,
            )
            startStreaming()
        }
    }

    // Estadísticas en vivo (fps y bitrate reales, ventana de 1 s)
    private var statFrames = 0
    private var statBytes = 0L
    private var statWindowStart = 0L

    private fun trackStats(bytes: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (statWindowStart == 0L) statWindowStart = now
        statFrames++
        statBytes += bytes
        val dt = now - statWindowStart
        if (dt >= 1000) {
            _status.value = _status.value.copy(
                measuredFps = (statFrames * 1000 / dt).toInt(),
                measuredKbps = (statBytes * 8 / dt).toInt(),
            )
            statFrames = 0
            statBytes = 0
            statWindowStart = now
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    fun startStreaming() {
        if (_status.value.running) return
        Log.i(TAG, "Iniciando transmisión")
        startAsForeground()

        try {
            val server = RtspServer(
                onClientsChanged = { count, anyUsb ->
                    _status.value = _status.value.copy(clients = count, usbClient = anyUsb)
                },
                onKeyFrameRequest = { encoder?.requestKeyFrame() },
            )
            val (width, height, bitrate) = qualityFor(qualityIndex)
            statFrames = 0; statBytes = 0; statWindowStart = 0
            val newEncoder = H264Encoder(
                width = width, height = height, bitrate = bitrate,
                onSpsPps = { sps, pps -> server.setSpsPps(sps, pps) },
                onFrame = { data, ptsUs, isKey ->
                    trackStats(data.size)
                    server.broadcast(data, ptsUs, isKey)
                },
            )
            newEncoder.start()
            server.start()

            encoder = newEncoder
            rtspServer = server

            startCamera(_status.value.backCamera)

            val announcer = NsdAnnouncer(this, RtspServer.DEFAULT_PORT)
            announcer.register { registered ->
                _status.value = _status.value.copy(mdnsRegistered = registered)
            }
            nsdAnnouncer = announcer

            _status.value = _status.value.copy(
                running = true,
                localIp = findLocalIp(),
                width = width,
                height = height,
                error = null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar la transmisión", e)
            teardownPipeline()
            _status.value = _status.value.copy(running = false, error = e.message)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    fun stopStreaming() {
        Log.i(TAG, "Deteniendo transmisión")
        teardownPipeline()
        _status.value = Status(backCamera = _status.value.backCamera)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun switchCamera() {
        val back = !_status.value.backCamera
        _status.value = _status.value.copy(backCamera = back)
        if (_status.value.running) startCamera(back)
    }

    /** La Activity entrega aquí el Surface del preview (o null al irse). */
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        if (_status.value.running) {
            cameraEngine?.updateTargets(currentTargets()) { msg -> reportError(msg) }
        }
    }

    private fun startCamera(back: Boolean) {
        val engine = cameraEngine ?: CameraEngine(this).also { cameraEngine = it }
        val cameraId = engine.findCameraId(back)
        if (cameraId == null) {
            reportError("No se encontró cámara ${if (back) "trasera" else "frontal"}")
            return
        }
        engine.start(cameraId, currentTargets(), fps = 30) { msg -> reportError(msg) }
    }

    private fun currentTargets(): List<Surface> {
        val targets = mutableListOf<Surface>()
        encoder?.inputSurface?.let { targets.add(it) }
        previewSurface?.takeIf { it.isValid }?.let { targets.add(it) }
        return targets
    }

    private fun reportError(message: String) {
        Log.e(TAG, message)
        _status.value = _status.value.copy(error = message)
    }

    private fun teardownPipeline() {
        nsdAnnouncer?.unregister()
        nsdAnnouncer = null
        cameraEngine?.release()
        cameraEngine = null
        rtspServer?.stop()
        rtspServer = null
        encoder?.stop()
        encoder = null
    }

    override fun onDestroy() {
        teardownPipeline()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun findLocalIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo obtener la IP local", e)
            null
        }
    }

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "camstream"
        private const val NOTIFICATION_ID = 1
        private const val KEY_QUALITY = "quality"
        const val ACTION_STOP = "com.camstream.app.STOP"
    }
}
