package com.yuushilabs.pianosheet.core.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Streaming 16-bit PCM WAV writer.
 *
 * The recording path writes the microphone samples exactly as captured — no
 * gain, no noise removal (spec §20.1). The header is patched with the final
 * sizes when the take is closed, so an interrupted recording still leaves a
 * playable (if shorter) file.
 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private val scratch = ByteArray(4096)
    private var closed = false

    init {
        file.parentFile?.mkdirs()
        raf.setLength(0)
        raf.write(header(0))
        raf.seek(HEADER_SIZE.toLong())
    }

    fun write(samples: FloatArray, count: Int = samples.size) {
        if (closed) return
        var index = 0
        while (index < count) {
            val block = minOf(count - index, scratch.size / 2)
            for (i in 0 until block) {
                val value = (samples[index + i].coerceIn(-1f, 1f) * 32767f).toInt()
                scratch[i * 2] = (value and 0xFF).toByte()
                scratch[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            raf.write(scratch, 0, block * 2)
            dataBytes += block * 2L
            index += block
        }
    }

    val bytesWritten: Long get() = dataBytes

    val durationMs: Long
        get() = if (sampleRate <= 0) 0 else dataBytes * 1000L / (sampleRate.toLong() * channels * 2L)

    override fun close() {
        if (closed) return
        closed = true
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.setLength(HEADER_SIZE + dataBytes)
        raf.close()
    }

    private fun header(dataSize: Long): ByteArray {
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(HEADER_SIZE)
        writeAscii(header, 0, "RIFF")
        writeInt(header, 4, (36 + dataSize).toInt())
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeInt(header, 16, 16)
        writeShort(header, 20, 1) // PCM
        writeShort(header, 22, channels)
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, channels * 2)
        writeShort(header, 34, 16)
        writeAscii(header, 36, "data")
        writeInt(header, 40, dataSize.toInt())
        return header
    }

    private fun writeAscii(target: ByteArray, offset: Int, text: String) {
        for (i in text.indices) target[offset + i] = text[i].code.toByte()
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    companion object {
        const val HEADER_SIZE = 44
    }
}
