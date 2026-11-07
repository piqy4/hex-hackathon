package com.hex.stenographer.audio

import android.net.Uri

/** Metadata of a user-selected file, shown before processing starts. */
data class AudioFileInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val mimeType: String?,
)

data class AudioImportConfig(
    val targetSampleRate: Int = PcmAudio.TARGET_SAMPLE_RATE,
    /** Hard cap protecting the heap: 3 h of 16 kHz float PCM is ~690 MB. */
    val maxDurationMs: Long = 3 * 60 * 60 * 1000L,
) {
    init {
        require(targetSampleRate > 0)
        require(maxDurationMs > 0)
    }
}

/** First pipeline stage: user-picked URI -> mono float PCM at [AudioImportConfig.targetSampleRate]. */
interface AudioImporter {
    /** @throws AudioImportException.Unreadable */
    suspend fun describe(uri: Uri): AudioFileInfo

    /**
     * Decodes the whole file. Cancellable; [onProgress] receives values in `[0, 1]`
     * from a background thread, at most once per percent.
     *
     * @throws AudioImportException
     */
    suspend fun decode(uri: Uri, onProgress: (Float) -> Unit = {}): PcmAudio
}
