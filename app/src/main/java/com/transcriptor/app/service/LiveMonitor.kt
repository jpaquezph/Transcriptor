package com.transcriptor.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * @param partial texto de la frase que se está diciendo; todavía puede cambiar.
 * @param stopping ya no se graba, pero falta transcribir lo que quedó pendiente.
 * @param ready el modelo terminó de cargar. Hasta entonces se graba, pero no aparece texto.
 */
data class LiveState(
    val id: String,
    val elapsedMs: Long,
    val stopping: Boolean,
    val partial: String = "",
    val ready: Boolean = false,
)

object LiveMonitor {
    private val _state = MutableStateFlow<LiveState?>(null)
    val state = _state.asStateFlow()

    fun update(value: LiveState?) {
        _state.value = value
    }

    /** Atómico: reemplazar el estado entero podría pisar un pedido de detener. */
    fun setElapsed(elapsedMs: Long) {
        _state.update { it?.copy(elapsedMs = elapsedMs) }
    }

    fun setReady() {
        _state.update { it?.copy(ready = true) }
    }

    fun setPartial(text: String) {
        _state.update { it?.copy(partial = text) }
    }

    fun requestStop() {
        _state.update { it?.copy(stopping = true) }
    }

    fun isStopRequested() = _state.value?.stopping != false
}
