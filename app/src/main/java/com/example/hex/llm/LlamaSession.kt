package com.example.hex.llm

import java.io.File

/** Compatibility adapter for the original role-3 tests; JNI has one canonical implementation. */
class LlamaSession(model: File, threads: Int = 4) : CompletionSession {
    private val delegate = com.hex.stenographer.llm.LlamaSession(model, threads)
    override fun complete(system: String, inputJson: String, maxTokens: Int, timeoutMs: Long): String =
        delegate.complete(system, inputJson, maxTokens, timeoutMs)
    override fun close() = delegate.close()
}
