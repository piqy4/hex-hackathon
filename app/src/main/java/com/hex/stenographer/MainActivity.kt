package com.hex.stenographer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hex.stenographer.ui.main.MainScreen
import com.hex.stenographer.ui.main.MainViewModel
import com.hex.stenographer.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)
                val state by viewModel.state.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                    viewModel::onFileSelected,
                )
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val exporter = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json"),
                ) { uri ->
                    val text = viewModel.pendingExport
                    viewModel.pendingExport = null
                    if (uri != null && text != null) scope.launch {
                        val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                checkNotNull(contentResolver.openOutputStream(uri, "wt"))
                                    .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                            }.isSuccess
                        }
                        android.widget.Toast.makeText(this@MainActivity,
                            if (success) "JSON сохранён" else "Не удалось сохранить JSON. Повторите экспорт.",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        state = state,
                        onPickFile = { picker.launch(AUDIO_MIME_TYPES) },
                        onProcess = viewModel::process,
                        onEdit = viewModel::editUtterance,
                        onReprocess = viewModel::reprocessUtterance,
                        onExport = {
                            val done = state as? com.hex.stenographer.ui.main.MainUiState.Done
                            if (done != null) {
                                viewModel.pendingExport = com.hex.stenographer.transcript.TranscriptJson.encode(
                                    done.result.utterances, done.result.durationMs)
                                exporter.launch("transcript.json")
                            }
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    private companion object {
        /** Some providers label OGG files as application/ogg. */
        val AUDIO_MIME_TYPES = arrayOf("audio/*", "application/ogg")
    }
}
