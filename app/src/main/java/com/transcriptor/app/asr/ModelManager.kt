package com.transcriptor.app.asr

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class NotEnoughSpaceException(val neededMb: Int) : IOException("Faltan $neededMb MB")

class ModelManager(context: Context) {
    private val root = File(context.filesDir, "models")
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    private val _active = MutableStateFlow(loadActive())
    val active: StateFlow<SpeechModel?> = _active.asStateFlow()

    fun dir(model: SpeechModel) = File(root, model.id)

    fun isInstalled(model: SpeechModel) =
        model.files.all { File(dir(model), it.name).length() == it.size }

    private fun loadActive(): SpeechModel? {
        val offline = SpeechModel.entries.filterNot { it.live }
        val saved = offline.firstOrNull { it.id == prefs.getString(KEY_ACTIVE, null) }
        return saved?.takeIf(::isInstalled) ?: offline.firstOrNull(::isInstalled)
    }

    fun select(model: SpeechModel) {
        prefs.edit().putString(KEY_ACTIVE, model.id).apply()
        _active.value = model
    }

    /** Si se interrumpe, la siguiente llamada continúa desde los archivos `.part`. */
    suspend fun download(model: SpeechModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = dir(model).apply { mkdirs() }
        val pending = model.files.sumOf { (it.size - partialLength(target, it)).coerceAtLeast(0) }
        // Margen: conviene que quede espacio para audios y transcripciones.
        val needed = pending + 100_000_000
        if (StatFs(target.path).availableBytes < needed) {
            throw NotEnoughSpaceException((needed / 1_000_000).toInt())
        }
        var finished = 0L
        for (file in model.files) {
            downloadFile(model, file, target) { bytes ->
                onProgress((finished + bytes).toFloat() / model.totalBytes)
            }
            finished += file.size
        }
        if (!model.live) select(model)
    }

    private fun partialLength(dir: File, file: ModelFile): Long {
        val done = File(dir, file.name)
        return if (done.length() == file.size) file.size else File(dir, "${file.name}.part").length()
    }

    private suspend fun downloadFile(
        model: SpeechModel,
        file: ModelFile,
        dir: File,
        onBytes: (Long) -> Unit,
    ) {
        val done = File(dir, file.name)
        if (done.length() == file.size) {
            onBytes(file.size)
            return
        }
        val part = File(dir, "${file.name}.part")
        if (part.length() < file.size) fetch(model.url(file), part, onBytes)

        if (part.length() != file.size || sha256(part) != file.sha256) {
            part.delete()
            throw IOException("Archivo dañado: ${file.name}")
        }
        if (!part.renameTo(done)) throw IOException("No se pudo guardar ${file.name}")
    }

    private suspend fun fetch(url: String, part: File, onBytes: (Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            val resumeFrom = part.length()
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }
            // Un 200 significa que el servidor ignoró el Range y manda el archivo entero.
            val append = code == HttpURLConnection.HTTP_PARTIAL
            var written = if (append) resumeFrom else 0
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_ACTIVE = "active"
    }
}
