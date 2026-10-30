package com.transcriptor.app.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.transcriptor.app.TranscriptorApp
import com.transcriptor.app.asr.NotEnoughSpaceException
import com.transcriptor.app.asr.SpeechModel
import com.transcriptor.app.data.Transcript
import com.transcriptor.app.export.ExportFormat
import com.transcriptor.app.export.Exporter
import com.transcriptor.app.service.LiveMonitor
import com.transcriptor.app.service.LiveState
import com.transcriptor.app.service.LiveTranscriptionService
import com.transcriptor.app.service.TranscriptionMonitor
import com.transcriptor.app.service.TranscriptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val model: SpeechModel, val fraction: Float) : DownloadState
    data class NoSpace(val neededMb: Int) : DownloadState
    data object Failed : DownloadState
}

sealed interface UiEvent {
    data class Open(val id: String) : UiEvent
    data object ImportFailed : UiEvent
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TranscriptorApp
    private val store = app.store
    private val models = app.models

    val transcripts = store.items
    val progress = TranscriptionMonitor.progress
    val live = LiveMonitor.state
    val activeModel = models.active

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()
    private var downloadJob: Job? = null

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun isInstalled(model: SpeechModel) = models.isInstalled(model)

    fun rename(transcript: Transcript, title: String) = store.rename(transcript.id, title)

    fun selectModel(model: SpeechModel) = models.select(model)

    fun import(uri: Uri) {
        viewModelScope.launch {
            val transcript = withContext(Dispatchers.IO) { runCatching { store.import(uri) } }
            transcript.onSuccess {
                resumeQueue()
                if (models.active.value != null) _events.tryEmit(UiEvent.Open(it.id))
            }.onFailure {
                Log.w(TAG, "No se pudo importar $uri", it)
                _events.tryEmit(UiEvent.ImportFailed)
            }
        }
    }

    /** Arranca lo pendiente: el servicio no se puede iniciar con la app en segundo plano. */
    fun resumeQueue() {
        // Durante una grabación la cola espera: dos Whisper a la vez atrasarían el texto en vivo.
        if (models.active.value == null || store.nextQueued() == null || live.value != null) return
        runCatching { TranscriptionService.start(app) }
            .onFailure { Log.w(TAG, "Servicio no iniciado; se reintenta al volver a la app", it) }
    }

    fun cancelCurrent() = TranscriptionMonitor.requestCancel()

    fun startLive(title: String) {
        val recording = store.createRecording(title)
        LiveMonitor.update(LiveState(recording.id, 0, stopping = false))
        runCatching { LiveTranscriptionService.start(app, recording.id) }
            .onSuccess { _events.tryEmit(UiEvent.Open(recording.id)) }
            .onFailure {
                Log.w(TAG, "No se pudo iniciar la grabación", it)
                LiveMonitor.update(null)
                store.delete(recording)
            }
    }

    fun stopLive() = LiveMonitor.requestStop()

    fun retry(transcript: Transcript) {
        store.requeue(transcript)
        resumeQueue()
    }

    fun delete(transcript: Transcript) {
        if (progress.value?.id == transcript.id) TranscriptionMonitor.requestCancel()
        viewModelScope.launch(Dispatchers.IO) { store.delete(transcript) }
    }

    fun download(model: SpeechModel) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _download.value = DownloadState.Running(model, 0f)
            _download.value = try {
                models.download(model) { _download.value = DownloadState.Running(model, it) }
                if (model.live) app.liveEngine.preloadIfInstalled()
                resumeQueue()
                DownloadState.Idle
            } catch (e: NotEnoughSpaceException) {
                DownloadState.NoSpace(e.neededMb)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Falló la descarga", e)
                DownloadState.Failed
            }
        }
    }

    /** @return false si no se pudo escribir. */
    suspend fun saveExport(transcript: Transcript, format: ExportFormat, timestamps: Boolean, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openOutputStream(uri)!!.use {
                    Exporter.write(app, transcript, format, timestamps, it)
                }
            }.onFailure { Log.e(TAG, "Falló la exportación", it) }.isSuccess
        }

    private companion object {
        const val TAG = "AppViewModel"
    }
}
