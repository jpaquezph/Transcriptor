package com.transcriptor.app.audio

import kotlin.math.max
import kotlin.math.min

/** Buffer circular de las últimas muestras, direccionado por posición absoluta en el audio. */
class SampleHistory(capacity: Int) {
    private val buffer = FloatArray(capacity)
    var total = 0L
        private set

    fun add(samples: FloatArray) {
        for (sample in samples) {
            buffer[(total % buffer.size).toInt()] = sample
            total++
        }
    }

    /** Lo que quede fuera de lo disponible se recorta. */
    fun range(from: Long, to: Long): FloatArray {
        val start = max(from, max(0L, total - buffer.size))
        val end = min(to, total)
        if (end <= start) return FloatArray(0)
        return FloatArray((end - start).toInt()) { buffer[((start + it) % buffer.size).toInt()] }
    }
}
