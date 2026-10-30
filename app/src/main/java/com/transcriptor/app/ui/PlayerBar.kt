package com.transcriptor.app.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.transcriptor.app.R
import com.transcriptor.app.export.formatClock
import kotlinx.coroutines.delay
import java.io.File

private val SKIP_SECONDS = listOf(10, 5, 2)
private val SPEEDS = listOf(
    0.5f to R.string.speed_half,
    1f to R.string.speed_normal,
    2f to R.string.speed_double,
)

class AudioPlayerState(val player: Player) {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun seekTo(ms: Long) {
        val target = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)
        player.seekTo(target)
        positionMs = target
    }

    fun skip(seconds: Int) = seekTo(player.currentPosition + seconds * 1000L)

    fun changeSpeed(value: Float) {
        speed = value
        player.playbackParameters = PlaybackParameters(value)
    }
}

@Composable
fun rememberAudioPlayer(file: File): AudioPlayerState {
    val context = LocalContext.current
    val state = remember(file) {
        val player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
        AudioPlayerState(player)
    }
    DisposableEffect(state) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.isPlaying = isPlaying
            }
        }
        state.player.addListener(listener)
        onDispose {
            state.player.removeListener(listener)
            state.player.release()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.player.pause() }
    LaunchedEffect(state) {
        while (true) {
            state.positionMs = state.player.currentPosition
            state.durationMs = state.player.duration.coerceAtLeast(0)
            delay(200)
        }
    }
    return state
}

@Composable
fun PlayerBar(state: AudioPlayerState, modifier: Modifier = Modifier) {
    Surface(modifier, tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            val duration = state.durationMs.coerceAtLeast(1).toFloat()
            Slider(
                value = (dragging ?: state.positionMs.toFloat()).coerceIn(0f, duration),
                valueRange = 0f..duration,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { state.seekTo(it.toLong()) }
                    dragging = null
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = dragging?.toLong() ?: state.positionMs
                Text(formatClock(shown), style = MaterialTheme.typography.labelMedium)
                Text(formatClock(state.durationMs), style = MaterialTheme.typography.labelMedium)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SKIP_SECONDS.forEach { seconds ->
                    SkipButton("−$seconds", stringResource(R.string.rewind_seconds, seconds)) { state.skip(-seconds) }
                }
                FilledIconButton(onClick = state::togglePlay, modifier = Modifier.size(56.dp)) {
                    if (state.isPlaying) {
                        Icon(Icons.Filled.Pause, stringResource(R.string.pause))
                    } else {
                        Icon(Icons.Filled.PlayArrow, stringResource(R.string.play))
                    }
                }
                SKIP_SECONDS.reversed().forEach { seconds ->
                    SkipButton("+$seconds", stringResource(R.string.forward_seconds, seconds)) { state.skip(seconds) }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SPEEDS.forEachIndexed { index, (speed, label) ->
                    SegmentedButton(
                        selected = state.speed == speed,
                        onClick = { state.changeSpeed(speed) },
                        shape = SegmentedButtonDefaults.itemShape(index, SPEEDS.size),
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SkipButton(label: String, description: String, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).semantics { contentDescription = description },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(label, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
