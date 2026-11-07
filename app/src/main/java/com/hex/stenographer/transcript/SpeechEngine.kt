package com.hex.stenographer.transcript

import com.hex.stenographer.asr.AsrChunk
import com.hex.stenographer.asr.ChunkPlanner
import com.hex.stenographer.asr.ChunkingConfig
import com.hex.stenographer.asr.Recognition
import com.hex.stenographer.asr.SpeechRecognizer
import com.hex.stenographer.asr.TranscriptText
import com.hex.stenographer.audio.PcmAudio
import com.hex.stenographer.diarization.SpeakerDiarizer
import kotlin.math.roundToLong

enum class SpeechStage { Diarization, Recognition }

fun interface SpeechProgress {
    /** [fraction] is `null` when [stage] has just started. */
    fun onProgress(stage: SpeechStage, fraction: Float?)
}

/** Diarization + ASR: normalized PCM in, sorted utterances with stable speaker ids out. */
interface SpeechEngine {
    val requiredModels: List<String>

    suspend fun run(audio: PcmAudio, progress: SpeechProgress = SpeechProgress { _, _ -> }): List<Utterance>
}

data class BoundaryConfig(
    /**
     * Tighten utterance edges to the recognized tokens when the model reports token times.
     * Off by default: on the evaluation dialogs GigaAM CTC token times did not beat the
     * diarization boundaries for any lead/tail (README, "Сравнение"). Kept for other models.
     */
    val trimToTokens: Boolean = false,
    /** Kept before the first token: CTC emits a token slightly after its acoustic onset. */
    val leadMs: Int = 120,
    /** Kept after the last token's start: covers the rest of the last syllable. */
    val tailMs: Int = 300,
) {
    init {
        require(leadMs >= 0 && tailMs >= 0)
    }
}

class DefaultSpeechEngine(
    private val diarizer: SpeakerDiarizer,
    private val recognizer: SpeechRecognizer,
    private val chunking: ChunkingConfig = ChunkingConfig(),
    private val boundaries: BoundaryConfig = BoundaryConfig(),
    private val onWordAlignment: (Boolean) -> Unit = {},
) : SpeechEngine {

    override val requiredModels: List<String>
        get() = diarizer.requiredModels + recognizer.requiredModels

    override suspend fun run(audio: PcmAudio, progress: SpeechProgress): List<Utterance> {
        progress.onProgress(SpeechStage.Diarization, null)
        val spans = diarizer.diarize(audio) { progress.onProgress(SpeechStage.Diarization, it) }

        progress.onProgress(SpeechStage.Recognition, null)
        if (recognizer.requiredModels.any { it.startsWith("gigaam-") }) {
            val continuous = ChunkPlanner.plan(audio.samples, audio.sampleRate, WordSpeakers.regions(spans, audio.sampleRate), chunking)
            val recognized = recognizer.transcribe(audio, continuous) { progress.onProgress(SpeechStage.Recognition, it) }
            if (recognized.size == continuous.size) {
                val assigned = continuous.zip(recognized).map { (chunk, result) ->
                    if (TranscriptText.clean(result.text) == null) emptyList()
                    else WordSpeakers.assign(chunk, result, spans, audio.sampleRate)
                }
                if (assigned.all { it != null }) {
                    onWordAlignment(true)
                    return assigned.flatMap { checkNotNull(it) }.sortedBy { it.startMs }
                }
            }
            onWordAlignment(false)
            // Unsupported/malformed token alignment: retain the tested segment-based fallback.
        }
        val chunks = ChunkPlanner.plan(audio.samples, audio.sampleRate, spans, chunking)
        val results = recognizer.transcribe(audio, chunks) { progress.onProgress(SpeechStage.Recognition, it) }

        return chunks.zip(results).mapNotNull { (chunk, result) -> toUtterance(chunk, result, audio) }
            .sortedBy { it.startMs }
    }

    private fun toUtterance(chunk: AsrChunk, result: Recognition, audio: PcmAudio): Utterance? {
        val text = TranscriptText.clean(result.text) ?: return null
        var startSample = chunk.startSample
        var endSample = chunk.endSample
        val tokens = result.tokenStartsSec
        if (boundaries.trimToTokens && tokens.isNotEmpty()) {
            val rate = audio.sampleRate
            val first = chunk.startSample + ((tokens.first() * rate).toLong() - boundaries.leadMs.toLong() * rate / 1000)
            val last = chunk.startSample + ((tokens.last() * rate).toLong() + boundaries.tailMs.toLong() * rate / 1000)
            startSample = first.coerceIn(chunk.startSample.toLong(), chunk.endSample.toLong() - 1).toInt()
            endSample = last.coerceIn(startSample.toLong() + 1, chunk.endSample.toLong()).toInt()
        }
        val start = toMs(startSample, audio)
        val end = toMs(endSample, audio)
        return if (end <= start) null else Utterance("speaker_${chunk.speaker}", start, end, text)
    }

    /** Rounded to the nearest millisecond and clamped to the recording, as the spec requires. */
    private fun toMs(sample: Int, audio: PcmAudio): Long =
        (sample * 1000.0 / audio.sampleRate).roundToLong().coerceIn(0L, audio.durationMs)
}
