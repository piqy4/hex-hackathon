package com.hex.stenographer.llm

import com.hex.stenographer.transcript.Utterance

import java.io.File

/** A session belongs to one worker. close() frees the model; each request frees its context. */
class LlamaSession(model: File, threads: Int = 4) : CompletionSession {
    private var handle: Long
    init {
        require(model.isFile) { "Model file is missing" }
        System.loadLibrary("hex_llm")
        handle = nativeOpen(model.absolutePath.toByteArray(Charsets.UTF_8), threads.coerceIn(1, 8))
        check(handle != 0L) { "Cannot load GGUF" }
    }
    @Synchronized override fun complete(system: String, inputJson: String, maxTokens: Int, timeoutMs: Long): String {
        check(handle != 0L) { "Session is closed" }
        return nativeComplete(handle, system.toByteArray(Charsets.UTF_8), inputJson.toByteArray(Charsets.UTF_8),
            maxTokens, timeoutMs).toString(Charsets.UTF_8)
    }
    @Synchronized override fun close() {
        if (handle != 0L) { nativeClose(handle); handle = 0 }
    }
    private external fun nativeOpen(path: ByteArray, threads: Int): Long
    private external fun nativeComplete(handle: Long, system: ByteArray, input: ByteArray, maxTokens: Int, timeoutMs: Long): ByteArray
    private external fun nativeClose(handle: Long)
}
