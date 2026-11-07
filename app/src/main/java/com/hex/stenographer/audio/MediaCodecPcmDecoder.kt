package com.hex.stenographer.audio

import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes any container/codec supported by the platform (MP3, AAC/M4A, OGG/Opus, FLAC, AMR,
 * G.711/ADPCM WAV, ...) using MediaExtractor + MediaCodec in synchronous mode.
 *
 * Output PCM is streamed into [PcmPipeline] buffer by buffer, so peak memory is independent of
 * the compressed file size. Native resources are always released in `finally`.
 */
internal object MediaCodecPcmDecoder {

    private const val TIMEOUT_US = 10_000L

    /** ~5 s of consecutive empty polls after EOS was queued means the decoder hung. */
    private const val MAX_IDLE_POLLS = 500

    // AudioFormat.ENCODING_PCM_24BIT_PACKED / ENCODING_PCM_32BIT are API 31 constants.
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22

    suspend fun decode(
        afd: AssetFileDescriptor,
        pipeline: PcmPipeline,
        progress: ProgressReporter,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(afd)
            } catch (e: IOException) {
                throw AudioImportException.UnsupportedFormat("container not recognized", e)
            } catch (e: IllegalArgumentException) {
                throw AudioImportException.UnsupportedFormat("container not recognized", e)
            }

            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioImportException.UnsupportedFormat("no audio track")
            val format = extractor.getTrackFormat(track)
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            pipeline.ensureCapacity(durationUs)

            extractor.selectTrack(track)
            codec = createDecoder(format).also {
                it.configure(format, null, null, 0)
                it.start()
            }
            pump(extractor, codec, pipeline, durationUs, progress)
        } catch (e: MediaCodec.CodecException) {
            throw AudioImportException.Corrupted("decoder error ${e.diagnosticInfo}", e)
        } catch (e: IllegalStateException) {
            throw AudioImportException.Corrupted("decoder in invalid state", e)
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
    }

    private fun createDecoder(format: MediaFormat): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw AudioImportException.UnsupportedFormat("track without MIME type")
        return try {
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
        } catch (e: IOException) {
            throw AudioImportException.UnsupportedFormat("no decoder for $mime", e)
        } catch (e: IllegalArgumentException) {
            throw AudioImportException.UnsupportedFormat("no decoder for $mime", e)
        }
    }

    private suspend fun pump(
        extractor: MediaExtractor,
        codec: MediaCodec,
        pipeline: PcmPipeline,
        durationUs: Long,
        progress: ProgressReporter,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idlePolls = 0

        while (true) {
            currentCoroutineContext().ensureActive()

            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = checkNotNull(codec.getInputBuffer(inIndex))
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val timeUs = extractor.sampleTime
                        codec.queueInputBuffer(inIndex, 0, size, timeUs, 0)
                        extractor.advance()
                        if (durationUs > 0) progress.report(timeUs.toFloat() / durationUs)
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    idlePolls = 0
                    if (!pipeline.isConfigured) applyFormat(codec.outputFormat, pipeline)
                    if (info.size > 0) {
                        val buffer = checkNotNull(codec.getOutputBuffer(outIndex))
                        buffer.order(ByteOrder.nativeOrder())
                        buffer.limit(info.offset + info.size).position(info.offset)
                        pipeline.write(buffer, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> applyFormat(codec.outputFormat, pipeline)

                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                    if (++idlePolls > MAX_IDLE_POLLS) throw AudioImportException.Corrupted("decoder stalled")
                }
            }
        }
    }

    private fun applyFormat(format: MediaFormat, pipeline: PcmPipeline) {
        val encoding = when (
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
        ) {
            AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.S16
            AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.F32
            AudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.U8
            ENCODING_PCM_24BIT_PACKED -> PcmEncoding.S24
            ENCODING_PCM_32BIT -> PcmEncoding.S32
            else -> throw AudioImportException.UnsupportedFormat("decoder PCM encoding $format")
        }
        pipeline.setFormat(
            encoding = encoding,
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
        )
    }
}
