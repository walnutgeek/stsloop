package com.walnutgeek.stsloop.core

/** The Corpus audio format: canonical 44-byte-header WAV, PCM 16-bit, mono. */
object Wav {
    const val HEADER_BYTES = 44
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
    private const val BLOCK_ALIGN = CHANNELS * BITS_PER_SAMPLE / 8

    fun header(sampleRate: Int, dataBytes: Int): ByteArray {
        val h = ByteArray(HEADER_BYTES)
        ascii(h, 0, "RIFF")
        le32(h, 4, 36 + dataBytes)
        ascii(h, 8, "WAVE")
        ascii(h, 12, "fmt ")
        le32(h, 16, 16)
        le16(h, 20, 1) // PCM
        le16(h, 22, CHANNELS)
        le32(h, 24, sampleRate)
        le32(h, 28, sampleRate * BLOCK_ALIGN)
        le16(h, 32, BLOCK_ALIGN)
        le16(h, 34, BITS_PER_SAMPLE)
        ascii(h, 36, "data")
        le32(h, 40, dataBytes)
        return h
    }

    /** Writes the first [count] samples into [out] as little-endian bytes (2 per sample). */
    fun pcm16ToLittleEndian(samples: ShortArray, count: Int, out: ByteArray) {
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
    }

    fun durationMs(samples: Long, sampleRate: Int): Long = samples * 1000 / sampleRate

    private fun ascii(b: ByteArray, at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }

    private fun le16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte()
        b[at + 1] = (v shr 8).toByte()
    }

    private fun le32(b: ByteArray, at: Int, v: Int) {
        le16(b, at, v)
        le16(b, at + 2, v shr 16)
    }
}
