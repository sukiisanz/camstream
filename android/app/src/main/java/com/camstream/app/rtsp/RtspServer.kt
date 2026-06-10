package com.camstream.app.rtsp

import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Servidor RTSP mínimo embebido (OPTIONS / DESCRIBE / SETUP / PLAY /
 * PAUSE / TEARDOWN) que entrega RTP **intercalado sobre TCP**
 * (RTP/AVP/TCP, RFC 2326 §10.12).
 *
 * Solo soportamos TCP a propósito: funciona igual por WiFi y por USB
 * (`adb forward` solo reenvía TCP) y evita pérdida de paquetes. El
 * receptor de Linux pide TCP explícitamente.
 */
class RtspServer(
    private val port: Int = DEFAULT_PORT,
    private val streamPath: String = DEFAULT_PATH,
    private val onClientsChanged: (count: Int, anyUsb: Boolean) -> Unit,
    private val onKeyFrameRequest: () -> Unit,
) {

    class Frame(val data: ByteArray, val ptsUs: Long, val isKeyFrame: Boolean)

    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    private val spsPpsLock = Object()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var running = false

    fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        synchronized(spsPpsLock) {
            this.sps = sps
            this.pps = pps
            spsPpsLock.notifyAll()
        }
    }

    fun start() {
        if (running) return
        running = true
        val socket = ServerSocket(port)
        serverSocket = socket
        acceptThread = Thread({
            while (running) {
                try {
                    val client = socket.accept()
                    client.tcpNoDelay = true
                    Client(client).start()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept() falló", e)
                }
            }
        }, "RtspAccept").apply { start() }
        Log.i(TAG, "Servidor RTSP escuchando en el puerto $port")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        clients.forEach { it.shutdown() }
        clients.clear()
        notifyClientsChanged()
    }

    /** Reparte una access unit codificada a todos los clientes en PLAY. */
    fun broadcast(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
        if (clients.isEmpty()) return
        val frame = Frame(data, ptsUs, isKeyFrame)
        clients.forEach { it.enqueue(frame) }
    }

    private fun notifyClientsChanged() {
        val playing = clients.filter { it.playing }
        onClientsChanged(playing.size, playing.any { it.isLoopback })
    }

    private fun buildSdp(): String {
        val sps = this.sps ?: return ""
        val pps = this.pps ?: return ""
        val spsB64 = Base64.encodeToString(sps, Base64.NO_WRAP)
        val ppsB64 = Base64.encodeToString(pps, Base64.NO_WRAP)
        val profileLevelId = "%02x%02x%02x".format(sps[1], sps[2], sps[3])
        return buildString {
            append("v=0\r\n")
            append("o=- ${System.currentTimeMillis()} 1 IN IP4 0.0.0.0\r\n")
            append("s=CamStream\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("t=0 0\r\n")
            append("m=video 0 RTP/AVP ${RtpH264Packetizer.PAYLOAD_TYPE}\r\n")
            append("a=rtpmap:${RtpH264Packetizer.PAYLOAD_TYPE} H264/90000\r\n")
            append(
                "a=fmtp:${RtpH264Packetizer.PAYLOAD_TYPE} packetization-mode=1;" +
                    "profile-level-id=$profileLevelId;" +
                    "sprop-parameter-sets=$spsB64,$ppsB64\r\n"
            )
            append("a=control:streamid=0\r\n")
        }
    }

    /** Espera (hasta [timeoutMs]) a que el encoder haya emitido SPS/PPS. */
    private fun awaitSpsPps(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(spsPpsLock) {
            while (sps == null || pps == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                try {
                    spsPpsLock.wait(remaining)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return true
    }

    private inner class Client(private val socket: Socket) : Thread("RtspClient") {

        /** Conexiones desde 127.0.0.1 llegan por `adb forward` → modo USB. */
        val isLoopback: Boolean = socket.inetAddress.isLoopbackAddress

        @Volatile var playing = false
        @Volatile private var alive = true
        @Volatile private var waitingForKeyFrame = true

        private val input = PushbackInputStream(socket.getInputStream(), 1)
        private val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        private val queue = ArrayBlockingQueue<Frame>(QUEUE_CAPACITY)
        private val packetizer = RtpH264Packetizer()
        private val sessionId = Random.nextLong(0x10000000L, Long.MAX_VALUE).toString(16)
        private var writerThread: Thread? = null

        override fun run() {
            clients.add(this)
            Log.i(TAG, "Cliente conectado: ${socket.inetAddress.hostAddress} (usb=$isLoopback)")
            try {
                while (alive) {
                    val first = input.read()
                    if (first < 0) break
                    if (first == INTERLEAVED_MAGIC) {
                        skipInterleavedFrame()
                        continue
                    }
                    input.unread(first)
                    handleRequest()
                }
            } catch (e: IOException) {
                Log.i(TAG, "Cliente desconectado: ${e.message}")
            } finally {
                shutdown()
            }
        }

        fun enqueue(frame: Frame) {
            if (!playing) return
            if (waitingForKeyFrame) {
                if (!frame.isKeyFrame) return
                waitingForKeyFrame = false
            }
            if (!queue.offer(frame)) {
                // El cliente no consume a tiempo: vaciamos para no acumular
                // latencia y esperamos al siguiente keyframe para reengancharnos.
                queue.clear()
                waitingForKeyFrame = true
                onKeyFrameRequest()
            }
        }

        fun shutdown() {
            if (!alive) return
            alive = false
            playing = false
            writerThread?.interrupt()
            try { socket.close() } catch (_: IOException) {}
            clients.remove(this)
            notifyClientsChanged()
        }

        // ---- Lectura RTSP ----

        private fun skipInterleavedFrame() {
            // $ <canal:1> <longitud:2> <datos>: RTCP del cliente, lo ignoramos.
            input.read()
            val len = (input.read() shl 8) or input.read()
            var remaining = len
            while (remaining > 0) {
                val skipped = input.skip(remaining.toLong()).toInt()
                if (skipped <= 0) break
                remaining -= skipped
            }
        }

        private fun readLine(stream: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = stream.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        private fun handleRequest() {
            val requestLine = readLine(input) ?: throw IOException("EOF")
            if (requestLine.isBlank()) return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: throw IOException("EOF en cabeceras")
                if (line.isBlank()) break
                val sep = line.indexOf(':')
                if (sep > 0) {
                    headers[line.substring(0, sep).trim().lowercase()] =
                        line.substring(sep + 1).trim()
                }
            }
            // Cuerpo (si lo hay) no nos interesa: lo saltamos.
            headers["content-length"]?.toIntOrNull()?.let { length ->
                var remaining = length
                while (remaining > 0) {
                    val skipped = input.skip(remaining.toLong()).toInt()
                    if (skipped <= 0) break
                    remaining -= skipped
                }
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: ""
            val cseq = headers["cseq"] ?: "0"
            Log.d(TAG, "RTSP $method (CSeq $cseq)")

            when (method) {
                "OPTIONS" -> respond(
                    200, "OK", cseq,
                    listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN")
                )

                "DESCRIBE" -> {
                    if (!awaitSpsPps(SPS_TIMEOUT_MS)) {
                        respond(503, "Service Unavailable", cseq)
                        return
                    }
                    val sdp = buildSdp().toByteArray(Charsets.UTF_8)
                    val base = "rtsp://${socket.localAddress.hostAddress}:$port$streamPath/"
                    respond(
                        200, "OK", cseq,
                        listOf(
                            "Content-Base: $base",
                            "Content-Type: application/sdp",
                            "Content-Length: ${sdp.size}"
                        ),
                        sdp
                    )
                }

                "SETUP" -> {
                    val transport = headers["transport"] ?: ""
                    if (!transport.contains("TCP", ignoreCase = true)) {
                        // Solo RTP intercalado sobre TCP; el cliente debe reintentar.
                        respond(461, "Unsupported Transport", cseq)
                        return
                    }
                    respond(
                        200, "OK", cseq,
                        listOf(
                            "Transport: RTP/AVP/TCP;unicast;interleaved=0-1",
                            "Session: $sessionId;timeout=60"
                        )
                    )
                }

                "PLAY" -> {
                    respond(
                        200, "OK", cseq,
                        listOf("Session: $sessionId", "Range: npt=0-")
                    )
                    startPlaying()
                }

                "PAUSE" -> {
                    playing = false
                    respond(200, "OK", cseq, listOf("Session: $sessionId"))
                    notifyClientsChanged()
                }

                "TEARDOWN" -> {
                    respond(200, "OK", cseq, listOf("Session: $sessionId"))
                    throw IOException("TEARDOWN")
                }

                else -> respond(405, "Method Not Allowed", cseq)
            }
        }

        private fun startPlaying() {
            if (playing) return
            queue.clear()
            waitingForKeyFrame = true
            playing = true
            notifyClientsChanged()
            onKeyFrameRequest()
            writerThread = Thread({
                try {
                    while (alive && playing) {
                        val frame = queue.take()
                        writeFrame(frame)
                    }
                } catch (_: InterruptedException) {
                } catch (e: IOException) {
                    Log.i(TAG, "Escritura RTP terminada: ${e.message}")
                    shutdown()
                }
            }, "RtpWriter").apply { start() }
        }

        private fun writeFrame(frame: Frame) {
            // Reinyectamos SPS/PPS delante de cada keyframe: así un decodificador
            // que se incorpore tarde siempre tiene los parámetros a mano.
            val payload = if (frame.isKeyFrame && sps != null && pps != null) {
                prependParameterSets(frame.data)
            } else {
                frame.data
            }
            val packets = packetizer.packetize(payload, frame.ptsUs)
            synchronized(output) {
                for (packet in packets) {
                    output.write(INTERLEAVED_MAGIC)
                    output.write(RTP_CHANNEL)
                    output.write((packet.size shr 8) and 0xFF)
                    output.write(packet.size and 0xFF)
                    output.write(packet)
                }
                output.flush()
            }
        }

        private fun prependParameterSets(frameData: ByteArray): ByteArray {
            val sps = sps!!
            val pps = pps!!
            val startCode = byteArrayOf(0, 0, 0, 1)
            val out = ByteArray(startCode.size * 2 + sps.size + pps.size + frameData.size)
            var offset = 0
            startCode.copyInto(out, offset); offset += startCode.size
            sps.copyInto(out, offset); offset += sps.size
            startCode.copyInto(out, offset); offset += startCode.size
            pps.copyInto(out, offset); offset += pps.size
            frameData.copyInto(out, offset)
            return out
        }

        private fun respond(
            code: Int,
            reason: String,
            cseq: String,
            extraHeaders: List<String> = emptyList(),
            body: ByteArray? = null,
        ) {
            val response = buildString {
                append("RTSP/1.0 $code $reason\r\n")
                append("CSeq: $cseq\r\n")
                append("Server: CamStream\r\n")
                extraHeaders.forEach { append("$it\r\n") }
                append("\r\n")
            }
            synchronized(output) {
                output.write(response.toByteArray(Charsets.UTF_8))
                body?.let { output.write(it) }
                output.flush()
            }
        }
    }

    companion object {
        private const val TAG = "RtspServer"
        const val DEFAULT_PORT = 8554
        const val DEFAULT_PATH = "/cam"
        private const val INTERLEAVED_MAGIC = 0x24 // '$'
        private const val RTP_CHANNEL = 0
        private const val QUEUE_CAPACITY = 60
        private const val SPS_TIMEOUT_MS = 3000L
    }
}
