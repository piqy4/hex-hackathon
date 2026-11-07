package com.hex.stenographer.audio

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class RumbleFilterTest {
    @Test fun removesDcAndRumbleWithoutMovingTimeline() {
        fun rms(hz: Double): Double {
            val audio = PcmAudio.of(FloatArray(32000) { (0.2 * sin(2 * PI * hz * it / 16000)).toFloat() }, 16000)
            audio.reduceRumbleInPlace()
            assertEquals(2000L, audio.durationMs)
            assertEquals(32000, audio.sampleCount)
            return sqrt(audio.samples.drop(16000).sumOf { it.toDouble().pow(2) } / 16000)
        }
        assertTrue(rms(20.0) < 0.02)
        assertTrue(rms(200.0) > 0.13)
        val dc = PcmAudio.of(FloatArray(32000) { 0.2f }, 16000)
        dc.reduceRumbleInPlace()
        assertTrue(abs(dc.samples.last()) < 1e-5)
    }
}
