package com.camstream.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: TextureView
    private lateinit var statusText: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnSwitchCamera: Button

    private var service: StreamService? = null
    private var statusJob: Job? = null
    private var previewSurface: Surface? = null

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
        statusText = findViewById(R.id.statusText)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)

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
            }
        }

        btnSwitchCamera.setOnClickListener { service?.switchCamera() }

        requestPermissionsAndBind()
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
        btnStartStop.text = getString(if (status.running) R.string.stop else R.string.start)
        statusText.text = when {
            !status.running -> getString(R.string.status_stopped) +
                (status.error?.let { " — $it" } ?: "")

            status.clients > 0 && status.usbClient ->
                "● USB conectado — rtsp://127.0.0.1:${status.port}/cam"

            status.clients > 0 ->
                "● WiFi conectado — rtsp://${status.localIp ?: "?"}:${status.port}/cam"

            else -> {
                val mdns = if (status.mdnsRegistered) "anunciado por mDNS" else "mDNS no disponible"
                "Esperando receptor ($mdns) — rtsp://${status.localIp ?: "?"}:${status.port}/cam"
            }
        }
    }

    override fun onDestroy() {
        if (service != null) {
            unbindService(connection)
            service = null
        }
        super.onDestroy()
    }
}
