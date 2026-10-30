package com.transcriptor.app.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.transcriptor.app.audio.AudioDecoder
import com.transcriptor.app.audio.SampleHistory
import com.transcriptor.app.data.Segment
import java.io.Closeable
import java.io.File

/**
 * El audio pasa primero por un detector de voz: Whisper solo procesa ventanas de 30 s, y cortar
 * en las pausas evita partir palabras, ahorra tiempo en los silencios y da la marca de tiempo
 * de cada frase.
 */
class Transcriber(assets: AssetManager, model: SpeechModel, modelDir: File) : Closeable {
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDir, model.encoder.name).path,
                    decoder = File(modelDir, model.decoder.name).path,
                    language = LANGUAGE,
                    task = "transcribe",
                ),
                tokens = File(modelDir, model.tokens.name).path,
                numThreads = threads,
                modelType = "whisper",
            ),
        ),
    )

    private val vad = Vad(
        assetManager = assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.4f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                maxSpeechDuration = 25f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private var history = SampleHistory(HISTORY_SAMPLES)
    private var previousEnd = 0L
    private val window = FloatArray(WINDOW)
    private var filled = 0

    /** @return false si se canceló; lo ya entregado a [onSegment] sigue siendo válido. */
    fun transcribe(
        audio: File,
        isCancelled: () -> Boolean,
        onPosition: (Long) -> Unit,
        onSegment: (Segment) -> Unit,
    ): Boolean {
        begin()
        val finished = AudioDecoder(audio).decode(isCancelled) { samples, positionMs ->
            feed(samples, isCancelled, onSegment)
            onPosition(positionMs)
        }
        if (!finished) return false
        finish(isCancelled, onSegment)
        return !isCancelled()
    }

    private fun begin() {
        vad.reset()
        history = SampleHistory(HISTORY_SAMPLES)
        previousEnd = 0
        filled = 0
    }

    private fun feed(samples: FloatArray, isCancelled: () -> Boolean, onSegment: (Segment) -> Unit) {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(WINDOW - filled, samples.size - offset)
            samples.copyInto(window, filled, offset, offset + count)
            filled += count
            offset += count
            if (filled == WINDOW) {
                val chunk = window.copyOf()
                history.add(chunk)
                vad.acceptWaveform(chunk)
                filled = 0
                drain(isCancelled, onSegment)
            }
        }
    }

    private fun finish(isCancelled: () -> Boolean, onSegment: (Segment) -> Unit) {
        vad.flush()
        drain(isCancelled, onSegment)
    }

    private fun drain(isCancelled: () -> Boolean, onSegment: (Segment) -> Unit) {
        while (!vad.empty() && !isCancelled()) {
            val speech = vad.front()
            // El detector reacciona tarde y se come el inicio de las frases: se agrega un margen,
            // sin pisar la frase anterior para no transcribir dos veces lo mismo.
            val from = maxOf(speech.start.toLong() - PADDING_BEFORE, previousEnd)
            val to = minOf(speech.start.toLong() + speech.samples.size + PADDING_AFTER, history.total)
            previousEnd = to
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(history.range(from, to), SAMPLE_RATE)
                recognizer.decode(stream)
                TextCleaner.clean(recognizer.getResult(stream).text)?.let { text ->
                    onSegment(
                        Segment(
                            startMs = from * 1000L / SAMPLE_RATE,
                            endMs = to * 1000L / SAMPLE_RATE,
                            text = text,
                        ),
                    )
                }
            } finally {
                stream.release()
            }
            vad.pop()
        }
    }

    override fun close() {
        recognizer.release()
        vad.release()
    }

    private companion object {
        /** Whisper no distingue variantes regionales: "es" cubre también Latinoamérica. */
        const val LANGUAGE = "es"
        const val SAMPLE_RATE = 16_000
        const val WINDOW = 512
        const val PADDING_BEFORE = SAMPLE_RATE * 4 / 10
        const val PADDING_AFTER = SAMPLE_RATE * 2 / 10

        // Una frase llega a 25 s como máximo; el resto es margen para el retardo del detector.
        const val HISTORY_SAMPLES = SAMPLE_RATE * 32
    }
}
