package com.hex.stenographer.audio

/**
 * Typed import failures, so the UI can tell the user *why* a file was rejected.
 * Messages are technical (for logs); user-facing text lives in string resources.
 */
sealed class AudioImportException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The URI cannot be opened: deleted file, revoked permission, provider failure. */
    class Unreadable(cause: Throwable? = null) :
        AudioImportException("Cannot open audio source", cause)

    /** Container or codec is not supported on this device, or the file has no audio track. */
    class UnsupportedFormat(detail: String, cause: Throwable? = null) :
        AudioImportException("Unsupported audio: $detail", cause)

    /** The file was recognized but its contents are malformed or the decoder failed. */
    class Corrupted(detail: String, cause: Throwable? = null) :
        AudioImportException("Corrupted audio: $detail", cause)

    /** The recording is longer than [limitMs]. */
    class TooLong(val limitMs: Long) :
        AudioImportException("Audio exceeds the limit of $limitMs ms")

    /** Not enough heap to hold the decoded PCM. */
    class InsufficientMemory(cause: Throwable? = null) :
        AudioImportException("Not enough memory to decode audio", cause)
}
