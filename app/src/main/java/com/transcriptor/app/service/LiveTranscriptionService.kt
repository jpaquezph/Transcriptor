package com.transcriptor.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.transcriptor.app.MainActivity
import com.transcriptor.app.R
import com.transcriptor.app.TranscriptorApp
import com.transcriptor.app.asr.LiveRecognizer
import com.transcriptor.app.audio.WavWriter
import com.transcriptor.app.data.Segment
import com.transcriptor.app.data.Transcript
import com.transcriptor.app.data.TranscriptError
import com.transcriptor.app.data.TranscriptStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Graba del micrófono y transcribe a la vez. La captura y el motor corren por separado: si el
 * motor va más lento que la voz, el audio espera en el canal en lugar de perderse.
 */
class LiveTranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }

        val id = intent?.getStringExtra(EXTRA_ID)
        val transcript = id?.let { (application as TranscriptorApp).store.get(it) }
        if (transcript == null) {
            LiveMonitor.update(null)
            stopSelf(startId)
        } else if (worker == null) {
            worker = scope.launch {
                run(transcript)
                LiveMonitor.update(null)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run(initial: Transcript) {
        val app = application as TranscriptorApp
        val store = app.store
        var current = initial
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transcriptor:live")
        wakeLock.acquire(TimeUnit.HOURS.toMillis(6))
        try {
            val pending = Channel<FloatArray>(Channel.UNLIMITED)
            val audio = store.audioFile(initial)
            // Cargar el modelo tarda varios segundos: se graba desde ya y el audio espera en el canal.
            val capture = scope.launch(Dispatchers.IO) {
                try {
                    val elapsed = record(audio, pending)
                    current = current.copy(durationMs = elapsed)
                    pending.close()
                } catch (e: Exception) {
                    pending.close(e)
                }
            }
            val recognizer = app.liveEngine.preload().await()
            recognizer.begin()
            LiveMonitor.setReady()
            val onPhrase = { segment: Segment ->
                current = current.copy(segments = current.segments + segment)
                store.saveWork(current)
            }
            for (samples in pending) recognizer.feed(samples, LiveMonitor::setPartial, onPhrase)
            capture.join()
            recognizer.finish(onPhrase)
            store.saveWork(current.copy(status = TranscriptStatus.DONE))
        } catch (e: Exception) {
            Log.e(TAG, "Falló la grabación", e)
            LiveMonitor.requestStop()
            store.saveWork(current.copy(status = TranscriptStatus.FAILED, error = TranscriptError.GENERIC))
        } finally {
            wakeLock.release()
        }
        if (store.nextQueued() != null) runCatching { TranscriptionService.start(this) }
    }

    /** Graba hasta que se pida detener. @return duración en milisegundos. */
    @SuppressLint("MissingPermission") // Se verifica antes de iniciar el servicio.
    private suspend fun record(file: File, pending: Channel<FloatArray>): Long {
        val rate = LiveRecognizer.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, rate),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Micrófono no disponible" }
        var samplesRead = 0L
        try {
            WavWriter(file, rate).use { wav ->
                val buffer = ShortArray(rate / 10)
                recorder.startRecording()
                while (!LiveMonitor.isStopRequested()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    check(read >= 0) { "Error del micrófono: $read" }
                    if (read == 0) continue
                    wav.write(buffer, read)
                    pending.send(FloatArray(read) { buffer[it] / 32768f })
                    samplesRead += read
                    LiveMonitor.setElapsed(samplesRead * 1000 / rate)
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        return samplesRead * 1000 / rate
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_foreground)
            .setContentTitle(getString(R.string.notif_live))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "LiveTranscription"
        private const val CHANNEL = "transcription"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_ID = "id"

        fun start(context: Context, id: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveTranscriptionService::class.java).putExtra(EXTRA_ID, id),
            )
        }
    }
}
