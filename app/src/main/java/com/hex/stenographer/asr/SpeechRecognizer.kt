package com.hex.stenographer.asr

import com.hex.stenographer.BuildConfig
import com.hex.stenographer.audio.PcmAudio
import com.hex.stenographer.diarization.DiarizationConfig
import com.hex.stenographer.models.ModelStore
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Raw recognizer output for one chunk. */
class Recognition(
    val text: String,
    /** Token start times in seconds from the chunk start; empty when the model gives none (Whisper). */
    val tokenStartsSec: FloatArray,
)

interface SpeechRecognizer {
    /** Model files that must be installed before [transcribe]; used to prepare them up front. */
    val requiredModels: List<String>

    /**
     * Transcribes [chunks] of [audio] in order. [onProgress] receives the decoded fraction of
     * chunk audio, from a background thread.
     */
    suspend fun transcribe(audio: PcmAudio, chunks: List<AsrChunk>, onProgress: (Float) -> Unit = {}): List<Recognition>
}

/**
 * On-device ASR models available through sherpa-onnx. File names match models/SHA256SUMS; the
 * bundled one is chosen at build time with `-PasrModel=<id>`, others can be sideloaded.
 */
enum class AsrModel(val id: String, val files: List<String>) {
    WHISPER_TINY("whisper-tiny", listOf("whisper-tiny-encoder.int8.onnx", "whisper-tiny-decoder.int8.onnx", WHISPER_TOKENS)),
    WHISPER_BASE("whisper-base", listOf("whisper-base-encoder.int8.onnx", "whisper-base-decoder.int8.onnx", WHISPER_TOKENS)),

    /** GigaAM v3 (SberDevices), Russian, CTC: lowercase text without punctuation. */
    GIGAAM_V3_CTC("gigaam-v3-ctc", listOf("gigaam-v3-ctc.int8.onnx", "gigaam-v3-ctc-tokens.txt")),

    /** GigaAM v3 trained to output punctuation and capitalization. */
    GIGAAM_V3_CTC_PUNCT("gigaam-v3-ctc-punct", listOf("gigaam-v3-ctc-punct.int8.onnx", "gigaam-v3-ctc-punct-tokens.txt")),

    /** k2 Zipformer transducer for Russian, int8. */
    ZIPFORMER_RU(
        "zipformer-ru",
        listOf("zipformer-ru-encoder.int8.onnx", "zipformer-ru-decoder.onnx", "zipformer-ru-joiner.int8.onnx", "zipformer-ru-tokens.txt"),
    ),
    ;

    /** Whisper pads every chunk to 30 s and is autoregressive; the others scale with chunk length. */
    val isWhisper: Boolean get() = this == WHISPER_TINY || this == WHISPER_BASE

    internal fun modelConfig(paths: List<File>, language: String, threads: Int): OfflineModelConfig {
        val p = paths.map { it.path }
        return when (this) {
            WHISPER_TINY, WHISPER_BASE -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = p[0], decoder = p[1], language = language, task = "transcribe"),
                tokens = p[2], numThreads = threads, debug = false, provider = "cpu", modelType = "whisper",
            )

            GIGAAM_V3_CTC, GIGAAM_V3_CTC_PUNCT -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = p[0]),
                tokens = p[1], numThreads = threads, debug = false, provider = "cpu", modelType = "nemo_ctc",
            )

            ZIPFORMER_RU -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = p[0], decoder = p[1], joiner = p[2]),
                tokens = p[3], numThreads = threads, debug = false, provider = "cpu", modelType = "transducer",
            )
        }
    }

    companion object {
        fun byId(id: String): AsrModel = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown ASR model: $id")

        /** The model bundled into this APK. */
        val DEFAULT: AsrModel get() = byId(BuildConfig.ASR_MODEL)
    }
}

private const val WHISPER_TOKENS = "whisper-tokens.txt"

data class AsrConfig(
    val model: AsrModel = AsrModel.DEFAULT,
    /** Whisper only: ISO code, or empty for per-chunk language detection. */
    val language: String = "ru",
    val numThreads: Int = DiarizationConfig.defaultThreads(),
) {
    init {
        require(numThreads >= 1)
    }
}

/**
 * sherpa-onnx OfflineRecognizer for any [AsrModel]. One recognizer per call, released in
 * `finally`; one short-lived stream per chunk, decoded sequentially (peak memory stays at one
 * chunk; the models already use [AsrConfig.numThreads] internally).
 */
class SherpaOfflineRecognizer(
    private val models: ModelStore,
    private val config: AsrConfig = AsrConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SpeechRecognizer {

    override val requiredModels: List<String> = config.model.files

    override suspend fun transcribe(
        audio: PcmAudio,
        chunks: List<AsrChunk>,
        onProgress: (Float) -> Unit,
    ): List<Recognition> {
        if (chunks.isEmpty()) return emptyList()
        val paths = models.require(*requiredModels.toTypedArray())
        return withContext(dispatcher) {
            val recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    modelConfig = config.model.modelConfig(paths, config.language, config.numThreads),
                ),
            )
            try {
                val total = chunks.sumOf { it.length.toLong() }.toFloat()
                var done = 0L
                chunks.map { chunk ->
                    ensureActive()
                    val result = decode(recognizer, audio, chunk)
                    done += chunk.length
                    onProgress(done / total)
                    result
                }
            } finally {
                recognizer.release()
            }
        }
    }

    private fun decode(recognizer: OfflineRecognizer, audio: PcmAudio, chunk: AsrChunk): Recognition {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio.samples.copyOfRange(chunk.startSample, chunk.endSample), audio.sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return Recognition(result.text, result.timestamps)
        } finally {
            stream.release()
        }
    }
}
