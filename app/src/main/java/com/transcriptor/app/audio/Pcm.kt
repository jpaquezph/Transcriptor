package com.transcriptor.app.audio

import android.media.AudioFormat
import java.nio.ByteBuffer

data class PcmFormat(val sampleRate: Int, val channels: Int, val encoding: Int)

/** Promedia los canales. Algunos decodificadores entregan float en vez de 16 bits. */
fun toMono(buffer: ByteBuffer, format: PcmFormat): FloatArray {
    val interleaved = if (format.encoding == AudioFormat.ENCODING_PCM_FLOAT) {
        val floats = buffer.asFloatBuffer()
        FloatArray(floats.remaining()) { floats.get(it) }
    } else {
        val shorts = buffer.asShortBuffer()
        FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }
    return mixToMono(interleaved, format.channels)
}

fun mixToMono(interleaved: FloatArray, channels: Int): FloatArray {
    if (channels <= 1) return interleaved
    return FloatArray(interleaved.size / channels) { frame ->
        var sum = 0f
        for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
        sum / channels
    }
}
