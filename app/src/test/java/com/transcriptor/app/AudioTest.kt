package com.transcriptor.app

import com.transcriptor.app.audio.Resampler
import com.transcriptor.app.audio.SampleHistory
import com.transcriptor.app.audio.WavWriter
import com.transcriptor.app.audio.mixToMono
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTest {
    private fun sine(frequency: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * frequency * it / rate)).toFloat() }

    private fun rms(samples: FloatArray) =
        sqrt(samples.map { it * it }.sum() / samples.size)

    /** Entrega el audio en bloques irregulares, como un decodificador real. */
    private fun resample(input: FloatArray, rate: Int): FloatArray {
        val resampler = Resampler(rate)
        var output = FloatArray(0)
        var offset = 0
        val sizes = intArrayOf(1024, 4096, 333, 2048)
        var turn = 0
        while (offset < input.size) {
            val end = minOf(offset + sizes[turn++ % sizes.size], input.size)
            output += resampler.process(input.copyOfRange(offset, end))
            offset = end
        }
        return output
    }

    @Test
    fun `conserva duracion y amplitud de la voz`() {
        for (rate in intArrayOf(8_000, 22_050, 44_100, 48_000)) {
            val output = resample(sine(1_000.0, rate, 2.0), rate)
            assertEquals("duración a $rate Hz", 32_000.0, output.size.toDouble(), 64.0)
            val body = output.copyOfRange(1000, output.size - 1000)
            assertEquals("amplitud a $rate Hz", 0.5f / sqrt(2f), rms(body), 0.02f)
        }
    }

    @Test
    fun `conserva la frecuencia al remuestrear`() {
        val output = resample(sine(1_000.0, 48_000, 1.0), 48_000)
        val expected = sine(1_000.0, 16_000, 1.0)
        // Fase casi idéntica salvo por el retardo inicial del filtro: se compara el centro.
        val error = (4000 until 12000).map { output[it] - expected[it] }.toFloatArray()
        assertTrue("error rms ${rms(error)}", rms(error) < 0.02f)
    }

    @Test
    fun `elimina frecuencias que producirian aliasing`() {
        val output = resample(sine(12_000.0, 48_000, 1.0), 48_000)
        val body = output.copyOfRange(1000, output.size - 1000)
        assertTrue("rms ${rms(body)}", rms(body) < 0.02f)
    }

    @Test
    fun `no modifica audio que ya esta a 16 kHz`() {
        val input = sine(440.0, 16_000, 0.1)
        assertArrayEquals(input, Resampler(16_000).process(input), 0f)
    }

    @Test
    fun `el historial devuelve rangos absolutos aunque de la vuelta`() {
        val history = SampleHistory(4)
        history.add(floatArrayOf(0f, 1f, 2f))
        assertArrayEquals(floatArrayOf(1f, 2f), history.range(1, 3), 0f)
        history.add(floatArrayOf(3f, 4f, 5f))
        assertArrayEquals(floatArrayOf(3f, 4f), history.range(3, 5), 0f)
        assertArrayEquals("recorta lo ya perdido y lo futuro", floatArrayOf(2f, 3f, 4f, 5f), history.range(0, 99), 0f)
        assertEquals(0, history.range(5, 3).size)
    }

    @Test
    fun `el WAV grabado tiene una cabecera valida`() {
        val file = File.createTempFile("grabacion", ".wav")
        try {
            WavWriter(file, 16_000).use { it.write(shortArrayOf(1, -2, 3, 99), 3) }
            val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(44 + 6, bytes.capacity())
            assertEquals(44 + 6 - 8, bytes.getInt(4))
            assertEquals(16_000, bytes.getInt(24))
            assertEquals(6, bytes.getInt(40))
            assertEquals((-2).toShort(), bytes.getShort(46))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `promedia los canales`() {
        assertArrayEquals(floatArrayOf(0.5f, 0f), mixToMono(floatArrayOf(1f, 0f, -1f, 1f), 2), 0f)
    }
}
