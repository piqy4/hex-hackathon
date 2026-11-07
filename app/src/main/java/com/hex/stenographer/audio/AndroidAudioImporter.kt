package com.hex.stenographer.audio

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * [AudioImporter] for SAF `content://` URIs.
 *
 * PCM WAV is parsed directly (exact, no codec latency, works for any length); everything else,
 * including non-PCM WAV, goes through the platform MediaCodec decoders. Both paths stream into
 * the same [PcmPipeline], so downmix, resampling and the timeline are identical.
 */
class AndroidAudioImporter(
    context: Context,
    private val config: AudioImportConfig = AudioImportConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioImporter {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun describe(uri: Uri): AudioFileInfo = withContext(dispatcher) {
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
        } catch (e: SecurityException) {
            throw AudioImportException.Unreadable(e)
        } catch (e: IllegalArgumentException) {
            throw AudioImportException.Unreadable(e)
        }
        AudioFileInfo(
            uri = uri,
            displayName = name ?: uri.lastPathSegment ?: uri.toString(),
            sizeBytes = size,
            mimeType = resolver.getType(uri),
        )
    }

    override suspend fun decode(uri: Uri, onProgress: (Float) -> Unit): PcmAudio =
        withContext(dispatcher) {
            try {
                decodeInternal(uri, ProgressReporter(onProgress))
            } catch (e: OutOfMemoryError) {
                // One huge FloatArray is the only large allocation here, so recovering is safe.
                throw AudioImportException.InsufficientMemory(e)
            }
        }

    private suspend fun decodeInternal(uri: Uri, progress: ProgressReporter): PcmAudio {
        val pipeline = PcmPipeline(config.targetSampleRate, config.maxDurationMs)

        val decodedAsWav = openStream(uri).use { stream ->
            val probe = WavReader.probe(stream)
            if (probe !is WavProbe.Supported) return@use false
            val format = probe.format
            pipeline.ensureCapacity(format.durationUs)
            WavReader.decode(stream, format, pipeline) { bytes ->
                if (format.dataBytes > 0) progress.report(bytes.toFloat() / format.dataBytes)
            }
            true
        }
        if (!decodedAsWav) {
            openDescriptor(uri).use { afd -> MediaCodecPcmDecoder.decode(afd, pipeline, progress) }
        }

        val audio = pipeline.finish()
        if (audio.sampleCount == 0) throw AudioImportException.Corrupted("no audio samples decoded")
        progress.report(1f)
        return audio
    }

    private fun openStream(uri: Uri): InputStream = openOrThrow { resolver.openInputStream(uri) }

    private fun openDescriptor(uri: Uri): AssetFileDescriptor =
        openOrThrow { resolver.openAssetFileDescriptor(uri, "r") }

    private inline fun <T : Any> openOrThrow(open: () -> T?): T =
        try {
            open() ?: throw AudioImportException.Unreadable()
        } catch (e: FileNotFoundException) {
            throw AudioImportException.Unreadable(e)
        } catch (e: SecurityException) {
            throw AudioImportException.Unreadable(e)
        } catch (e: IllegalArgumentException) {
            throw AudioImportException.Unreadable(e)
        }
}
