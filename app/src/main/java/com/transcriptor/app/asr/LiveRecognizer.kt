package com.transcriptor.app.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.transcriptor.app.data.Segment
import java.io.File

/**
 * Reconocimiento por streaming: a diferencia de Whisper, el texto crece a medida que entra el
 * audio. Una pausa cierra la frase en curso, que es lo que se guarda como segmento.
 */
class LiveRecognizer(model: SpeechModel, modelDir: File) {
    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, model.encoder.name).path,
                    decoder = File(modelDir, model.decoder.name).path,
                    joiner = File(modelDir, model.joiner!!.name).path,
                ),
                tokens = File(modelDir, model.tokens.name).path,
                numThreads = 2,
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 0.8f, 0f),
                rule3 = EndpointRule(false, 0f, MAX_PHRASE_SECONDS),
            ),
            enableEndpoint = true,
        ),
    )
    private lateinit var stream: OnlineStream
    private var received = 0L
    private var phraseStart = 0L

    /** Prepara una grabación nueva; el modelo cargado se reutiliza entre grabaciones. */
    fun begin() {
        stream = recognizer.createStream()
        received = 0
        phraseStart = 0
    }

    /** Recibe mono a 16 kHz. [onPartial] lleva el texto de la frase en curso. */
    fun feed(samples: FloatArray, onPartial: (String) -> Unit, onPhrase: (Segment) -> Unit) {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        received += samples.size
        decodeReady()
        val text = currentText()
        if (recognizer.isEndpoint(stream)) {
            closePhrase(text, onPhrase)
            onPartial("")
        } else {
            onPartial(text)
        }
    }

    /** Lo último dicho sigue dentro del modelo: el silencio final lo empuja hacia afuera. */
    fun finish(onPhrase: (Segment) -> Unit) {
        stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
        stream.inputFinished()
        decodeReady()
        closePhrase(currentText(), onPhrase)
        stream.release()
    }

    private fun decodeReady() {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

    private fun currentText() = recognizer.getResult(stream).text.trim()

    private fun closePhrase(text: String, onPhrase: (Segment) -> Unit) {
        if (text.isNotEmpty()) {
            onPhrase(Segment(phraseStart * 1000 / SAMPLE_RATE, received * 1000 / SAMPLE_RATE, text))
        }
        recognizer.reset(stream)
        phraseStart = received
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MAX_PHRASE_SECONDS = 15f
    }
}
