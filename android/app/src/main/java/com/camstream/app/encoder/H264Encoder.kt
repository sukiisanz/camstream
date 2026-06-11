package com.camstream.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Codificador H.264 por hardware con entrada de Surface.
 *
 * La cámara dibuja cada fotograma directamente en [inputSurface] (cero
 * copias) y el códec entrega access units Annex-B por [onFrame].
 * SPS/PPS se entregan una vez por [onSpsPps] en cuanto el códec los emite.
 */
class H264Encoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val fps: Int = 30,
    private val bitrate: Int = 6_000_000,
    private val onSpsPps: (sps: ByteArray, pps: ByteArray) -> Unit,
    private val onFrame: (data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) -> Unit,
) {

    lateinit var inputSurface: Surface
        private set

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Un keyframe por segundo: reconexión rápida sin inflar el bitrate.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = tiempo real
            if (Build.VERSION.SDK_INT >= 30) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        val handlerThread = HandlerThread("H264Encoder").apply { start() }
        thread = handlerThread

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            setCallback(callback, Handler(handlerThread.looper))
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
    }

    /** Pide al códec que el siguiente fotograma sea un keyframe (IDR). */
    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: IllegalStateException) {
            Log.w(TAG, "requestKeyFrame en estado inválido", e)
        }
    }

    fun stop() {
        try {
            codec?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stop en estado inválido", e)
        }
        codec?.release()
        codec = null
        if (::inputSurface.isInitialized) inputSurface.release()
        thread?.quitSafely()
        thread = null
    }

    private val callback = object : MediaCodec.Callback() {

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            try {
                val buffer = codec.getOutputBuffer(index) ?: return
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data, 0, info.size)

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    extractSpsPps(data)
                } else if (info.size > 0) {
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    onFrame(data, info.presentationTimeUs, isKey)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Códec liberado durante onOutputBufferAvailable", e)
            }
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Entrada por Surface: no hay buffers de entrada que gestionar.
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Formato de salida: $format")
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Error del códec", e)
        }
    }

    private fun extractSpsPps(csd: ByteArray) {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in Nal.splitAnnexB(csd)) {
            when (Nal.typeOf(nal)) {
                Nal.TYPE_SPS -> sps = nal
                Nal.TYPE_PPS -> pps = nal
            }
        }
        if (sps != null && pps != null) {
            onSpsPps(sps, pps)
        } else {
            Log.e(TAG, "CSD sin SPS/PPS válidos (${csd.size} bytes)")
        }
    }

    private companion object {
        const val TAG = "H264Encoder"
    }
}
