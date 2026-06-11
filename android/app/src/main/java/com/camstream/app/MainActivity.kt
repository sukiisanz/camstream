package com.camstream.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: TextureView
    private lateinit var statusPill: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var liveDot: View
    private lateinit var dimScrim: View
    private lateinit var btnStartStop: FloatingActionButton
    private lateinit var btnSwitchCamera: FloatingActionButton
    private lateinit var btnQr: FloatingActionButton
    private lateinit var btnSettings: FloatingActionButton

    private var service: StreamService? = null
    private var statusJob: Job? = null
    private var previewSurface: Surface? = null
    private var pulse: ObjectAnimator? = null
    private var dimHintShown = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.CAMERA] == true) {
                bindStreamService()
            } else {
                Toast.makeText(this, "Sin permiso de cámara no se puede transmitir", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val streamService = (binder as StreamService.LocalBinder).service
            service = streamService
            previewSurface?.let { streamService.setPreviewSurface(it) }
            observeStatus(streamService)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            statusJob?.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        statusPill = findViewById(R.id.statusPill)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        liveDot = findViewById(R.id.liveDot)
        dimScrim = findViewById(R.id.dimScrim)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnQr = findViewById(R.id.btnQr)
        btnSettings = findViewById(R.id.btnSettings)

        previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                texture.setDefaultBufferSize(1280, 720)
                configureTransform(w, h)
                previewSurface = Surface(texture)
                service?.setPreviewSurface(previewSurface)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {
                configureTransform(w, h)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                service?.setPreviewSurface(null)
                previewSurface?.release()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }

        btnStartStop.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.status.value.running) {
                s.stopStreaming()
            } else {
                // El servicio debe arrancar como "started" además de "bound"
                // para sobrevivir si la Activity pasa a segundo plano.
                ContextCompat.startForegroundService(this, Intent(this, StreamService::class.java))
                s.startStreaming()
                if (!dimHintShown) {
                    dimHintShown = true
                    Toast.makeText(this, R.string.dim_hint, Toast.LENGTH_LONG).show()
                }
            }
        }

        btnSwitchCamera.setOnClickListener { service?.switchCamera() }
        btnQr.setOnClickListener { showQrDialog() }
        statusPill.setOnClickListener { showQrDialog() }
        btnSettings.setOnClickListener { showQualityDialog() }

        // Modo ahorro: tocar el video atenúa, tocar la capa negra vuelve
        previewView.setOnClickListener {
            if (service?.status?.value?.running == true) setDim(true)
        }
        dimScrim.setOnClickListener { setDim(false) }

        requestPermissionsAndBind()
    }

    private fun setDim(on: Boolean) {
        dimScrim.visibility = if (on) View.VISIBLE else View.GONE
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.05f
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun showQualityDialog() {
        val s = service ?: return
        val options = arrayOf(
            getString(R.string.quality_1080),
            getString(R.string.quality_720),
            getString(R.string.quality_480),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setSingleChoiceItems(options, s.qualityIndex) { dialog, which ->
                s.setQuality(which)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showQrDialog() {
        val status = service?.status?.value ?: return
        val ip = status.localIp
        if (ip == null) {
            Toast.makeText(this, "Inicia la transmisión primero", Toast.LENGTH_SHORT).show()
            return
        }
        val url = "rtsp://$ip:${status.port}/cam"
        val image = ImageView(this).apply {
            setImageBitmap(makeQr(url))
            adjustViewBounds = true
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(url)
            .setMessage(R.string.qr_hint)
            .setView(image)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun makeQr(text: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(size * size) { i ->
            if (matrix.get(i % size, i / size)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    /**
     * El TextureView pinta el buffer de la cámara tal cual sale del sensor
     * (montado en vertical en casi todos los teléfonos): con la pantalla en
     * horizontal hay que contra-rotarlo y reescalarlo, o se ve girado y
     * estirado. Misma técnica que el ejemplo oficial camera2basic, con
     * escala "fit" para ver el encuadre completo que se transmite.
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = if (Build.VERSION.SDK_INT >= 30) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val bufferW = 1280f
        val bufferH = 720f
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val bufferRect = RectF(0f, 0f, bufferH, bufferW)
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = minOf(viewHeight / bufferH, viewWidth / bufferW)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        previewView.setTransform(matrix)
    }

    private fun requestPermissionsAndBind() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            bindStreamService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun bindStreamService() {
        bindService(Intent(this, StreamService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun observeStatus(streamService: StreamService) {
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamService.status.collect { status -> render(status) }
            }
        }
    }

    private fun render(status: StreamService.Status) {
        btnStartStop.setImageResource(if (status.running) R.drawable.ic_stop else R.drawable.ic_play)
        btnStartStop.contentDescription = getString(if (status.running) R.string.stop else R.string.start)

        val (dotColor, label) = when {
            !status.running ->
                R.color.cs_stopped to getString(R.string.status_stopped) +
                    (status.error?.let { " — $it" } ?: "")

            status.clients > 0 && status.usbClient ->
                R.color.cs_usb to getString(R.string.status_usb)

            status.clients > 0 ->
                R.color.cs_wifi to getString(R.string.status_wifi)

            else -> R.color.cs_waiting to getString(R.string.status_waiting)
        }
        statusText.text = label
        val live = status.running && status.clients > 0
        liveDot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (live) R.color.cs_live else dotColor)
        )
        setPulsing(live)

        statsText.visibility = if (status.running) View.VISIBLE else View.GONE
        if (status.running) {
            statsText.text = String.format(
                Locale.US, "%d×%d · %d fps · %.1f Mbps",
                status.width, status.height,
                status.measuredFps, status.measuredKbps / 1000f,
            )
        } else if (dimScrim.visibility == View.VISIBLE) {
            setDim(false)
        }
    }

    /** Puntito rojo latiendo mientras alguien recibe el stream. */
    private fun setPulsing(on: Boolean) {
        if (on && pulse == null) {
            pulse = ObjectAnimator.ofFloat(liveDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (!on) {
            pulse?.cancel()
            pulse = null
            liveDot.alpha = 1f
        }
    }

    override fun onDestroy() {
        pulse?.cancel()
        if (service != null) {
            unbindService(connection)
            service = null
        }
        super.onDestroy()
    }
}
