package com.camstream.app.encoder

/**
 * Utilidades para trocear un buffer H.264 Annex-B (con start codes
 * 00 00 00 01 / 00 00 01) en unidades NAL individuales sin start code.
 */
object Nal {

    const val TYPE_IDR = 5
    const val TYPE_SPS = 7
    const val TYPE_PPS = 8

    fun typeOf(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    /** Devuelve la lista de NALs (sin start codes) contenidas en [data]. */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var i = 0
        var nalStart = -1
        while (i < data.size - 2) {
            val isStart3 = data[i] == ZERO && data[i + 1] == ZERO && data[i + 2] == ONE
            val isStart4 = i < data.size - 3 &&
                data[i] == ZERO && data[i + 1] == ZERO && data[i + 2] == ZERO && data[i + 3] == ONE
            if (isStart3 || isStart4) {
                if (nalStart >= 0) {
                    nals.add(data.copyOfRange(nalStart, i))
                }
                i += if (isStart4) 4 else 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until data.size) {
            nals.add(data.copyOfRange(nalStart, data.size))
        }
        return nals
    }

    private const val ZERO = 0.toByte()
    private const val ONE = 1.toByte()
}
