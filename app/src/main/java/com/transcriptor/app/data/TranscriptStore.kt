package com.transcriptor.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Cada transcripción es un JSON en disco más una copia de su audio. La copia es necesaria
 * porque el permiso sobre un archivo compartido desde otra app caduca al cerrar la pantalla.
 */
class TranscriptStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val transcriptsDir = File(context.filesDir, "transcripts").apply { mkdirs() }
    private val audiosDir = File(context.filesDir, "audios").apply { mkdirs() }

    private val _items = MutableStateFlow(loadAll())
    val items: StateFlow<List<Transcript>> = _items.asStateFlow()

    private fun loadAll(): List<Transcript> =
        transcriptsDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<Transcript>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun get(id: String): Transcript? = _items.value.firstOrNull { it.id == id }

    fun audioFile(transcript: Transcript) = File(audiosDir, transcript.audioFile)

    /** La cola se atiende por orden de llegada. */
    fun nextQueued(): Transcript? =
        _items.value.filter { it.status == TranscriptStatus.QUEUED }.minByOrNull { it.createdAt }

    fun import(uri: Uri): Transcript {
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        val id = UUID.randomUUID().toString()
        val extension = name.substringAfterLast('.', "").take(8).lowercase()
        val audioName = if (extension.isEmpty()) id else "$id.$extension"
        val target = File(audiosDir, audioName)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Sin datos: $uri")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        val transcript = Transcript(
            id = id,
            title = name.substringBeforeLast('.', name),
            createdAt = System.currentTimeMillis(),
            audioFile = audioName,
        )
        save(transcript)
        return transcript
    }

    fun createRecording(title: String): Transcript {
        val id = UUID.randomUUID().toString()
        val transcript = Transcript(
            id = id,
            title = title,
            createdAt = System.currentTimeMillis(),
            audioFile = "$id.wav",
            status = TranscriptStatus.TRANSCRIBING,
        )
        save(transcript)
        return transcript
    }

    @Synchronized
    fun save(transcript: Transcript) {
        val target = File(transcriptsDir, "${transcript.id}.json")
        val temp = File(transcriptsDir, "${transcript.id}.tmp")
        temp.writeText(json.encodeToString(transcript))
        temp.renameTo(target)
        _items.value = (_items.value.filterNot { it.id == transcript.id } + transcript)
            .sortedByDescending { it.createdAt }
    }

    /** Para quien trabaja con una copia: la persona puede renombrar mientras se transcribe. */
    @Synchronized
    fun saveWork(transcript: Transcript) {
        save(transcript.copy(title = get(transcript.id)?.title ?: transcript.title))
    }

    @Synchronized
    fun rename(id: String, title: String) {
        get(id)?.let { save(it.copy(title = title)) }
    }

    @Synchronized
    fun delete(transcript: Transcript) {
        File(transcriptsDir, "${transcript.id}.json").delete()
        audioFile(transcript).delete()
        _items.value = _items.value.filterNot { it.id == transcript.id }
    }

    /** Un trabajo que quedó en curso al morir el proceso no se va a terminar solo. */
    fun markInterrupted() {
        _items.value.filter { it.status == TranscriptStatus.TRANSCRIBING }
            .forEach { save(it.copy(status = TranscriptStatus.INTERRUPTED)) }
    }

    fun requeue(transcript: Transcript) {
        save(transcript.copy(status = TranscriptStatus.QUEUED, error = null, segments = emptyList()))
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
