package com.transcriptor.app.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM de 16 bits mono. Los tamaños de la cabecera se completan al cerrar. */
class WavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val output = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(header(0))
    }
    private var dataBytes = 0

    fun write(samples: ShortArray, count: Int) {
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        output.write(bytes.array())
        dataBytes += count * 2
    }

    override fun close() {
        output.seek(0)
        output.write(header(dataBytes))
        output.close()
    }

    private fun header(dataSize: Int): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(HEADER_SIZE - 8 + dataSize)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }.array()

    private companion object {
        const val HEADER_SIZE = 44
    }
}
