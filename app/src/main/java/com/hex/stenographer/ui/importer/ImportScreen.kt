package com.hex.stenographer.ui.importer

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hex.stenographer.R
import com.hex.stenographer.audio.AudioFileInfo
import java.util.Locale

@Composable
fun ImportScreen(
    state: ImportUiState,
    onPickFile: () -> Unit,
    onDecode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state is ImportUiState.Decoding
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onPickFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.import_pick_file))
        }

        val file = when (state) {
            ImportUiState.NoFile -> null
            is ImportUiState.Ready -> state.file
            is ImportUiState.Decoding -> state.file
            is ImportUiState.Decoded -> state.file
            is ImportUiState.Failed -> state.file
        }
        if (file == null) {
            Text(stringResource(R.string.import_no_file), style = MaterialTheme.typography.bodyMedium)
        } else {
            FileInfo(file)
            Button(onClick = onDecode, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.import_decode))
            }
        }

        when (state) {
            is ImportUiState.Decoding -> {
                val p = state.progress
                if (p == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.import_decoding))
            }

            is ImportUiState.Decoded -> {
                val rtf = if (state.durationMs > 0) state.decodeTimeMs.toDouble() / state.durationMs else 0.0
                Text(
                    stringResource(
                        R.string.import_result,
                        formatTimestamp(state.durationMs),
                        state.sampleRate,
                        state.sampleCount,
                        state.decodeTimeMs,
                        String.format(Locale.US, "%.3f", rtf),
                    ),
                )
            }

            is ImportUiState.Failed -> Text(
                stringResource(state.error.messageRes()),
                color = MaterialTheme.colorScheme.error,
            )

            else -> Unit
        }
    }
}

@Composable
private fun FileInfo(file: AudioFileInfo) {
    val context = LocalContext.current
    val size = file.sizeBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "—"
    Text(file.displayName, style = MaterialTheme.typography.titleMedium)
    Text("$size · ${file.mimeType ?: "?"}", style = MaterialTheme.typography.bodySmall)
}

private fun ImportError.messageRes(): Int = when (this) {
    ImportError.Unreadable -> R.string.import_error_unreadable
    ImportError.Unsupported -> R.string.import_error_unsupported
    ImportError.Corrupted -> R.string.import_error_corrupted
    ImportError.TooLong -> R.string.import_error_too_long
    ImportError.OutOfMemory -> R.string.import_error_memory
    ImportError.Unknown -> R.string.import_error_unknown
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
