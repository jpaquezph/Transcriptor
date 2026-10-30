package com.transcriptor.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** @param fraction de 0 a 1; null si el archivo no informa su duración. */
data class TranscriptionProgress(val id: String, val fraction: Float?, val remainingMs: Long?)

/** Une el servicio con la interfaz, que vive y muere por su cuenta. */
object TranscriptionMonitor {
    private val _progress = MutableStateFlow<TranscriptionProgress?>(null)
    val progress = _progress.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)

    fun update(value: TranscriptionProgress?) {
        _progress.value = value
    }

    fun requestCancel() = cancelRequested.set(true)

    fun clearCancel() = cancelRequested.set(false)

    fun isCancelRequested() = cancelRequested.get()
}
