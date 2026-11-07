package com.hex.stenographer.diarization

/**
 * A single-speaker interval on the timeline of the source file, in samples of [com.hex.stenographer.audio.PcmAudio].
 *
 * [speaker] is 1-based and numbered by first appearance within one recording, so it maps directly
 * to the exported `speaker_N` id.
 */
data class SpeechSpan(
    val speaker: Int,
    val startSample: Int,
    val endSample: Int,
) {
    init {
        require(speaker >= 1) { "speaker must be 1-based: $speaker" }
        require(startSample in 0 until endSample) { "invalid span [$startSample, $endSample)" }
    }

    val speakerId: String get() = "speaker_$speaker"

    val length: Int get() = endSample - startSample
}
