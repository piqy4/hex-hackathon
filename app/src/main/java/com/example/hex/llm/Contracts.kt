package com.example.hex.llm

/** Times are absolute, measured from the beginning of the original recording. */
data class Utterance(val speakerId: String, val startMs: Long, val endMs: Long, val text: String)

interface TextPolisher {
    /** Blocking CPU operation: call on a worker, after releasing ASR/diarization resources. */
    fun polish(items: List<Utterance>): List<Utterance>
}

interface CompletionSession : AutoCloseable {
    fun complete(system: String, inputJson: String, maxTokens: Int, timeoutMs: Long): String
}

data class PolishEvent(
    val batch: Int,
    val size: Int,
    val elapsedMs: Long,
    val status: String,
    val changed: Int = 0
)
