package com.hex.stenographer.ui.main

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hex.stenographer.asr.SherpaOfflineRecognizer
import com.hex.stenographer.audio.AndroidAudioImporter
import com.hex.stenographer.audio.AudioFileInfo
import com.hex.stenographer.audio.AudioImportException
import com.hex.stenographer.audio.AudioImporter
import com.hex.stenographer.audio.normalizeLoudnessInPlace
import com.hex.stenographer.diarization.SherpaSpeakerDiarizer
import com.hex.stenographer.models.ModelException
import com.hex.stenographer.models.ModelStore
import com.hex.stenographer.transcript.DefaultSpeechEngine
import com.hex.stenographer.transcript.SpeechEngine
import com.hex.stenographer.transcript.SpeechStage
import com.hex.stenographer.transcript.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Stage { PreparingModels, Decoding, Diarization, Recognition }

enum class ProcessingError {
    Unreadable, Unsupported, Corrupted, TooLong, OutOfMemory,
    ModelsMissing, ModelsCorrupted, InsufficientStorage, Failed,
}

data class ProcessingResult(
    val durationMs: Long,
    val utterances: List<Utterance>,
    val stageTimesMs: Map<Stage, Long>,
) {
    val speakerCount: Int get() = utterances.distinctBy { it.speakerId }.size

    /** From "Process" to result, model installation excluded (it is reported separately). */
    val processingMs: Long get() = stageTimesMs.filterKeys { it != Stage.PreparingModels }.values.sum()

    val rtf: Double get() = if (durationMs == 0L) 0.0 else processingMs.toDouble() / durationMs
}

sealed interface MainUiState {
    data object NoFile : MainUiState
    data class Ready(val file: AudioFileInfo) : MainUiState
    data class Processing(val file: AudioFileInfo, val stage: Stage, val progress: Float?) : MainUiState
    data class Done(val file: AudioFileInfo, val result: ProcessingResult) : MainUiState
    data class Failed(val file: AudioFileInfo?, val error: ProcessingError) : MainUiState
}

class MainViewModel(
    private val importer: AudioImporter,
    private val models: ModelStore,
    private val engine: SpeechEngine,
) : ViewModel() {

    private val _state = MutableStateFlow<MainUiState>(MainUiState.NoFile)
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch { runCatching { models.prune() } }
    }

    /** `null` means the picker was cancelled: keep whatever is on screen. */
    fun onFileSelected(uri: Uri?) {
        if (uri == null) return
        job?.cancel() // the previous result is replaced, never attached to the new file
        job = viewModelScope.launch {
            _state.value = try {
                MainUiState.Ready(importer.describe(uri))
            } catch (e: AudioImportException) {
                MainUiState.Failed(null, e.toError())
            }
        }
    }

    fun process() {
        if (job?.isActive == true) return
        val file = when (val s = _state.value) {
            is MainUiState.Ready -> s.file
            is MainUiState.Done -> s.file
            is MainUiState.Failed -> s.file
            else -> null
        } ?: return

        job = viewModelScope.launch {
            val timer = StageTimer(file)
            _state.value = try {
                timer.start(Stage.PreparingModels)
                models.require(*engine.requiredModels.toTypedArray()) { timer.progress(Stage.PreparingModels, it) }

                timer.start(Stage.Decoding)
                val audio = importer.decode(file.uri) { timer.progress(Stage.Decoding, it) }
                audio.normalizeLoudnessInPlace()

                val utterances = engine.run(audio) { speechStage, fraction ->
                    val stage = speechStage.toStage()
                    if (fraction == null) timer.start(stage) else timer.progress(stage, fraction)
                }
                MainUiState.Done(file, ProcessingResult(audio.durationMs, utterances, timer.finish()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                MainUiState.Failed(file, e.toError())
            }
        }
    }

    /** Publishes stage changes and progress, and measures wall time per stage. */
    private inner class StageTimer(private val file: AudioFileInfo) {
        private val times = LinkedHashMap<Stage, Long>()
        private var current: Stage? = null
        private var startedAt = 0L

        fun start(stage: Stage) {
            close()
            current = stage
            startedAt = SystemClock.elapsedRealtime()
            _state.value = MainUiState.Processing(file, stage, progress = null)
        }

        fun progress(stage: Stage, fraction: Float) {
            // Atomic: a late callback must not overwrite a newer state (another stage or file).
            _state.update {
                if (it is MainUiState.Processing && it.file == file && it.stage == stage) it.copy(progress = fraction) else it
            }
        }

        fun finish(): Map<Stage, Long> {
            close()
            return times.toMap()
        }

        private fun close() {
            current?.let { times[it] = SystemClock.elapsedRealtime() - startedAt }
            current = null
        }
    }

    private fun SpeechStage.toStage(): Stage = when (this) {
        SpeechStage.Diarization -> Stage.Diarization
        SpeechStage.Recognition -> Stage.Recognition
    }

    private fun Exception.toError(): ProcessingError = when (this) {
        is AudioImportException.Unreadable -> ProcessingError.Unreadable
        is AudioImportException.UnsupportedFormat -> ProcessingError.Unsupported
        is AudioImportException.Corrupted -> ProcessingError.Corrupted
        is AudioImportException.TooLong -> ProcessingError.TooLong
        is AudioImportException.InsufficientMemory -> ProcessingError.OutOfMemory
        is ModelException.Missing -> ProcessingError.ModelsMissing
        is ModelException.Corrupted -> ProcessingError.ModelsCorrupted
        is ModelException.InsufficientStorage -> ProcessingError.InsufficientStorage
        else -> ProcessingError.Failed
    }

    companion object {
        private const val TAG = "MainViewModel"

        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val models = ModelStore(app)
                val engine = DefaultSpeechEngine(SherpaSpeakerDiarizer(models), SherpaOfflineRecognizer(models))
                MainViewModel(AndroidAudioImporter(app), models, engine)
            }
        }
    }
}
