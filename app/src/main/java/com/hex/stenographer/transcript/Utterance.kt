package com.hex.stenographer.transcript

/**
 * One transcript line, exactly the four fields of the export format. Times are integer
 * milliseconds from the start of the source file.
 */
data class Utterance(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    init {
        require(speakerId.isNotBlank()) { "speakerId is blank" }
        require(startMs in 0 until endMs) { "invalid interval [$startMs, $endMs)" }
        require(text.isNotBlank()) { "text is blank" }
    }
}
