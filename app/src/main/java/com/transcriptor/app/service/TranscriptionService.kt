package com.transcriptor.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.transcriptor.app.MainActivity
import com.transcriptor.app.R
import com.transcriptor.app.TranscriptorApp
import com.transcriptor.app.asr.Transcriber
import com.transcriptor.app.audio.AudioDecoder
import com.transcriptor.app.audio.UnsupportedAudioException
import com.transcriptor.app.data.Transcript
import com.transcriptor.app.data.TranscriptError
import com.transcriptor.app.data.TranscriptStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Transcribir un audio largo tarda minutos: debe seguir aunque la persona cambie de app o apague
 * la pantalla, por eso es un servicio en primer plano con wake lock.
 */
class TranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var lastStartId = 0
    private lateinit var notifications: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val preparing = notification(getString(R.string.notif_preparing), null)
        // Sin ServiceCompat: descarta mediaProcessing y Android rechaza el servicio por no tener tipo.
        when {
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(NOTIFICATION_ID, preparing, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(NOTIFICATION_ID, preparing, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIFICATION_ID, preparing)
        }

        if (worker == null) {
            worker = scope.launch {
                withContext(Dispatchers.Default) { processQueue() }
                worker = null
                TranscriptionMonitor.update(null)
                stopSelf(lastStartId)
            }
        }
        return START_NOT_STICKY
    }

    // Android corta estos servicios a las 6 horas (API 35+): se conserva lo transcrito hasta ahí.
    override fun onTimeout(startId: Int, fgsType: Int) {
        TranscriptionMonitor.requestCancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun processQueue() {
        val app = application as TranscriptorApp
        val store = app.store
        val model = app.models.active.value
        if (model == null) {
            store.items.value.filter { it.status == TranscriptStatus.QUEUED }.forEach {
                store.save(it.copy(status = TranscriptStatus.FAILED, error = TranscriptError.MODEL_MISSING))
            }
            return
        }
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transcriptor:transcription")
        wakeLock.acquire(TimeUnit.HOURS.toMillis(6))
        try {
            Transcriber(assets, model, app.models.dir(model)).use { transcriber ->
                while (true) {
                    val next = store.nextQueued() ?: break
                    TranscriptionMonitor.clearCancel()
                    process(next, transcriber)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el motor", e)
            store.items.value
                .filter { it.status == TranscriptStatus.QUEUED || it.status == TranscriptStatus.TRANSCRIBING }
                .forEach { store.save(it.copy(status = TranscriptStatus.FAILED, error = TranscriptError.GENERIC)) }
        } finally {
            wakeLock.release()
        }
    }

    private fun process(queued: Transcript, transcriber: Transcriber) {
        val store = (application as TranscriptorApp).store
        val audio = store.audioFile(queued)
        val duration = AudioDecoder(audio).durationMs()
        var current = queued.copy(status = TranscriptStatus.TRANSCRIBING, durationMs = duration)
        store.saveWork(current)

        val startedAt = SystemClock.elapsedRealtime()
        var lastPercent = -1
        val report = { positionMs: Long ->
            val fraction = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else null
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val remaining = fraction?.takeIf { it > 0.02f }?.let { (elapsed * (1 - it) / it).toLong() }
            TranscriptionMonitor.update(TranscriptionProgress(queued.id, fraction, remaining))
            val percent = fraction?.let { (it * 100).toInt() } ?: 0
            if (percent != lastPercent) {
                lastPercent = percent
                notifications.notify(NOTIFICATION_ID, notification(queued.title, fraction))
            }
        }
        report(0)

        val status = try {
            val finished = transcriber.transcribe(
                audio = audio,
                isCancelled = TranscriptionMonitor::isCancelRequested,
                onPosition = report,
                onSegment = { segment ->
                    current = current.copy(segments = current.segments + segment)
                    store.saveWork(current)
                },
            )
            if (finished) TranscriptStatus.DONE else TranscriptStatus.CANCELLED
        } catch (e: UnsupportedAudioException) {
            Log.w(TAG, "Audio no compatible", e)
            current = current.copy(error = TranscriptError.UNSUPPORTED_FORMAT)
            TranscriptStatus.FAILED
        } catch (e: Exception) {
            Log.e(TAG, "Falló la transcripción", e)
            current = current.copy(error = TranscriptError.GENERIC)
            TranscriptStatus.FAILED
        }
        store.saveWork(current.copy(status = status))
    }

    private fun notification(text: String, fraction: Float?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_foreground)
            .setContentTitle(getString(R.string.status_transcribing))
            .setContentText(text)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "TranscriptionService"
        private const val CHANNEL = "transcription"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TranscriptionService::class.java))
        }
    }
}
