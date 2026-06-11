package com.camstream.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface

/**
 * Maneja la cámara con Camera2 enviando los fotogramas a la vez a dos
 * destinos: el Surface del codificador H.264 y (si existe) el Surface
 * del preview en pantalla. Sin copias intermedias de píxeles.
 */
class CameraEngine(context: Context) {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val thread = HandlerThread("CameraEngine").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var currentTargets: List<Surface> = emptyList()
    private var currentFps = 30

    fun findCameraId(back: Boolean): String? {
        val wanted =
            if (back) CameraCharacteristics.LENS_FACING_BACK
            else CameraCharacteristics.LENS_FACING_FRONT
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == wanted
        }
    }

    @SuppressLint("MissingPermission") // la Activity comprueba el permiso antes
    fun start(
        cameraId: String,
        targets: List<Surface>,
        fps: Int,
        onError: (String) -> Unit,
    ) {
        stop()
        currentTargets = targets
        currentFps = fps
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera, targets, fps, onError)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                device = null
                onError("Cámara desconectada")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                device = null
                onError("Error de cámara: $error")
            }
        }, handler)
    }

    /** Cambia los Surfaces de destino (p. ej. al aparecer el preview). */
    fun updateTargets(targets: List<Surface>, onError: (String) -> Unit) {
        val camera = device ?: return
        currentTargets = targets
        createSession(camera, targets, currentFps, onError)
    }

    private fun createSession(
        camera: CameraDevice,
        targets: List<Surface>,
        fps: Int,
        onError: (String) -> Unit,
    ) {
        try {
            session?.close()
            session = null
            @Suppress("DEPRECATION") // la variante con OutputConfiguration no aporta nada aquí
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(newSession: CameraCaptureSession) {
                    session = newSession
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        targets.forEach { addTarget(it) }
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                        // CONTINUOUS_PICTURE reenfoca más rápido y agresivo que
                        // CONTINUOUS_VIDEO; para webcam (sujeto casi estático)
                        // gana en nitidez sin transiciones molestas.
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        // La estabilización electrónica (que algunos fabricantes
                        // activan sola al grabar) recorta y emborrona la imagen;
                        // con el teléfono apoyado no aporta nada.
                        set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                        )
                    }
                    newSession.setRepeatingRequest(request.build(), null, handler)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("No se pudo configurar la sesión de cámara")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "createSession", e)
            onError("Error configurando la cámara: ${e.message}")
        }
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "CameraEngine"
    }
}
