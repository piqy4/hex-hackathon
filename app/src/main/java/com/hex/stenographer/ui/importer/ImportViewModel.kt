package com.hex.stenographer.ui.importer

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hex.stenographer.audio.AndroidAudioImporter
import com.hex.stenographer.audio.AudioFileInfo
import com.hex.stenographer.audio.AudioImportException
import com.hex.stenographer.audio.AudioImporter
import com.hex.stenographer.audio.PcmAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportError { Unreadable, Unsupported, Corrupted, TooLong, OutOfMemory, Unknown }

sealed interface ImportUiState {
    data object NoFile : ImportUiState
    data class Ready(val file: AudioFileInfo) : ImportUiState
    data class Decoding(val file: AudioFileInfo, val progress: Float?) : ImportUiState
    data class Decoded(
        val file: AudioFileInfo,
        val durationMs: Long,
        val sampleRate: Int,
        val sampleCount: Int,
        val decodeTimeMs: Long,
    ) : ImportUiState

    data class Failed(val file: AudioFileInfo?, val error: ImportError) : ImportUiState
}

class ImportViewModel(private val importer: AudioImporter) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.NoFile)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** Decoded PCM for the next pipeline stage; kept out of UI state to avoid huge equals(). */
    var audio: PcmAudio? = null
        private set

    private var job: Job? = null

    /** `null` means the picker was cancelled: keep whatever is on screen. */
    fun onFileSelected(uri: Uri?) {
        if (uri == null) return
        job?.cancel()
        audio = null // never attach the previous result to a new file
        job = viewModelScope.launch {
            _state.value = try {
                ImportUiState.Ready(importer.describe(uri))
            } catch (e: AudioImportException) {
                ImportUiState.Failed(null, e.toImportError())
            }
        }
    }

    fun decode() {
        if (job?.isActive == true) return
        val file = when (val s = _state.value) {
            is ImportUiState.Ready -> s.file
            is ImportUiState.Decoded -> s.file
            is ImportUiState.Failed -> s.file
            else -> null
        } ?: return

        audio = null
        _state.value = ImportUiState.Decoding(file, progress = null)
        job = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            _state.value = try {
                val pcm = importer.decode(file.uri) { p ->
                    // Atomic: a late callback must not overwrite a newer state (e.g. another file).
                    _state.update { if (it is ImportUiState.Decoding && it.file == file) it.copy(progress = p) else it }
                }
                audio = pcm
                ImportUiState.Decoded(
                    file = file,
                    durationMs = pcm.durationMs,
                    sampleRate = pcm.sampleRate,
                    sampleCount = pcm.sampleCount,
                    decodeTimeMs = SystemClock.elapsedRealtime() - started,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioImportException) {
                ImportUiState.Failed(file, e.toImportError())
            } catch (e: Exception) {
                ImportUiState.Failed(file, ImportError.Unknown)
            }
        }
    }

    private fun AudioImportException.toImportError(): ImportError = when (this) {
        is AudioImportException.Unreadable -> ImportError.Unreadable
        is AudioImportException.UnsupportedFormat -> ImportError.Unsupported
        is AudioImportException.Corrupted -> ImportError.Corrupted
        is AudioImportException.TooLong -> ImportError.TooLong
        is AudioImportException.InsufficientMemory -> ImportError.OutOfMemory
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ImportViewModel(AndroidAudioImporter(checkNotNull(this[APPLICATION_KEY]))) }
        }
    }
}
