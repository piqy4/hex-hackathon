package com.hex.stenographer.ui.main

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val player = remember(context) { FragmentPlayer(context) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(player, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.stop() }
    }
    LaunchedEffect(state.file?.uri, busy) { player.stop() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("НЕЙРО-СТЕНОГРАФИСТ", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text("Разговор — в текст", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Text("Реплики, говорящие и время. Обработка на устройстве.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val file = state.file
                    if (file == null) {
                        Text("Начните с аудиозаписи", style = MaterialTheme.typography.titleLarge)
                        Text("Выберите запись встречи, интервью или разговора. Интернет для обработки не нужен.")
                        Text("01  Выберите файл\n02  Получите расшифровку\n03  Сохраните JSON",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onPickFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Выбрать аудиофайл")
                        }
                        Text("WAV, MP3, AAC, OGG и другие поддерживаемые устройством форматы",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("АУДИОЗАПИСЬ", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        FileInfo(file)
                        if (!busy) {
                            Button(onClick = onProcess, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state is MainUiState.Done) "Обработать ещё раз" else "Создать расшифровку")
                            }
                            TextButton(onClick = onPickFile) { Text("Выбрать другой файл") }
                        }
                    }
                    when (state) {
                        is MainUiState.Processing -> {
                            StageProgress(state)
                            Text(if (state.stage == Stage.PreparingModels)
                                "При первом запуске подготовка моделей может занять несколько минут."
                            else "Обработка продолжается. Длительность зависит от записи и устройства.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is MainUiState.Failed -> Text(stringResource(state.error.messageRes()),
                            color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                }
            }
        }
        if (state is MainUiState.Done) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Расшифровка готова", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text("${formatTimestamp(state.result.durationMs)}  ·  Говорящих: ${state.result.speakerCount}  ·  Реплик: ${state.result.utterances.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Сохранить JSON")
                    }
                    Text("Проверьте текст перед отправкой. Реплики можно прослушать и отредактировать.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.fragmentError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.reprocessing != null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Повторное распознавание реплики…")
                    }
                    Summary(state.result)
                }
            }
            itemsIndexed(state.result.utterances, key = { i, _ -> i }) { index, utterance ->
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        UtteranceRow(utterance)
                        if (!busy) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                EditUtterance(utterance.text) { onEdit(index, it) }
                                Box {
                                    var expanded by remember { mutableStateOf(false) }
                                    TextButton(onClick = { expanded = true }) { Text("Действия") }
                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        DropdownMenuItem(text = { Text("Прослушать реплику") }, onClick = {
                                            expanded = false
                                            player.play(state.file.uri, utterance.startMs, utterance.endMs) {
                                                android.widget.Toast.makeText(context, "Не удалось воспроизвести аудио",
                                                    android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        })
                                        DropdownMenuItem(text = { Text("Остановить воспроизведение") }, onClick = {
                                            expanded = false; player.stop()
                                        })
                                        DropdownMenuItem(text = { Text("Распознать заново") }, onClick = {
                                            expanded = false; onReprocess(index)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text("Создано на устройстве · Нейро-стенографист",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp))
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
    val size = file.sizeBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "Размер неизвестен"
    Text(file.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StageProgress(state: MainUiState.Processing) {
    val p = state.progress
    Text(stringResource(state.stage.labelRes()), style = MaterialTheme.typography.titleSmall)
    if (p == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Summary(result: ProcessingResult) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Скрыть время обработки" else "Время обработки")
    }
    if (expanded) Text(
        stringResource(R.string.main_summary, formatTimestamp(result.durationMs),
            result.speakerCount, result.utterances.size,
            result.stageTimesMs[Stage.Decoding] ?: 0L,
            result.stageTimesMs[Stage.Diarization] ?: 0L,
            result.stageTimesMs[Stage.Recognition] ?: 0L,
            result.stageTimesMs[Stage.Polishing] ?: 0L,
            String.format(Locale.US, "%.3f", result.rtf)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (result.utterances.isEmpty()) Text(stringResource(R.string.main_no_speech))
}

@Composable
private fun UtteranceRow(utterance: Utterance) {
    Text("Говорящий ${utterance.speakerId.removePrefix("speaker_")}",
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
    Text("${formatTimestamp(utterance.startMs)} — ${formatTimestamp(utterance.endMs)}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(utterance.text, style = MaterialTheme.typography.bodyLarge)
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

fun formatTimestamp(ms: Long): String {
    val h = ms / 3_600_000
    val m = ms / 60_000 % 60
    val s = ms / 1000 % 60
    val milli = ms % 1000
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, milli)
    else String.format(Locale.US, "%02d:%02d.%03d", m, s, milli)
}
