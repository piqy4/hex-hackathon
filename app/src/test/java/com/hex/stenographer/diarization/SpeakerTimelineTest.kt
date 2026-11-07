package com.hex.stenographer.diarization

import com.hex.stenographer.audio.PcmAudio
import com.hex.stenographer.audio.normalizeLoudnessInPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpeakerTimelineTest {

    private val rate = 16_000
    private val total = 120 * rate

    private fun build(vararg raw: RawSegment, config: TimelineConfig = TimelineConfig()) =
        SpeakerTimeline.build(raw.toList(), total, rate, config)

    private fun seg(start: Double, end: Double, cluster: Int) = RawSegment(start.toFloat(), end.toFloat(), cluster)

    private fun span(speaker: Int, start: Double, end: Double) =
        SpeechSpan(speaker, (start * rate).toInt(), (end * rate).toInt())

    @Test
    fun `A-B-A returns the first id and ids follow first appearance`() {
        val spans = build(seg(5.0, 8.0, 7), seg(0.0, 4.0, 3), seg(9.0, 12.0, 3))
        assertEquals(listOf(span(1, 0.0, 4.0), span(2, 5.0, 8.0), span(1, 9.0, 12.0)), spans)
        assertEquals("speaker_1", spans[2].speakerId)
    }

    @Test
    fun `same speaker is merged across a short pause only`() {
        val merged = build(seg(0.0, 1.0, 0), seg(1.3, 2.0, 0))
        assertEquals(listOf(span(1, 0.0, 2.0)), merged)

        val separate = build(seg(0.0, 1.0, 0), seg(1.301, 2.0, 0))
        assertEquals(2, separate.size)
    }

    @Test
    fun `pause with another voice in it is never merged`() {
        val spans = build(seg(0.0, 1.0, 0), seg(1.05, 1.25, 1), seg(1.3, 2.0, 0))
        assertEquals(listOf(1, 2, 1), spans.map { it.speaker })
    }

    @Test
    fun `overlap goes to the longer segment and the shorter keeps the rest`() {
        // B (3 s) overlaps the end of A (2 s): the shared second goes to B.
        val spans = build(seg(0.0, 2.0, 0), seg(1.0, 4.0, 1))
        assertEquals(listOf(span(1, 0.0, 1.0), span(2, 1.0, 4.0)), spans)
    }

    @Test
    fun `interjection inside a longer turn is absorbed by it`() {
        val spans = build(seg(0.0, 10.0, 0), seg(4.0, 5.0, 1))
        assertEquals(listOf(span(1, 0.0, 10.0)), spans)
    }

    @Test
    fun `segment overlapped on both sides keeps only its middle`() {
        val spans = build(seg(3.0, 5.0, 0), seg(0.0, 3.5, 1), seg(4.5, 9.0, 1))
        assertEquals(listOf(span(1, 0.0, 3.5), span(2, 3.5, 4.5), span(1, 4.5, 9.0)), spans)
    }

    @Test
    fun `equal duration overlap goes to the earlier start`() {
        val spans = build(seg(1.0, 3.0, 1), seg(0.0, 2.0, 0))
        assertEquals(listOf(span(1, 0.0, 2.0), span(2, 2.0, 3.0)), spans)
    }

    @Test
    fun `blips are dropped and neighbours of the same speaker re-merged`() {
        val spans = build(seg(0.0, 1.0, 0), seg(1.0, 1.05, 1), seg(1.05, 2.0, 0))
        assertEquals(listOf(span(1, 0.0, 2.0)), spans)
    }

    @Test
    fun `segments are clamped to the recording and empty input gives empty output`() {
        assertEquals(emptyList<SpeechSpan>(), build())
        val spans = build(seg(-1.0, 0.5, 0), seg(119.0, 130.0, 1), seg(125.0, 126.0, 2))
        assertEquals(listOf(SpeechSpan(1, 0, rate / 2), SpeechSpan(2, 119 * rate, total)), spans)
    }

    @Test
    fun `random input always yields a valid timeline`() {
        val random = Random(42)
        repeat(200) {
            val raw = List(random.nextInt(1, 60)) {
                val start = random.nextDouble(-1.0, 121.0)
                seg(start, start + random.nextDouble(0.0, 8.0), random.nextInt(4))
            }
            val spans = SpeakerTimeline.build(raw, total, rate)
            assertNonOverlappingSorted(spans)
            spans.forEach { assertTrue(it.endSample <= total) }
            // Ids are dense and appear in order 1, 2, 3...
            val firstSeen = spans.map { it.speaker }.distinct()
            assertEquals((1..firstSeen.size).toList(), firstSeen)
        }
    }

    @Test
    fun `loudness normalization ignores silence and clicks and caps gain`() {
        // 1 s of -41 dBFS tone between 2 s of silence, plus one 0.8 click: peak normalization would do nothing.
        val x = FloatArray(4 * rate)
        val amp = (Math.pow(10.0, -41.0 / 20) * Math.sqrt(2.0)).toFloat()
        for (i in 0 until rate) x[2 * rate + i] = amp * kotlin.math.sin(2 * Math.PI * 300 * i / rate).toFloat()
        x[100] = 0.8f
        val audio = PcmAudio.of(x, rate)
        val gain = audio.normalizeLoudnessInPlace(speechFraction = 0.25)
        assertEquals(18.0, gain, 0.6) // -41 -> -23 dBFS
        assertEquals(1f, audio.samples[100], 0f) // the click is clamped, not the speech
        assertTrue(audio.samples.all { it in -1f..1f })

        val nearSilent = PcmAudio.of(FloatArray(rate) { if (it % 2 == 0) 1e-5f else -1e-5f }, rate)
        assertEquals(30.0, nearSilent.normalizeLoudnessInPlace(), 0.0) // capped

        assertEquals(0.0, PcmAudio.of(FloatArray(rate), rate).normalizeLoudnessInPlace(), 0.0)
    }

    private fun assertNonOverlappingSorted(spans: List<SpeechSpan>) {
        for (i in 1 until spans.size) {
            assertTrue("sorted/non-overlapping at $i: $spans", spans[i - 1].endSample <= spans[i].startSample)
        }
    }
}
