package com.hex.stenographer.diarization

import com.hex.stenographer.audio.PcmAudio
import com.hex.stenographer.models.ModelStore
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Finds who speaks when. The result is sorted, non-overlapping and uses stable 1-based speaker ids. */
interface SpeakerDiarizer {
    /** Model files that must be installed before [diarize]; used to prepare them up front. */
    val requiredModels: List<String>

    /** @param onProgress fraction in `[0, 1]`, called from a background thread. */
    suspend fun diarize(audio: PcmAudio, onProgress: (Float) -> Unit = {}): List<SpeechSpan>
}

data class DiarizationConfig(
    /**
     * Clustering distance threshold; higher -> fewer speakers. The sherpa-onnx example uses 0.5 with
     * the zh-cn ERes2Net. Measured with the VoxCeleb ERes2Net after loudness normalization
     * (README, "Сравнение"): Russian dialogs A/C are best over 0.8–0.85 (lower values split speakers,
     * zh-cn embeddings are worse everywhere) and the 4-speaker sample needs ≥ 0.85.
     * Re-check on the team's own recordings.
     */
    val clusteringThreshold: Float = 0.85f,
    /** Fixed number of speakers, or `null` to detect it from audio (the default required by the spec). */
    val numSpeakers: Int? = null,
    /** Pyannote speech regions shorter than this are discarded, seconds. */
    val minDurationOnSec: Float = 0.2f,
    val timeline: TimelineConfig = TimelineConfig(),
    /** Speaker embedding weights; the zh-cn ERes2Net can be sideloaded for comparison. */
    val embeddingModel: String = SherpaSpeakerDiarizer.EMBEDDING_MODEL,
    val numThreads: Int = defaultThreads(),
) {
    init {
        require(clusteringThreshold > 0f)
        require(numSpeakers == null || numSpeakers >= 1)
        require(numThreads >= 1)
    }

    companion object {
        /** Big cores matter most; more threads than that only add contention on big.LITTLE. */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}

/**
 * sherpa-onnx diarization: pyannote segmentation 3.0 (int8) + 3D-Speaker ERes2Net embeddings +
 * fast agglomerative clustering, run over the whole recording so clusters are shared by all turns.
 *
 * The native engine is created per call and released in `finally`: the pipeline runs models one
 * after another and must give memory back before ASR and the LLM load.
 */
class SherpaSpeakerDiarizer(
    private val models: ModelStore,
    private val config: DiarizationConfig = DiarizationConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SpeakerDiarizer {

    override val requiredModels: List<String> = listOf(SEGMENTATION_MODEL, config.embeddingModel)

    override suspend fun diarize(audio: PcmAudio, onProgress: (Float) -> Unit): List<SpeechSpan> {
        val (segmentation, embedding) = models.require(*requiredModels.toTypedArray())
        return withContext(dispatcher) {
            val engine = OfflineSpeakerDiarization(config = nativeConfig(segmentation.path, embedding.path))
            try {
                check(engine.sampleRate() == audio.sampleRate) {
                    "Diarizer expects ${engine.sampleRate()} Hz, got ${audio.sampleRate} Hz"
                }
                val segments = engine.processWithCallback(audio.samples, ProgressCallback(onProgress))
                ensureActive()
                SpeakerTimeline.build(
                    raw = segments.map { RawSegment(it.start, it.end, it.speaker) },
                    totalSamples = audio.sampleCount,
                    sampleRate = audio.sampleRate,
                    config = config.timeline,
                )
            } finally {
                engine.release()
            }
        }
    }

    private fun nativeConfig(segmentationPath: String, embeddingPath: String) =
        OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationPath),
                numThreads = config.numThreads,
                debug = false,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingPath,
                numThreads = config.numThreads,
                debug = false,
                provider = "cpu",
            ),
            clustering = FastClusteringConfig(
                numClusters = config.numSpeakers ?: -1,
                threshold = config.clusteringThreshold,
            ),
            minDurationOn = config.minDurationOnSec,
            // Same-speaker gaps are merged by SpeakerTimeline with its own, tested threshold.
            minDurationOff = 0f,
        )

    /**
     * Must be a real class, not a lambda: the sherpa-onnx JNI looks up the specialized
     * `invoke(IIJ)Ljava/lang/Integer;`, which Kotlin emits only for classes implementing
     * Function3<Int, Int, Long, Int>; invokedynamic lambdas have just the erased bridge and crash
     * the process with NoSuchMethodError. Kept from R8 in keepRules/rules.keep.
     */
    internal class ProgressCallback(private val onProgress: (Float) -> Unit) : (Int, Int, Long) -> Int {
        override fun invoke(processedChunks: Int, totalChunks: Int, arg: Long): Int {
            if (totalChunks > 0) onProgress(processedChunks.toFloat() / totalChunks)
            return 0 // continue
        }
    }

    companion object {
        const val SEGMENTATION_MODEL = "pyannote-segmentation-3-0-int8.onnx"
        const val EMBEDDING_MODEL = "3dspeaker-eres2net-voxceleb-16k.onnx"
    }
}
