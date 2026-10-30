package com.transcriptor.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.ByteOrder

class UnsupportedAudioException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodifica con los códecs del sistema, que es lo que permite aceptar MP3, M4A/AAC, WAV, FLAC,
 * OGG, AMR y las notas de voz de WhatsApp (.opus) sin empaquetar FFmpeg. También sirve para
 * video: se usa su primera pista de audio.
 */
class AudioDecoder(private val file: File) {

    fun durationMs(): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (_: RuntimeException) {
            0
        } finally {
            retriever.release()
        }
    }

    /**
     * Entrega mono a 16 kHz. [onSamples] se llama de forma síncrona, así que un motor lento frena
     * la decodificación en lugar de acumular el audio entero en memoria.
     *
     * @return false si [isCancelled] interrumpió la decodificación.
     */
    fun decode(isCancelled: () -> Boolean, onSamples: (FloatArray, Long) -> Unit): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(file.path)
            } catch (e: Exception) {
                throw UnsupportedAudioException("No se pudo leer ${file.name}", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("Sin pista de audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            codec = try {
                MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (e: Exception) {
                throw UnsupportedAudioException("Sin decodificador para $mime", e)
            }

            var pcm = PcmFormat(
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                encoding = AudioFormat.ENCODING_PCM_16BIT,
            )
            var resampler = Resampler(pcm.sampleRate)
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (true) {
                if (isCancelled()) return false
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = codec.outputFormat
                        val next = PcmFormat(
                            sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            encoding = if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                changed.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            },
                        )
                        if (next.sampleRate != pcm.sampleRate) resampler = Resampler(next.sampleRate)
                        pcm = next
                    }
                    index >= 0 -> {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset).limit(info.offset + info.size)
                            val mono = toMono(buffer.order(ByteOrder.nativeOrder()), pcm)
                            val samples = resampler.process(mono)
                            if (samples.isNotEmpty()) onSamples(samples, info.presentationTimeUs / 1000)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
        } catch (e: MediaCodec.CodecException) {
            throw UnsupportedAudioException("Error del decodificador", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
