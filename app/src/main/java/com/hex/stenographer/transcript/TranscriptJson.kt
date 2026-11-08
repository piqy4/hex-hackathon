package com.hex.stenographer.transcript

/** Uses the same strict schema as the LLM module's export tests. */
object TranscriptJson {
    fun encode(utterances: List<Utterance>, durationMs: Long): String =
        com.hex.stenographer.llm.TranscriptJson.encode(utterances.sortedBy { it.startMs }, durationMs)
}
