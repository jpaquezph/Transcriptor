package com.transcriptor.app.ui

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transcriptor.app.R
import com.transcriptor.app.data.Transcript
import com.transcriptor.app.data.TranscriptError
import com.transcriptor.app.data.TranscriptStatus
import com.transcriptor.app.service.TranscriptionProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    transcripts: List<Transcript>,
    progress: TranscriptionProgress?,
    engineReady: Boolean,
    canRecord: Boolean,
    snackbar: SnackbarHostState,
    onRecord: () -> Unit,
    hasWhatsApp: Boolean,
    onOpenWhatsApp: () -> Unit,
    onPickVoiceNotes: () -> Unit,
    onPickFiles: () -> Unit,
    onOpen: (Transcript) -> Unit,
    onRename: (Transcript, String) -> Unit,
    onDelete: (Transcript) -> Unit,
    onEngine: () -> Unit,
) {
    var toDelete by remember { mutableStateOf<Transcript?>(null) }
    var toRename by remember { mutableStateOf<Transcript?>(null) }
    var picking by remember { mutableStateOf(false) }
    var showingChatHint by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onEngine) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.engine_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (engineReady) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canRecord) {
                        ExtendedFloatingActionButton(
                            onClick = onRecord,
                            icon = { Icon(Icons.Filled.Mic, null) },
                            text = { Text(stringResource(R.string.record)) },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    ExtendedFloatingActionButton(
                        onClick = { picking = true },
                        icon = { Icon(Icons.Filled.Add, null) },
                        text = { Text(stringResource(R.string.pick_audio)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!engineReady) EngineCard(onEngine)
            if (transcripts.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 168.dp)) {
                    items(transcripts, key = { it.id }) { transcript ->
                        TranscriptRow(
                            transcript = transcript,
                            progress = progress?.takeIf { it.id == transcript.id },
                            onClick = { onOpen(transcript) },
                            onRename = { toRename = transcript },
                            onDelete = { toDelete = transcript },
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        PickAudioSheet(
            hasWhatsApp = hasWhatsApp,
            onDismiss = { picking = false },
            onChat = { picking = false; showingChatHint = true },
            onVoiceNotes = { picking = false; onPickVoiceNotes() },
            onFiles = { picking = false; onPickFiles() },
        )
    }

    if (showingChatHint) {
        AlertDialog(
            onDismissRequest = { showingChatHint = false },
            text = { Text(stringResource(R.string.whatsapp_hint)) },
            confirmButton = {
                TextButton(onClick = { showingChatHint = false; onOpenWhatsApp() }) {
                    Text(stringResource(R.string.whatsapp_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { showingChatHint = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    toRename?.let { transcript ->
        RenameDialog(
            title = transcript.title,
            onDismiss = { toRename = null },
            onConfirm = { onRename(transcript, it); toRename = null },
        )
    }

    toDelete?.let { transcript ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            text = { Text(stringResource(R.string.delete_confirm, transcript.title)) },
            confirmButton = {
                TextButton(onClick = { onDelete(transcript); toDelete = null }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickAudioSheet(
    hasWhatsApp: Boolean,
    onDismiss: () -> Unit,
    onChat: () -> Unit,
    onVoiceNotes: () -> Unit,
    onFiles: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.pick_audio),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (hasWhatsApp) {
                PickOption(Icons.Outlined.Forum, R.string.pick_from_chat, R.string.pick_from_chat_desc, onChat)
            }
            PickOption(Icons.Outlined.GraphicEq, R.string.pick_voice_notes, R.string.pick_voice_notes_desc, onVoiceNotes)
            PickOption(Icons.Outlined.FolderOpen, R.string.pick_files, R.string.pick_files_desc, onFiles)
        }
    }
}

@Composable
private fun PickOption(icon: ImageVector, @StringRes title: Int, @StringRes description: Int, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(description)) },
    )
}

@Composable
fun RenameDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.rename_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.text.isNotBlank(),
                onClick = { onConfirm(value.text.trim()) },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun EngineCard(onEngine: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.needs_engine_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.needs_engine_body))
            Button(onClick = onEngine) { Text(stringResource(R.string.needs_engine_action)) }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.GraphicEq, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.empty_hint),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TranscriptRow(
    transcript: Transcript,
    progress: TranscriptionProgress?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(transcript.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    statusLine(transcript, progress)
                        ?: DateFormat.getMediumDateFormat(context).format(transcript.createdAt),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    val fraction = progress.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRename) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.rename))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete))
                }
            }
        },
    )
}

/** null cuando no hay nada que avisar. */
@Composable
fun statusLine(transcript: Transcript, progress: TranscriptionProgress?): String? = when (transcript.status) {
    TranscriptStatus.DONE -> null
    TranscriptStatus.QUEUED -> stringResource(R.string.status_queued)
    TranscriptStatus.CANCELLED -> stringResource(R.string.status_cancelled)
    TranscriptStatus.INTERRUPTED -> stringResource(R.string.status_interrupted)
    TranscriptStatus.FAILED -> stringResource(
        when (transcript.error) {
            TranscriptError.UNSUPPORTED_FORMAT -> R.string.error_unsupported
            TranscriptError.MODEL_MISSING -> R.string.error_model_missing
            else -> R.string.error_generic
        },
    )
    TranscriptStatus.TRANSCRIBING -> {
        val percent = progress?.fraction?.let { (it * 100).toInt() }
        val minutes = progress?.remainingMs?.let { (it / 60_000).toInt() + 1 }
        when {
            percent != null && minutes != null -> stringResource(R.string.status_percent_eta, percent, minutes)
            percent != null -> stringResource(R.string.status_percent, percent)
            else -> stringResource(R.string.status_transcribing)
        }
    }
}
