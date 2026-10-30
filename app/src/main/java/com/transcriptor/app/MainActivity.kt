package com.transcriptor.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transcriptor.app.asr.SpeechModel
import com.transcriptor.app.ui.AppViewModel
import com.transcriptor.app.ui.EngineScreen
import com.transcriptor.app.ui.HomeScreen
import com.transcriptor.app.ui.TranscriptScreen
import com.transcriptor.app.ui.TranscriptorTheme
import com.transcriptor.app.ui.UiEvent
import com.transcriptor.app.ui.WhatsApp
import kotlinx.coroutines.launch

private const val ROUTE_HOME = "home"
private const val ROUTE_ENGINE = "engine"
private const val ROUTE_TRANSCRIPT = "t:"

// El .opus de WhatsApp suele llegar como octet-stream desde los gestores de archivos.
private val PICKABLE_TYPES = arrayOf("audio/*", "video/*", "application/ogg", "application/octet-stream")

/** @param input carpeta en la que abrir el selector; null deja elegir al sistema. */
private class PickAudio : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: Context, input: Uri?) =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, PICKABLE_TYPES)
            .apply { if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input) }

    override fun parseResult(resultCode: Int, intent: Intent?) =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            TranscriptorTheme {
                val transcripts by viewModel.transcripts.collectAsStateWithLifecycle()
                val progress by viewModel.progress.collectAsStateWithLifecycle()
                val live by viewModel.live.collectAsStateWithLifecycle()
                val active by viewModel.activeModel.collectAsStateWithLifecycle()
                val download by viewModel.download.collectAsStateWithLifecycle()
                var route by rememberSaveable { mutableStateOf(ROUTE_HOME) }
                val snackbar = remember { SnackbarHostState() }
                val importFailed = stringResource(R.string.import_failed)
                val micNeeded = stringResource(R.string.mic_needed)
                val scope = rememberCoroutineScope()

                val startLive = {
                    val date = DateFormat.getTimeFormat(this).format(System.currentTimeMillis())
                    viewModel.startLive(getString(R.string.live_title, date))
                }
                val askMicrophone = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) startLive() else scope.launch { snackbar.showSnackbar(micNeeded) }
                }

                val askNotifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { /* Sin notificaciones la transcripción funciona igual. */ }
                val pickAudio = rememberLauncherForActivityResult(PickAudio()) { uri ->
                    uri?.let(viewModel::import)
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.events.collect { event ->
                        when (event) {
                            is UiEvent.Open -> route = ROUTE_TRANSCRIPT + event.id
                            UiEvent.ImportFailed -> {
                                // El aviso vive en Inicio; un archivo compartido puede llegar en otra pantalla.
                                route = ROUTE_HOME
                                snackbar.showSnackbar(importFailed)
                            }
                        }
                    }
                }
                LifecycleResumeEffect(Unit) {
                    viewModel.resumeQueue()
                    onPauseOrDispose { }
                }

                BackHandler(enabled = route != ROUTE_HOME) { route = ROUTE_HOME }

                val opened = route.takeIf { it.startsWith(ROUTE_TRANSCRIPT) }
                    ?.removePrefix(ROUTE_TRANSCRIPT)
                    ?.let { id -> transcripts.firstOrNull { it.id == id } }
                when {
                    opened != null -> TranscriptScreen(
                        transcript = opened,
                        audioFile = (application as TranscriptorApp).store.audioFile(opened),
                        progress = progress?.takeIf { it.id == opened.id },
                        live = live?.takeIf { it.id == opened.id },
                        viewModel = viewModel,
                        onBack = { route = ROUTE_HOME },
                    )
                    route == ROUTE_ENGINE -> EngineScreen(
                        active = active,
                        download = download,
                        isInstalled = viewModel::isInstalled,
                        onDownload = viewModel::download,
                        onSelect = viewModel::selectModel,
                        onBack = { route = ROUTE_HOME },
                    )
                    else -> HomeScreen(
                        transcripts = transcripts,
                        progress = progress,
                        engineReady = active != null,
                        canRecord = live == null && progress == null,
                        snackbar = snackbar,
                        onRecord = {
                            when {
                                !viewModel.isInstalled(SpeechModel.LIVE) -> route = ROUTE_ENGINE
                                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED -> startLive()
                                else -> askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        hasWhatsApp = WhatsApp.launchIntent(packageManager) != null,
                        onOpenWhatsApp = {
                            WhatsApp.launchIntent(packageManager)?.let(::startActivity)
                        },
                        onPickVoiceNotes = { pickAudio.launch(WhatsApp.voiceNotesUri(packageManager)) },
                        onPickFiles = { pickAudio.launch(null) },
                        onOpen = { route = ROUTE_TRANSCRIPT + it.id },
                        onRename = viewModel::rename,
                        onDelete = viewModel::delete,
                        onEngine = { route = ROUTE_ENGINE },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let(viewModel::import)
    }
}
