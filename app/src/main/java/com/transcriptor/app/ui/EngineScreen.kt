package com.transcriptor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.transcriptor.app.R
import com.transcriptor.app.asr.SpeechModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineScreen(
    active: SpeechModel?,
    download: DownloadState,
    isInstalled: (SpeechModel) -> Boolean,
    onDownload: (SpeechModel) -> Unit,
    onSelect: (SpeechModel) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.engine_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.engine_intro))
            Text(
                stringResource(R.string.spanish_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (download) {
                is DownloadState.NoSpace -> ErrorText(stringResource(R.string.model_no_space, download.neededMb))
                DownloadState.Failed -> ErrorText(stringResource(R.string.model_download_failed))
                else -> Unit
            }
            SpeechModel.entries.forEach { model ->
                val running = (download as? DownloadState.Running)?.takeIf { it.model == model }
                ModelCard(
                    model = model,
                    installed = isInstalled(model),
                    isActive = model == active,
                    downloadingFraction = running?.fraction,
                    busy = download is DownloadState.Running,
                    onDownload = { onDownload(model) },
                    onSelect = { onSelect(model) },
                )
            }
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ModelCard(
    model: SpeechModel,
    installed: Boolean,
    isActive: Boolean,
    downloadingFraction: Float?,
    busy: Boolean,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(model.label), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.model_size, model.sizeMb), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(model.description))
            when {
                downloadingFraction != null -> {
                    LinearProgressIndicator(
                        progress = { downloadingFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.model_downloading, (downloadingFraction * 100).toInt()))
                }
                isActive || (installed && model.live) -> Text(
                    stringResource(if (isActive) R.string.model_active else R.string.model_installed),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                installed -> OutlinedButton(onClick = onSelect) { Text(stringResource(R.string.model_use)) }
                else -> Button(onClick = onDownload, enabled = !busy) { Text(stringResource(R.string.model_download)) }
            }
        }
    }
}
