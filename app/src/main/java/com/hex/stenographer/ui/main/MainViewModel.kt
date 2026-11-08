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

enum class Stage { PreparingModels, Decoding, Diarization, Recognition, Polishing }

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
    data class Done(val file: AudioFileInfo, val result: ProcessingResult, val reprocessing: Int? = null, val fragmentError: String? = null) : MainUiState
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

    // Kept outside saved-state Bundle: long transcripts can exceed Binder's size limit.
    var pendingExport: String? = null

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

    fun reprocessUtterance(index: Int) {
        if (job?.isActive == true) return
        val snapshot = _state.value as? MainUiState.Done ?: return
        val item = snapshot.result.utterances.getOrNull(index) ?: return
        job = viewModelScope.launch {
            _state.value = snapshot.copy(reprocessing = index)
            try {
                val audio = importer.decode(snapshot.file.uri) {}
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { audio.normalizeLoudnessInPlace() }
                val span = com.hex.stenographer.diarization.SpeechSpan(
                    1, audio.msToSample(item.startMs), audio.msToSample(item.endMs))
                val chunks = com.hex.stenographer.asr.ChunkPlanner.plan(
                    audio.samples, audio.sampleRate, listOf(span),
                    com.hex.stenographer.asr.ChunkingConfig(minChunkMs = 100))
                val recognitions = SherpaOfflineRecognizer(models).transcribe(audio, chunks)
                val text = recognitions.mapNotNull { com.hex.stenographer.asr.TranscriptText.clean(it.text) }
                    .joinToString(" ")
                check(text.isNotBlank()) { "Речь не найдена; прежний текст сохранён." }
                val model = models.require("qwen2.5-0.5b-instruct-q4_k_m.gguf").single()
                val corrected = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.hex.stenographer.llm.SafeTextPolisher(
                        { com.hex.stenographer.llm.LlamaSession(model) }
                    ).polish(listOf(item.copy(text = text))).single()
                }
                _state.value = snapshot.copy(result = snapshot.result.copy(
                    utterances = snapshot.result.utterances.mapIndexed { i, old ->
                        if (i == index) corrected else old
                    }))
            } catch (e: CancellationException) {
                _state.value = snapshot
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fragment recognition failed", e)
                _state.value = snapshot.copy(fragmentError = "Не удалось обработать фрагмент. Прежний текст сохранён.")
            }
        }
    }

    fun editUtterance(index: Int, text: String) {
        if (text.isBlank() || job?.isActive == true) return
        _state.update { state ->
            if (state !is MainUiState.Done || index !in state.result.utterances.indices) state
            else state.copy(result = state.result.copy(utterances =
                state.result.utterances.mapIndexed { i, item ->
                    if (i == index) item.copy(text = text.trim()) else item
                }))
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
                models.require(*(engine.requiredModels + "qwen2.5-0.5b-instruct-q4_k_m.gguf").toTypedArray()) { timer.progress(Stage.PreparingModels, it) }

                val (durationMs, utterances) = recognizeFile(file, timer)
                timer.start(Stage.Polishing)
                val model = models.require("qwen2.5-0.5b-instruct-q4_k_m.gguf").single()
                val polished = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.hex.stenographer.llm.SafeTextPolisher(
                        { com.hex.stenographer.llm.LlamaSession(model) },
                        { Log.i(TAG, "LLM: $it") },
                    ).polish(utterances)
                }
                MainUiState.Done(file, ProcessingResult(durationMs, polished, timer.finish()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                MainUiState.Failed(file, e.toError())
            }
        }
    }

    /** PCM stays in this frame and is no longer retained when LLM starts. */
    private suspend fun recognizeFile(file: AudioFileInfo, timer: StageTimer): Pair<Long, List<Utterance>> {
        timer.start(Stage.Decoding)
        val audio = importer.decode(file.uri) { timer.progress(Stage.Decoding, it) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { audio.normalizeLoudnessInPlace() }
        val items = engine.run(audio) { speechStage, fraction ->
            val stage = speechStage.toStage()
            if (fraction == null) timer.start(stage) else timer.progress(stage, fraction)
        }
        return audio.durationMs to items
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
