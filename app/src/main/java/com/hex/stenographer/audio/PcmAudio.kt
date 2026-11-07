package com.hex.stenographer.audio

/**
 * Decoded mono float PCM in [-1, 1], aligned to the beginning of the source file:
 * sample `i` corresponds to time `i / sampleRate` seconds from the start of the original track.
 *
 * Not a data class on purpose: structural equality over a multi-megabyte [FloatArray] is never wanted.
 */
class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationMs: Long,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(durationMs >= 0) { "durationMs must be non-negative: $durationMs" }
    }

    val sampleCount: Int get() = samples.size

    /** Start time of sample [index] in milliseconds from the start of the file (floor). */
    fun sampleToMs(index: Int): Long = index.toLong() * 1000 / sampleRate

    /** Index of the sample at [ms], clamped to `[0, sampleCount]`. */
    fun msToSample(ms: Long): Int =
        (ms * sampleRate / 1000).coerceIn(0L, samples.size.toLong()).toInt()

    override fun toString(): String =
        "PcmAudio(samples=${samples.size}, sampleRate=$sampleRate, durationMs=$durationMs)"

    companion object {
        /** Sample rate expected by Whisper and the sherpa-onnx diarization models. */
        const val TARGET_SAMPLE_RATE = 16_000

        fun of(samples: FloatArray, sampleRate: Int): PcmAudio =
            PcmAudio(samples, sampleRate, samples.size.toLong() * 1000 / sampleRate)
    }
}
