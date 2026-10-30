package com.transcriptor.app.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Cargar el modelo de streaming tarda segundos. Se hace al abrir la app y se conserva, para que el
 * texto salga desde la primera palabra. Si el sistema necesita memoria, cierra el proceso entero.
 */
class LiveEngine(private val models: ModelManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loading: Deferred<LiveRecognizer>? = null

    @Synchronized
    fun preload(): Deferred<LiveRecognizer> {
        loading?.takeUnless { it.isCancelled }?.let { return it }
        return scope.async {
            LiveRecognizer(SpeechModel.LIVE, models.dir(SpeechModel.LIVE))
        }.also { loading = it }
    }

    fun preloadIfInstalled() {
        if (models.isInstalled(SpeechModel.LIVE)) preload()
    }
}
