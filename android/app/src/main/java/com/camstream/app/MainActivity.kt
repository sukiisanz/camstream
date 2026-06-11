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
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private lateinit var btnStartStop: ImageButton
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnQr: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnBattery: ImageButton
    private lateinit var btnOverlay: ImageButton

    private val swatchColors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF1A8CB1.toInt(), 0xFF2AADA8.toInt(),
        0xFF41DE8F.toInt(), 0xFFFFB300.toInt(), 0xFFFF1744.toInt(), 0xFF9C27B0.toInt(),
    )

    private var service: StreamService? = null
    private var statusJob: Job? = null
    private var previewSurface: Surface? = null
    private var pulse: ObjectAnimator? = null
    private var streamRotation = 0
    private var streamMirror = false

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
        btnBattery = findViewById(R.id.btnBattery)
        btnOverlay = findViewById(R.id.btnOverlay)

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
        btnQr.setOnClickListener { showQrDialog() }
        statusPill.setOnClickListener { showQrDialog() }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnBattery.setOnClickListener { showBatteryDialog() }
        btnOverlay.setOnClickListener { showOverlayDialog() }

        // Modo ahorro: tocar la capa negra recupera el brillo
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

    private fun showBatteryDialog() {
        if (service?.status?.value?.running != true) {
            Toast.makeText(this, "Inicia la transmisión primero", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_confirm) { _, _ -> setDim(true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSettingsDialog() {
        val s = service ?: return
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val rgQuality = view.findViewById<RadioGroup>(R.id.rgQuality)
        val rgRotation = view.findViewById<RadioGroup>(R.id.rgRotation)
        val swMirror = view.findViewById<SwitchMaterial>(R.id.swMirror)
        val sbBrightness = view.findViewById<SeekBar>(R.id.sbBrightness)
        val sbContrast = view.findViewById<SeekBar>(R.id.sbContrast)
        val sbSaturation = view.findViewById<SeekBar>(R.id.sbSaturation)
        val btnReset = view.findViewById<Button>(R.id.btnResetImage)

        val qualityIds = intArrayOf(R.id.rbQ1080, R.id.rbQ720, R.id.rbQ480)
        val rotationIds = intArrayOf(R.id.rbRot0, R.id.rbRot90, R.id.rbRot180, R.id.rbRot270)

        // Estado actual antes de enganchar listeners, para no disparar cambios
        rgQuality.check(qualityIds[s.qualityIndex])
        rgRotation.check(rotationIds[s.rotationIndex])
        swMirror.isChecked = s.mirror
        sbBrightness.progress = s.brightnessPct
        sbContrast.progress = s.contrastPct
        sbSaturation.progress = s.saturationPct

        rgQuality.setOnCheckedChangeListener { _, id -> s.setQuality(qualityIds.indexOf(id)) }
        rgRotation.setOnCheckedChangeListener { _, id -> s.setRotation(rotationIds.indexOf(id)) }
        swMirror.setOnCheckedChangeListener { _, on -> s.setMirror(on) }
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    s.setImageAdjust(
                        sbBrightness.progress, sbContrast.progress, sbSaturation.progress,
                    )
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }
        sbBrightness.setOnSeekBarChangeListener(seekListener)
        sbContrast.setOnSeekBarChangeListener(seekListener)
        sbSaturation.setOnSeekBarChangeListener(seekListener)
        btnReset.setOnClickListener {
            sbBrightness.progress = 100
            sbContrast.progress = 100
            sbSaturation.progress = 100
            s.setImageAdjust(100, 100, 100)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showOverlayDialog() {
        val s = service ?: return
        val view = layoutInflater.inflate(R.layout.dialog_overlay, null)
        val swOverlay = view.findViewById<SwitchMaterial>(R.id.swOverlay)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etRole = view.findViewById<EditText>(R.id.etRole)
        val rgStyle = view.findViewById<RadioGroup>(R.id.rgStyle)
        val rgPosition = view.findViewById<RadioGroup>(R.id.rgPosition)
        val rowText = view.findViewById<LinearLayout>(R.id.rowTextColors)
        val rowAccent = view.findViewById<LinearLayout>(R.id.rowAccentColors)

        val styleIds = intArrayOf(
            R.id.rbStyleClean, R.id.rbStyleBar, R.id.rbStylePill, R.id.rbStyleGradient,
        )
        val posIds = intArrayOf(R.id.rbPosLeft, R.id.rbPosCenter, R.id.rbPosRight)

        swOverlay.isChecked = s.overlayEnabled
        etName.setText(s.overlayName)
        etRole.setText(s.overlayRole)
        rgStyle.check(styleIds[s.overlayStyle])
        rgPosition.check(posIds[s.overlayPosition])
        var textColor = s.overlayTextColor
        var accentColor = s.overlayAccentColor
        buildSwatchRow(rowText, textColor) { textColor = it }
        buildSwatchRow(rowAccent, accentColor) { accentColor = it }

        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_title)
            .setView(view)
            .setPositiveButton(R.string.apply) { _, _ ->
                s.setOverlayConfig(
                    enabled = swOverlay.isChecked,
                    name = etName.text.toString().trim(),
                    role = etRole.text.toString().trim(),
                    style = styleIds.indexOf(rgStyle.checkedRadioButtonId).coerceAtLeast(0),
                    textColor = textColor,
                    accentColor = accentColor,
                    position = posIds.indexOf(rgPosition.checkedRadioButtonId).coerceAtLeast(0),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Fila de circulitos de color; el elegido se marca con borde blanco. */
    private fun buildSwatchRow(row: LinearLayout, initial: Int, onPick: (Int) -> Unit) {
        val density = resources.displayMetrics.density
        val size = (32 * density).toInt()
        val margin = (6 * density).toInt()
        val stroke = (3 * density).toInt()
        var selected = swatchColors.indexOf(initial).coerceAtLeast(0)
        val views = mutableListOf<View>()

        fun refresh() {
            views.forEachIndexed { i, v ->
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(swatchColors[i])
                    setStroke(stroke, if (i == selected) Color.WHITE else 0x40FFFFFF)
                }
            }
        }

        swatchColors.forEachIndexed { i, color ->
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, 0, margin, 0)
            }
            v.setOnClickListener {
                selected = i
                onPick(color)
                refresh()
            }
            views.add(v)
            row.addView(v)
        }
        refresh()
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
        // Refleja en el preview el espejo/giro que se aplica al stream
        if (streamMirror) matrix.postScale(-1f, 1f, centerX, centerY)
        if (streamRotation != 0) {
            matrix.postRotate(streamRotation.toFloat(), centerX, centerY)
            if (streamRotation % 180 != 0 && viewWidth > viewHeight && viewHeight > 0) {
                val scale = viewHeight.toFloat() / viewWidth.toFloat()
                matrix.postScale(scale, scale, centerX, centerY)
            }
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

        if (status.rotationDeg != streamRotation || status.mirror != streamMirror) {
            streamRotation = status.rotationDeg
            streamMirror = status.mirror
            if (previewView.width > 0) {
                configureTransform(previewView.width, previewView.height)
            }
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
