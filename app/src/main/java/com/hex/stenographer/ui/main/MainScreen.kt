package com.hex.stenographer.ui.main

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hex.stenographer.R
import com.hex.stenographer.audio.AudioFileInfo
import com.hex.stenographer.transcript.Utterance
import java.util.Locale

@Composable
fun MainScreen(
    state: MainUiState,
    onPickFile: () -> Unit,
    onProcess: () -> Unit,
    onExport: () -> Unit,
    onEdit: (Int, String) -> Unit,
    onReprocess: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state is MainUiState.Processing || (state is MainUiState.Done && state.reprocessing != null)
    val context = LocalContext.current
    val player = androidx.compose.runtime.remember(context) { FragmentPlayer(context) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(player, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.stop() }
    }
    androidx.compose.runtime.LaunchedEffect(state.file?.uri, busy) { player.stop() }
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPickFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.main_pick_file))
                }
                val file = state.file
                if (file == null) {
                    Text(stringResource(R.string.main_no_file), style = MaterialTheme.typography.bodyMedium)
                } else {
                    FileInfo(file)
                    Button(onClick = onProcess, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.main_process))
                    }
                }
                when (state) {
                    is MainUiState.Processing -> StageProgress(state)
                    is MainUiState.Failed -> Text(
                        stringResource(state.error.messageRes()),
                        color = MaterialTheme.colorScheme.error,
                    )
                    is MainUiState.Done -> {
                        Summary(state.result)
                        state.fragmentError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.reprocessing != null) Text("Повторное распознавание реплики…")
                        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                            Text("Экспорт JSON")
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (state is MainUiState.Done) {
            itemsIndexed(state.result.utterances, key = { i, _ -> i }) { index, utterance ->
                UtteranceRow(utterance)
                if (!busy) {
                    EditUtterance(utterance.text) { onEdit(index, it) }
                    TextButton(onClick = { onReprocess(index) }) { Text("Распознать заново") }
                    TextButton(onClick = {
                        player.play(state.file.uri, utterance.startMs, utterance.endMs) {
                            android.widget.Toast.makeText(context, "Не удалось воспроизвести аудио",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Прослушать") }
                    TextButton(onClick = { player.stop() }) { Text("Стоп") }
                }
                HorizontalDivider()
            }
        }
    }
}

private val MainUiState.file: AudioFileInfo?
    get() = when (this) {
        MainUiState.NoFile -> null
        is MainUiState.Ready -> file
        is MainUiState.Processing -> file
        is MainUiState.Done -> file
        is MainUiState.Failed -> file
    }

@Composable
private fun FileInfo(file: AudioFileInfo) {
    val context = LocalContext.current
    val size = file.sizeBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "—"
    Text(file.displayName, style = MaterialTheme.typography.titleMedium)
    Text("$size · ${file.mimeType ?: "?"}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StageProgress(state: MainUiState.Processing) {
    val p = state.progress
    if (p == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
    }
    Text(stringResource(state.stage.labelRes()))
}

@Composable
private fun Summary(result: ProcessingResult) {
    Text(
        stringResource(
            R.string.main_summary,
            formatTimestamp(result.durationMs),
            result.speakerCount,
            result.utterances.size,
            result.stageTimesMs[Stage.Decoding] ?: 0L,
            result.stageTimesMs[Stage.Diarization] ?: 0L,
            result.stageTimesMs[Stage.Recognition] ?: 0L,
            result.stageTimesMs[Stage.Polishing] ?: 0L,
            String.format(Locale.US, "%.3f", result.rtf),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (result.utterances.isEmpty()) Text(stringResource(R.string.main_no_speech))
}

@Composable
private fun UtteranceRow(utterance: Utterance) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(utterance.speakerId, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatTimestamp(utterance.startMs)} – ${formatTimestamp(utterance.endMs)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(utterance.text)
    }
}

private fun Stage.labelRes(): Int = when (this) {
    Stage.PreparingModels -> R.string.stage_models
    Stage.Decoding -> R.string.stage_decoding
    Stage.Diarization -> R.string.stage_diarization
    Stage.Recognition -> R.string.stage_recognition
    Stage.Polishing -> R.string.stage_polishing
}

private fun ProcessingError.messageRes(): Int = when (this) {
    ProcessingError.Unreadable -> R.string.error_unreadable
    ProcessingError.Unsupported -> R.string.error_unsupported
    ProcessingError.Corrupted -> R.string.error_corrupted
    ProcessingError.TooLong -> R.string.error_too_long
    ProcessingError.OutOfMemory -> R.string.error_memory
    ProcessingError.ModelsMissing -> R.string.error_models_missing
    ProcessingError.ModelsCorrupted -> R.string.error_models_corrupted
    ProcessingError.InsufficientStorage -> R.string.error_storage
    ProcessingError.Failed -> R.string.error_failed
}

/** `mm:ss.SSS` (or `h:mm:ss.SSS`) for on-screen display; JSON keeps raw milliseconds. */
fun formatTimestamp(ms: Long): String {
    val h = ms / 3_600_000
    val m = ms / 60_000 % 60
    val s = ms / 1000 % 60
    val milli = ms % 1000
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, milli)
    } else {
        String.format(Locale.US, "%02d:%02d.%03d", m, s, milli)
    }
}
