package com.transcriptor.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Remuestreo por sinc con ventana, en streaming. Una interpolación lineal dejaría aliasing al
 * bajar de 44,1/48 kHz a 16 kHz, y eso se nota en las consonantes.
 */
class Resampler(private val inputRate: Int, private val outputRate: Int = 16_000) {
    private val step = inputRate.toDouble() / outputRate
    private val cutoff = min(1.0, outputRate.toDouble() / inputRate)
    private val halfWidth = ceil(ZERO_CROSSINGS / cutoff).toInt()

    private val table = FloatArray(ZERO_CROSSINGS * RESOLUTION + 2) { i ->
        val x = i.toDouble() / RESOLUTION
        val sinc = if (i == 0) 1.0 else sin(PI * x) / (PI * x)
        val window = 0.5 + 0.5 * cos(PI * x / ZERO_CROSSINGS)
        (cutoff * sinc * window).toFloat()
    }

    private var buffer = FloatArray(0)
    private var position = 0.0

    fun process(input: FloatArray): FloatArray {
        if (inputRate == outputRate) return input
        // Se conservan muestras previas: el filtro mira hacia atrás y hacia adelante.
        val merged = buffer + input
        val available = merged.size - halfWidth
        val count = ceil((available - position) / step).toInt().coerceAtLeast(0)
        val output = FloatArray(count)
        for (i in 0 until count) {
            output[i] = interpolate(merged, position + i * step)
        }
        position += count * step
        val keepFrom = (position.toInt() - halfWidth).coerceIn(0, merged.size)
        buffer = merged.copyOfRange(keepFrom, merged.size)
        position -= keepFrom
        return output
    }

    private fun interpolate(samples: FloatArray, at: Double): Float {
        val first = ceil(at - halfWidth).toInt().coerceAtLeast(0)
        val last = min((at + halfWidth).toInt(), samples.size - 1)
        var sum = 0f
        for (i in first..last) {
            val index = (abs(at - i) * cutoff * RESOLUTION).toInt()
            if (index < table.size) sum += samples[i] * table[index]
        }
        return sum
    }

    private companion object {
        const val ZERO_CROSSINGS = 8
        const val RESOLUTION = 256
    }
}
