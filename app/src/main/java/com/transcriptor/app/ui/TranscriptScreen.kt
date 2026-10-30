package com.transcriptor.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import com.transcriptor.app.R
import com.transcriptor.app.data.Segment
import com.transcriptor.app.data.Transcript
import com.transcriptor.app.data.TranscriptStatus
import com.transcriptor.app.export.ExportFormat
import com.transcriptor.app.export.Exporter
import com.transcriptor.app.export.formatClock
import com.transcriptor.app.export.plainText
import com.transcriptor.app.service.LiveState
import com.transcriptor.app.service.TranscriptionProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class CreateFile : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?) =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(
    transcript: Transcript,
    audioFile: File,
    progress: TranscriptionProgress?,
    live: LiveState?,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // El WAV de una grabación en curso todavía no tiene cabecera válida.
    val player = if (live == null) rememberAudioPlayer(audioFile) else null
    val hasText = transcript.segments.isNotEmpty()
    var exporting by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }

    val copiedMessage = stringResource(R.string.copied)
    val savedMessage = stringResource(R.string.export_saved)
    val failedMessage = stringResource(R.string.export_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        transcript.title,
                        Modifier.clickable(onClickLabel = stringResource(R.string.rename)) { renaming = true },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        enabled = hasText,
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(transcript.title, plainText(transcript.segments)),
                            )
                            scope.launch { snackbar.showSnackbar(copiedMessage) }
                        },
                    ) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy)) }
                    IconButton(
                        enabled = hasText,
                        onClick = {
                            scope.launch {
                                val intent = withContext(Dispatchers.IO) {
                                    Exporter.shareTextIntent(context, transcript)
                                }
                                context.startActivity(Exporter.chooser(context, intent))
                            }
                        },
                    ) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
                    IconButton(enabled = hasText, onClick = { exporting = true }) {
                        Icon(Icons.Outlined.FileDownload, stringResource(R.string.export))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { player?.let { PlayerBar(it, Modifier.navigationBarsPadding()) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (live != null) {
                LiveBanner(live, viewModel::stopLive)
            } else {
                StatusBanner(transcript, progress, viewModel)
            }
            SegmentList(
                segments = transcript.segments,
                partial = live?.partial.orEmpty(),
                positionMs = player?.positionMs ?: 0,
                isPlaying = player?.isPlaying == true,
                followLatest = live != null,
                showNoSpeech = transcript.status == TranscriptStatus.DONE,
                onSeek = {
                    player?.seekTo(it)
                    if (player?.isPlaying == false) player.togglePlay()
                },
            )
        }
    }

    if (renaming) {
        RenameDialog(
            title = transcript.title,
            onDismiss = { renaming = false },
            onConfirm = { viewModel.rename(transcript, it); renaming = false },
        )
    }

    if (exporting) {
        ExportSheet(
            transcript = transcript,
            onDismiss = { exporting = false },
            onSave = { format, timestamps, uri ->
                scope.launch {
                    val saved = viewModel.saveExport(transcript, format, timestamps, uri)
                    exporting = false
                    snackbar.showSnackbar(if (saved) savedMessage else failedMessage)
                }
            },
            onShare = { format, timestamps ->
                scope.launch {
                    val intent = withContext(Dispatchers.IO) {
                        runCatching { Exporter.shareFileIntent(context, transcript, format, timestamps) }
                    }.getOrNull()
                    exporting = false
                    if (intent != null) {
                        context.startActivity(Exporter.chooser(context, intent))
                    } else {
                        snackbar.showSnackbar(failedMessage)
                    }
                }
            },
        )
    }
}

@Composable
private fun LiveBanner(live: LiveState, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth().keepScreenOn().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (live.stopping) {
            Text(stringResource(R.string.live_finishing), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.live_recording, formatClock(live.elapsedMs)),
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = onStop) { Text(stringResource(R.string.stop)) }
            }
            Text(
                stringResource(if (live.ready) R.string.live_hint else R.string.live_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusBanner(
    transcript: Transcript,
    progress: TranscriptionProgress?,
    viewModel: AppViewModel,
) {
    val line = statusLine(transcript, progress) ?: return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            when (transcript.status) {
                TranscriptStatus.TRANSCRIBING -> TextButton(onClick = viewModel::cancelCurrent) {
                    Text(stringResource(R.string.cancel))
                }
                TranscriptStatus.CANCELLED, TranscriptStatus.INTERRUPTED, TranscriptStatus.FAILED ->
                    TextButton(onClick = { viewModel.retry(transcript) }) { Text(stringResource(R.string.retry)) }
                else -> Unit
            }
        }
        if (transcript.status == TranscriptStatus.TRANSCRIBING) {
            val fraction = progress?.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SegmentList(
    segments: List<Segment>,
    partial: String,
    positionMs: Long,
    isPlaying: Boolean,
    followLatest: Boolean,
    showNoSpeech: Boolean,
    onSeek: (Long) -> Unit,
) {
    if (segments.isEmpty() && partial.isEmpty()) {
        if (showNoSpeech) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_speech), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val listState = rememberLazyListState()
    val currentIndex = segments.indexOfLast { positionMs >= it.startMs }
    LaunchedEffect(currentIndex, isPlaying) {
        if (isPlaying && currentIndex >= 0) listState.animateScrollToItem(currentIndex)
    }
    LaunchedEffect(segments.size, partial, followLatest) {
        // Un offset enorme deja a la vista el final de la última frase, aunque ocupe varias líneas.
        if (followLatest) listState.scrollToItem(segments.size, 100_000)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        itemsIndexed(segments) { index, segment ->
            val highlighted = index == currentIndex && positionMs > 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(segment.startMs) }
                    .background(
                        if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(8.dp),
            ) {
                Text(
                    formatClock(segment.startMs),
                    Modifier.width(56.dp).padding(top = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(segment.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
        // Siempre presente: mantiene estable el índice al que sigue el desplazamiento automático.
        item {
            if (partial.isNotEmpty()) {
                Text(
                    partial,
                    Modifier.fillMaxWidth().padding(start = 64.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    transcript: Transcript,
    onDismiss: () -> Unit,
    onSave: (ExportFormat, Boolean, Uri) -> Unit,
    onShare: (ExportFormat, Boolean) -> Unit,
) {
    var format by rememberSaveable { mutableStateOf(ExportFormat.PDF) }
    var timestamps by rememberSaveable { mutableStateOf(false) }
    val createFile = rememberLauncherForActivityResult(CreateFile()) { uri ->
        if (uri != null) onSave(format, timestamps, uri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.export_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ExportFormat.entries.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option == format,
                            role = Role.RadioButton,
                            onClick = { format = option },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == format, onClick = null)
                    Text(stringResource(option.label), Modifier.padding(start = 12.dp))
                }
            }
            // Un SRT son marcas de tiempo por definición.
            if (format != ExportFormat.SRT) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.export_timestamps), Modifier.weight(1f))
                    Switch(checked = timestamps, onCheckedChange = { timestamps = it })
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { createFile.launch(Exporter.fileName(transcript, format) to format.mime) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.export_save)) }
                Button(
                    onClick = { onShare(format, timestamps) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.share)) }
            }
        }
    }
}
