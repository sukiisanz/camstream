package com.camstream.app.rtsp

import com.camstream.app.encoder.Nal
import kotlin.random.Random

/**
 * Convierte access units H.264 (Annex-B) en paquetes RTP según RFC 6184.
 *
 * NALs pequeñas viajan como "Single NAL Unit Packet"; las grandes se
 * fragmentan en FU-A. El bit marker se activa en el último paquete de
 * cada access unit (fin de fotograma).
 */
class RtpH264Packetizer(private val ssrc: Int = Random.nextInt()) {

    private var seq = Random.nextInt(0x10000)

    fun packetize(accessUnit: ByteArray, ptsUs: Long): List<ByteArray> {
        // Reloj RTP de 90 kHz para video.
        val timestamp = (ptsUs * 90L / 1000L).toInt()
        val nals = Nal.splitAnnexB(accessUnit).filter { it.isNotEmpty() }
        val packets = mutableListOf<ByteArray>()
        nals.forEachIndexed { index, nal ->
            val lastNal = index == nals.lastIndex
            if (nal.size <= MAX_PAYLOAD) {
                packets.add(singleNal(nal, timestamp, marker = lastNal))
            } else {
                packets.addAll(fragmented(nal, timestamp, lastNal))
            }
        }
        return packets
    }

    private fun singleNal(nal: ByteArray, timestamp: Int, marker: Boolean): ByteArray {
        val packet = ByteArray(HEADER_SIZE + nal.size)
        writeHeader(packet, marker, timestamp)
        nal.copyInto(packet, HEADER_SIZE)
        return packet
    }

    private fun fragmented(nal: ByteArray, timestamp: Int, lastNal: Boolean): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val fuIndicator = ((nal[0].toInt() and 0xE0) or 28).toByte() // NRI original + tipo FU-A
        val nalType = (nal[0].toInt() and 0x1F).toByte()
        var offset = 1 // el primer byte (cabecera NAL) va codificado en FU indicator/header
        while (offset < nal.size) {
            val chunk = minOf(MAX_PAYLOAD - 2, nal.size - offset)
            val startBit = offset == 1
            val endBit = offset + chunk == nal.size
            val packet = ByteArray(HEADER_SIZE + 2 + chunk)
            writeHeader(packet, marker = endBit && lastNal, timestamp = timestamp)
            packet[HEADER_SIZE] = fuIndicator
            packet[HEADER_SIZE + 1] = (
                (if (startBit) 0x80 else 0) or
                (if (endBit) 0x40 else 0) or
                nalType.toInt()
            ).toByte()
            nal.copyInto(packet, HEADER_SIZE + 2, offset, offset + chunk)
            packets.add(packet)
            offset += chunk
        }
        return packets
    }

    private fun writeHeader(packet: ByteArray, marker: Boolean, timestamp: Int) {
        packet[0] = 0x80.toByte() // V=2, sin padding, sin extensión, sin CSRC
        packet[1] = ((if (marker) 0x80 else 0) or PAYLOAD_TYPE).toByte()
        packet[2] = (seq shr 8).toByte()
        packet[3] = seq.toByte()
        seq = (seq + 1) and 0xFFFF
        packet[4] = (timestamp shr 24).toByte()
        packet[5] = (timestamp shr 16).toByte()
        packet[6] = (timestamp shr 8).toByte()
        packet[7] = timestamp.toByte()
        packet[8] = (ssrc shr 24).toByte()
        packet[9] = (ssrc shr 16).toByte()
        packet[10] = (ssrc shr 8).toByte()
        packet[11] = ssrc.toByte()
    }

    companion object {
        const val PAYLOAD_TYPE = 96
        const val HEADER_SIZE = 12
        const val MAX_PAYLOAD = 1400
    }
}
