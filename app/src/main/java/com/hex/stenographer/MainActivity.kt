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
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        state = state,
                        onPickFile = { picker.launch(AUDIO_MIME_TYPES) },
                        onProcess = viewModel::process,
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
