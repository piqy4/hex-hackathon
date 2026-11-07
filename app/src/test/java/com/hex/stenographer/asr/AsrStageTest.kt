package com.hex.stenographer.asr

import com.hex.stenographer.audio.PcmAudio
import com.hex.stenographer.diarization.SpeakerDiarizer
import com.hex.stenographer.diarization.SpeechSpan
import com.hex.stenographer.eval.Wer
import com.hex.stenographer.transcript.DefaultSpeechEngine
import com.hex.stenographer.transcript.Utterance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AsrStageTest {

    private val rate = 16_000

    /** Tone everywhere except silent `[from, to)` seconds. */
    private fun speechWithPauses(seconds: Int, vararg pauses: Pair<Double, Double>): FloatArray =
        FloatArray(seconds * rate) { i ->
            val t = i.toDouble() / rate
            if (pauses.any { (a, b) -> t >= a && t < b }) 0f else (0.5 * sin(2 * PI * 220 * t)).toFloat()
        }

    // ---------- ChunkPlanner ----------

    @Test
    fun `short turns pass through and too short ones are skipped`() {
        val samples = FloatArray(10 * rate)
        val spans = listOf(SpeechSpan(1, 0, 3 * rate), SpeechSpan(2, 3 * rate, 3 * rate + rate / 10))
        assertEquals(listOf(AsrChunk(1, 0, 3 * rate)), ChunkPlanner.plan(samples, rate, spans))
    }

    @Test
    fun `long turn is split at the pause closest to the limit`() {
        val samples = speechWithPauses(40, 10.0 to 10.3, 20.0 to 20.3)
        val chunks = ChunkPlanner.plan(samples, rate, listOf(SpeechSpan(1, 0, samples.size)))
        assertEquals(2, chunks.size)
        val splitSec = chunks[0].endSample.toDouble() / rate
        assertTrue("split at $splitSec", splitSec in 20.0..20.3) // the later of the two pauses
        assertEquals(chunks[0].endSample, chunks[1].startSample)
        assertEquals(samples.size, chunks[1].endSample)
    }

    @Test
    fun `without a pause the split happens at the limit and chunks stay within it`() {
        val samples = speechWithPauses(60)
        val chunks = ChunkPlanner.plan(samples, rate, listOf(SpeechSpan(1, 0, samples.size)))
        assertEquals(listOf(0, 25 * rate, 50 * rate), chunks.map { it.startSample })
        assertTrue(chunks.all { it.length <= 25 * rate })
    }

    @Test
    fun `pauses before the minimum split point are ignored and tiny tails are glued back`() {
        // Pause at 2 s is too early; 25.1 s turn without a usable pause -> one 25.1 s chunk.
        val samples = speechWithPauses(30, 2.0 to 2.5)
        val chunks = ChunkPlanner.plan(samples, rate, listOf(SpeechSpan(1, 0, 25 * rate + rate / 10)))
        assertEquals(listOf(AsrChunk(1, 0, 25 * rate + rate / 10)), chunks)
    }

    // ---------- TranscriptText ----------

    @Test
    fun `whisper output cleanup`() {
        assertEquals("Добрый день.", TranscriptText.clean("  Добрый   день. "))
        assertNull(TranscriptText.clean("Субтитры сделал DimaTorzok"))
        assertNull(TranscriptText.clean("[Музыка] ♪♪"))
        assertNull(TranscriptText.clean("..."))
        assertEquals("Привет.", TranscriptText.clean("Привет. Продолжение следует..."))
        assertEquals("да, конечно", TranscriptText.clean("да, да, да, да, да, конечно"))
        assertEquals("я думаю что", TranscriptText.clean("я думаю что я думаю что я думаю что я думаю что"))
        assertEquals("нет нет", TranscriptText.clean("нет нет")) // two repeats are normal speech
    }

    // ---------- WER ----------

    @Test
    fun `wer counts substitutions deletions and insertions after normalization`() {
        val deletion = Wer.compute("Ёлка стоит в углу.", "елка стоит углу")
        assertEquals(listOf(0, 1, 0, 4), with(deletion) { listOf(substitutions, deletions, insertions, referenceWords) })
        assertEquals(0.25, deletion.wer, 1e-9)

        val insertion = Wer.compute("стоит в углу", "стоит в углу большая")
        assertEquals(listOf(0, 0, 1), with(insertion) { listOf(substitutions, deletions, insertions) })

        val substitution = Wer.compute("стоит в углу", "стоит на углу")
        assertEquals(listOf(1, 0, 0), with(substitution) { listOf(substitutions, deletions, insertions) })
        assertEquals(0.0, Wer.compute("Да, конечно!", "да конечно").wer, 0.0)
        assertEquals(1.0, Wer.compute("раз два", "три четыре").wer, 0.0)
    }

    // ---------- SpeechEngine assembly ----------

    @Test
    fun `token trimming tightens edges within the chunk`() = runBlocking {
        val audio = PcmAudio.of(FloatArray(10 * rate), rate)
        val diarizer = object : SpeakerDiarizer {
            override val requiredModels = emptyList<String>()
            override suspend fun diarize(audio: PcmAudio, onProgress: (Float) -> Unit) =
                listOf(SpeechSpan(1, 1 * rate, 5 * rate))
        }
        val recognizer = object : SpeechRecognizer {
            override val requiredModels = emptyList<String>()
            override suspend fun transcribe(audio: PcmAudio, chunks: List<AsrChunk>, onProgress: (Float) -> Unit) =
                listOf(Recognition("текст", floatArrayOf(0.5f, 1.0f, 2.0f))) // tokens 1.5 s .. 3.0 s absolute
        }
        val trimmed = DefaultSpeechEngine(
            diarizer, recognizer,
            boundaries = com.hex.stenographer.transcript.BoundaryConfig(trimToTokens = true, leadMs = 100, tailMs = 300),
        ).run(audio)
        assertEquals(listOf(Utterance("speaker_1", 1400, 3300, "текст")), trimmed)
    }

    @Test
    fun `engine maps chunks to utterances with rounded clamped times and drops empty text`() = runBlocking {
        val audio = PcmAudio.of(FloatArray(10 * rate + 7), rate) // duration 10000 ms
        val spans = listOf(
            SpeechSpan(2, 5 * rate, 10 * rate + 7),
            SpeechSpan(1, 8, 2 * rate + 8),
            SpeechSpan(1, 3 * rate, 4 * rate),
        )
        val diarizer = object : SpeakerDiarizer {
            override val requiredModels = emptyList<String>()
            override suspend fun diarize(audio: PcmAudio, onProgress: (Float) -> Unit) = spans
        }
        val texts = mapOf(5 * rate to "Второй.", 8 to "Первый.", 3 * rate to "Субтитры сделал DimaTorzok")
        val recognizer = object : SpeechRecognizer {
            override val requiredModels = emptyList<String>()
            override suspend fun transcribe(audio: PcmAudio, chunks: List<AsrChunk>, onProgress: (Float) -> Unit) =
                chunks.map { Recognition(texts.getValue(it.startSample), FloatArray(0)) }
        }
        val utterances = DefaultSpeechEngine(diarizer, recognizer).run(audio)
        assertEquals(
            listOf(
                Utterance("speaker_1", 1, 2001, "Первый."), // 8 samples = 0.5 ms -> rounds to 1
                Utterance("speaker_2", 5000, 10000, "Второй."), // end clamped to duration
            ),
            utterances,
        )
    }
}
