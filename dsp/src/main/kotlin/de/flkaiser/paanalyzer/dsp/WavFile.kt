package de.flkaiser.paanalyzer.dsp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileException(message: String) : Exception(message)

class WavAudio(val samples: FloatArray, val sampleRate: Int)

/**
 * Minimal WAV support for storing recordings: writes mono 32-bit float, reads
 * 32-bit float and 16/24/32-bit integer PCM (first channel if there are several).
 */
object WavFile {

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun write(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 4
        val buffer = ByteBuffer.allocate(44 + 12 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + 12 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(FORMAT_FLOAT.toShort())
        buffer.putShort(1) // channels
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 4) // byte rate
        buffer.putShort(4) // block align
        buffer.putShort(32) // bits per sample
        // Non-PCM formats should carry a fact chunk with the sample count.
        buffer.put("fact".toByteArray(Charsets.US_ASCII))
        buffer.putInt(4)
        buffer.putInt(samples.size)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        for (v in samples) buffer.putFloat(v)
        return buffer.array()
    }

    fun read(bytes: ByteArray): WavAudio {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || tag(b, 0) != "RIFF" || tag(b, 8) != "WAVE") throw WavFileException("Not a WAV file.")
        var pos = 12
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(b, pos)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > bytes.size) {
                if (id != "data") throw WavFileException("The WAV file is truncated.")
            }
            when (id) {
                "fmt " -> {
                    format = b.getShort(body).toInt() and 0xFFFF
                    channels = b.getShort(body + 2).toInt()
                    sampleRate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                    if (format == FORMAT_EXTENSIBLE && size >= 26) format = b.getShort(body + 24).toInt() and 0xFFFF
                }
                "data" -> {
                    if (format < 0) throw WavFileException("The WAV file has no format chunk.")
                    val length = minOf(size.toLong() and 0xFFFFFFFFL, (bytes.size - body).toLong()).toInt()
                    return WavAudio(decode(b, body, length, format, channels, bits), sampleRate)
                }
            }
            pos = body + size + (size and 1)
        }
        throw WavFileException("The WAV file has no audio data.")
    }

    private fun decode(b: ByteBuffer, offset: Int, length: Int, format: Int, channels: Int, bits: Int): FloatArray {
        if (channels < 1) throw WavFileException("Invalid channel count.")
        val bytesPerSample = bits / 8
        val frame = bytesPerSample * channels
        val n = length / frame
        return when {
            format == FORMAT_FLOAT && bits == 32 -> FloatArray(n) { b.getFloat(offset + it * frame) }
            format == FORMAT_PCM && bits == 16 -> FloatArray(n) { b.getShort(offset + it * frame) / 32768f }
            format == FORMAT_PCM && bits == 24 -> FloatArray(n) {
                val p = offset + it * frame
                val v = (b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p + 2).toInt() shl 16)
                v / 8388608f
            }
            format == FORMAT_PCM && bits == 32 -> FloatArray(n) { (b.getInt(offset + it * frame) / 2147483648.0).toFloat() }
            else -> throw WavFileException("Unsupported WAV format ($format, $bits bit).")
        }
    }

    private fun tag(b: ByteBuffer, at: Int): String =
        String(ByteArray(4) { b.get(at + it) }, Charsets.US_ASCII)

    /** Helper for tests and tools: a 16-bit PCM file. */
    fun writePcm16(samples: FloatArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        val dataSize = samples.size * 2 * channels
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize).put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(FORMAT_PCM.toShort()).putShort(channels.toShort())
        header.putInt(sampleRate).putInt(sampleRate * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
        out.write(header.array())
        val data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (v in samples) repeat(channels) { data.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        out.write(data.array())
        return out.toByteArray()
    }
}
