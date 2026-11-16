package com.yuushilabs.pianosheet.core.audio

import java.io.File
import java.io.InputStream

data class WavData(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
) {
    val frames: Int get() = if (channels > 0) samples.size / channels else samples.size
    val durationSec: Double get() = if (sampleRate > 0) frames.toDouble() / sampleRate else 0.0
}

/**
 * Reads the saved (unprocessed) WAV back for the final analysis (spec §5.1).
 *
 * The app writes 16-bit mono PCM, but a file imported through SCR-001 can be
 * anything the system hands over, so the common PCM widths are supported and
 * multi-channel audio is mixed down to mono before analysis.
 */
object WavFileReader {

    fun read(file: File): WavData {
        require(file.exists()) { "WAV file not found: ${file.absolutePath}" }
        return file.inputStream().use { read(it) }
    }

    fun read(stream: InputStream): WavData {
        val bytes = stream.readBytes()
        require(bytes.size >= 44) { "not a WAV file (too short)" }
        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
            "not a RIFF/WAVE file"
        }
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var audioFormat = 1
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val size = intLe(bytes, offset + 4)
            val body = offset + 8
            if (size < 0 || body + size > bytes.size) {
                // Truncated file: take what is there instead of failing the take.
                if (id == "data") {
                    dataOffset = body
                    dataSize = bytes.size - body
                }
                break
            }
            when (id) {
                "fmt " -> {
                    audioFormat = shortLe(bytes, body)
                    channels = shortLe(bytes, body + 2)
                    sampleRate = intLe(bytes, body + 4)
                    bits = shortLe(bytes, body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataSize = size
                }
            }
            offset = body + size + (size and 1)
        }
        require(sampleRate > 0 && channels > 0 && bits > 0 && dataOffset >= 0) {
            "WAV header is missing fmt/data chunks"
        }
        val effectiveFormat = if (audioFormat == 0xFFFE) 1 else audioFormat
        require(effectiveFormat == 1 || effectiveFormat == 3) {
            "unsupported WAV encoding: $audioFormat"
        }
        val bytesPerSample = bits / 8
        require(bytesPerSample in 1..4) { "unsupported WAV bit depth: $bits" }
        val frameBytes = bytesPerSample * channels
        val frames = dataSize / frameBytes
        val samples = FloatArray(frames)
        var cursor = dataOffset
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += readSample(bytes, cursor, bits, effectiveFormat)
                cursor += bytesPerSample
            }
            samples[frame] = sum / channels
        }
        return WavData(samples = samples, sampleRate = sampleRate, channels = channels, bitsPerSample = bits)
    }

    private fun readSample(bytes: ByteArray, offset: Int, bits: Int, format: Int): Float = when {
        format == 3 && bits == 32 -> Float.fromBits(intLe(bytes, offset))
        bits == 16 -> shortLe(bytes, offset).toFloat() / 32768f
        bits == 8 -> (bytes[offset].toInt() and 0xFF).toFloat() / 128f - 1f
        bits == 24 -> {
            val raw = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16)
            val signed = if (raw and 0x800000 != 0) raw - 0x1000000 else raw
            signed / 8388608f
        }
        bits == 32 -> intLe(bytes, offset).toFloat() / 2147483648f
        else -> 0f
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        val builder = StringBuilder(length)
        for (i in 0 until length) builder.append((bytes[offset + i].toInt() and 0xFF).toChar())
        return builder.toString()
    }

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(bytes: ByteArray, offset: Int): Int {
        val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }
}
