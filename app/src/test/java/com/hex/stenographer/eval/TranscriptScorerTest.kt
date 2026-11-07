package com.hex.stenographer.eval

import com.hex.stenographer.transcript.Utterance
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptScorerTest {

    private fun u(speaker: String, start: Long, end: Long, text: String) = Utterance(speaker, start, end, text)

    private val reference = listOf(
        u("A", 0, 2000, "добрый день"),
        u("B", 2500, 4000, "здравствуйте"),
        u("A", 4500, 6000, "начнём"),
    )

    @Test
    fun `perfect hypothesis with other speaker ids scores zero errors`() {
        val hyp = listOf(
            u("speaker_1", 0, 2000, "Добрый день!"),
            u("speaker_2", 2500, 4000, "Здравствуйте."),
            u("speaker_1", 4500, 6000, "Начнём."),
        )
        val s = TranscriptScorer.score(reference, hyp)
        assertEquals(0.0, s.wer.wer, 0.0)
        assertEquals(0.0, s.diarizationErrorRate, 0.0)
        assertEquals(mapOf("speaker_1" to "A", "speaker_2" to "B"), s.speakerMapping)
        assertEquals(listOf(3, 0, 0, 0), listOf(s.matched, s.missed, s.extra, s.speakerErrors))
        assertEquals(0L, s.maxBoundaryErrorMs)
    }

    @Test
    fun `boundary errors, missed and extra turns, wrong speaker`() {
        val hyp = listOf(
            u("speaker_1", 100, 1900, "добрый день"), // starts 100 late, ends 100 early
            u("speaker_1", 4400, 6300, "начнём"), // B missed entirely; A again
            u("speaker_2", 7000, 7500, "угу"), // speech where the reference is silent
        )
        val s = TranscriptScorer.score(reference, hyp)
        assertEquals(2, s.matched)
        assertEquals(1, s.missed)
        assertEquals(1, s.extra)
        assertEquals(0, s.speakerErrors)
        assertEquals(100.0, s.meanStartErrorMs, 1e-9)
        assertEquals((100.0 + 300.0) / 2, s.meanEndErrorMs, 1e-9)
        assertEquals(300L, s.maxBoundaryErrorMs)
        // WER: "здравствуйте" deleted, "угу" inserted over 4 reference words.
        assertEquals(0.5, s.wer.wer, 1e-9)
    }

    @Test
    fun `one hypothesis speaker for everybody gives a confusion on the other speaker`() {
        val hyp = reference.map { it.copy(speakerId = "speaker_1") }
        val s = TranscriptScorer.score(reference, hyp)
        assertEquals(mapOf("speaker_1" to "A"), s.speakerMapping)
        assertEquals(1, s.speakerErrors)
        assertEquals(1500.0 / 5000.0, s.diarizationErrorRate, 1e-9)
    }
}
