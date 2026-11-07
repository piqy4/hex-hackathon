package com.hex.stenographer.ui.main

import android.net.Uri
import android.os.SystemClock
import android.util.Log
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
import com.hex.stenographer.audio.normalizePeakInPlace
import com.hex.stenographer.diarization.SherpaSpeakerDiarizer
import com.hex.stenographer.diarization.SpeakerDiarizer
import com.hex.stenographer.diarization.SpeechSpan
import com.hex.stenographer.models.ModelException
import com.hex.stenographer.models.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Stage { PreparingModels, Decoding, Diarization }

enum class ProcessingError {
    Unreadable, Unsupported, Corrupted, TooLong, OutOfMemory,
    ModelsMissing, ModelsCorrupted, InsufficientStorage, Failed,
}

/** One diarized turn prepared for display. */
data class SpanItem(val speakerId: String, val startMs: Long, val endMs: Long)

data class ProcessingResult(
    val durationMs: Long,
    val sampleRate: Int,
    val speakerCount: Int,
    val spans: List<SpanItem>,
    val stageTimesMs: Map<Stage, Long>,
) {
    /** Real-time factor of decoding + diarization (model installation excluded, as the spec asks). */
    val rtf: Double
        get() = if (durationMs == 0L) 0.0
        else ((stageTimesMs[Stage.Decoding] ?: 0) + (stageTimesMs[Stage.Diarization] ?: 0)).toDouble() / durationMs
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
    private val diarizer: SpeakerDiarizer,
) : ViewModel() {

    private val _state = MutableStateFlow<MainUiState>(MainUiState.NoFile)
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    /** Normalized PCM of the last processed file, for the next pipeline stages. */
    var audio: PcmAudio? = null
        private set

    /** Diarization of [audio]. */
    var spans: List<SpeechSpan> = emptyList()
        private set

    private var job: Job? = null

    init {
        viewModelScope.launch { runCatching { models.prune() } }
    }

    /** `null` means the picker was cancelled: keep whatever is on screen. */
    fun onFileSelected(uri: Uri?) {
        if (uri == null) return
        job?.cancel()
        clearResult() // never attach the previous result to a new file
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

        clearResult()
        job = viewModelScope.launch {
            val times = LinkedHashMap<Stage, Long>()
            _state.value = try {
                stage(file, Stage.PreparingModels, times) { progress ->
                    models.require(SherpaSpeakerDiarizer.SEGMENTATION_MODEL, SherpaSpeakerDiarizer.EMBEDDING_MODEL, onProgress = progress)
                }
                val pcm = stage(file, Stage.Decoding, times) { progress ->
                    importer.decode(file.uri, progress).also { it.normalizePeakInPlace() }
                }
                val turns = stage(file, Stage.Diarization, times) { progress -> diarizer.diarize(pcm, progress) }
                audio = pcm
                spans = turns
                MainUiState.Done(file, pcm.toResult(turns, times))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                MainUiState.Failed(file, e.toError())
            }
        }
    }

    private suspend fun <T> stage(
        file: AudioFileInfo,
        stage: Stage,
        times: MutableMap<Stage, Long>,
        block: suspend (onProgress: (Float) -> Unit) -> T,
    ): T {
        _state.value = MainUiState.Processing(file, stage, progress = null)
        val started = SystemClock.elapsedRealtime()
        val result = block { p ->
            // Atomic: a late callback must not overwrite a newer state (another stage or file).
            _state.update {
                if (it is MainUiState.Processing && it.file == file && it.stage == stage) it.copy(progress = p) else it
            }
        }
        times[stage] = SystemClock.elapsedRealtime() - started
        return result
    }

    private fun clearResult() {
        audio = null
        spans = emptyList()
    }

    private fun PcmAudio.toResult(turns: List<SpeechSpan>, times: Map<Stage, Long>) = ProcessingResult(
        durationMs = durationMs,
        sampleRate = sampleRate,
        speakerCount = turns.maxOfOrNull { it.speaker } ?: 0,
        spans = turns.map { SpanItem(it.speakerId, sampleToMs(it.startSample), sampleToMs(it.endSample)) },
        stageTimesMs = times.toMap(),
    )

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
                MainViewModel(AndroidAudioImporter(app), models, SherpaSpeakerDiarizer(models))
            }
        }
    }
}
