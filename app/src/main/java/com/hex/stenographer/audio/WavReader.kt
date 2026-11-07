package com.hex.stenographer.audio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class WavFormat(
    val encoding: PcmEncoding,
    val channels: Int,
    val sampleRate: Int,
    /** Size of the `data` chunk in bytes, or `-1` when unknown (streamed WAV): read to EOF. */
    val dataBytes: Long,
) {
    val frameBytes: Int get() = encoding.bytesPerSample * channels

    val durationUs: Long
        get() = if (dataBytes < 0) -1 else dataBytes / frameBytes * 1_000_000L / sampleRate
}

internal sealed interface WavProbe {
    /** Not a RIFF/WAVE stream: hand it to MediaCodec. */
    data object NotWav : WavProbe

    /** A WAV with a codec we do not parse ourselves (ADPCM, G.711, ...): hand it to MediaCodec. */
    data class Unsupported(val reason: String) : WavProbe

    /** The stream is positioned at the first byte of the `data` chunk payload. */
    data class Supported(val format: WavFormat) : WavProbe
}

/**
 * Minimal streaming RIFF/WAVE reader for PCM integer, IEEE float and WAVE_FORMAT_EXTENSIBLE.
 * Walks chunks properly (skips LIST, fact, bext, ... and honours odd-size padding), so files
 * from recorders and editors that put metadata before `data` are handled.
 */
internal object WavReader {

    private const val TAG_PCM = 1
    private const val TAG_FLOAT = 3
    private const val TAG_EXTENSIBLE = 0xFFFE
    private const val MAX_FMT_BYTES = 1024
    private const val READ_BYTES = 64 * 1024
    private val SAMPLE_RATES = 1_000..768_000

    fun probe(input: InputStream): WavProbe {
        val riff = ByteArray(12)
        if (!input.readFully(riff)) return WavProbe.NotWav
        if (!riff.hasTag(0, "RIFF") || !riff.hasTag(8, "WAVE")) return WavProbe.NotWav

        var fmt: ByteArray? = null
        val header = ByteArray(8)
        while (true) {
            if (!input.readFully(header)) throw AudioImportException.Corrupted("WAV without data chunk")
            val size = header.u32(4)
            when {
                header.hasTag(0, "fmt ") -> {
                    if (size < 16 || size > MAX_FMT_BYTES) {
                        throw AudioImportException.Corrupted("WAV fmt chunk of $size bytes")
                    }
                    val chunk = ByteArray(size.toInt())
                    if (!input.readFully(chunk)) throw AudioImportException.Corrupted("truncated fmt chunk")
                    input.skipFully(size and 1)
                    fmt = chunk
                }

                header.hasTag(0, "data") -> {
                    val f = fmt ?: return WavProbe.Unsupported("data chunk before fmt")
                    return parseFormat(f, size)
                }

                else -> input.skipFully(size + (size and 1))
            }
        }
    }

    private fun parseFormat(f: ByteArray, dataSize: Long): WavProbe {
        var tag = f.u16(0)
        val channels = f.u16(2)
        val sampleRate = f.u32(4)
        val blockAlign = f.u16(12)
        if (tag == TAG_EXTENSIBLE && f.size >= 40) tag = f.u16(24) // first 2 bytes of SubFormat GUID

        if (channels == 0 || blockAlign == 0 || sampleRate == 0L) {
            throw AudioImportException.Corrupted("WAV fmt: channels=$channels blockAlign=$blockAlign rate=$sampleRate")
        }
        if (channels > InterleavedToMono.MAX_CHANNELS) return WavProbe.Unsupported("$channels channels")
        if (sampleRate !in SAMPLE_RATES.first.toLong()..SAMPLE_RATES.last.toLong()) {
            return WavProbe.Unsupported("sample rate $sampleRate")
        }
        if (blockAlign % channels != 0) return WavProbe.Unsupported("blockAlign $blockAlign")

        // Container size decides decoding; valid bits are left-justified, so scaling stays correct.
        val bytesPerSample = blockAlign / channels
        val encoding = when (tag) {
            TAG_PCM -> when (bytesPerSample) {
                1 -> PcmEncoding.U8
                2 -> PcmEncoding.S16
                3 -> PcmEncoding.S24
                4 -> PcmEncoding.S32
                else -> null
            }

            TAG_FLOAT -> when (bytesPerSample) {
                4 -> PcmEncoding.F32
                8 -> PcmEncoding.F64
                else -> null
            }

            else -> null
        } ?: return WavProbe.Unsupported("format tag 0x${tag.toString(16)}, $bytesPerSample bytes/sample")

        val dataBytes = if (dataSize == 0L || dataSize == 0xFFFF_FFFFL) -1L else dataSize
        return WavProbe.Supported(WavFormat(encoding, channels, sampleRate.toInt(), dataBytes))
    }

    /**
     * Streams the `data` payload into [pipeline]. A truncated payload (common for interrupted
     * recordings) is accepted up to the last whole frame.
     *
     * @param onBytes total payload bytes consumed so far.
     */
    suspend fun decode(
        input: InputStream,
        format: WavFormat,
        pipeline: PcmPipeline,
        onBytes: (Long) -> Unit,
    ) {
        pipeline.setFormat(format.encoding, format.channels, format.sampleRate)
        val frameBytes = format.frameBytes
        val buffer = ByteArray(maxOf(1, READ_BYTES / frameBytes) * frameBytes)
        val view = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        var remaining = if (format.dataBytes < 0) Long.MAX_VALUE else format.dataBytes
        var filled = 0
        var consumed = 0L

        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val toRead = minOf((buffer.size - filled).toLong(), remaining).toInt()
            val n = input.read(buffer, filled, toRead)
            if (n < 0) break
            filled += n
            remaining -= n
            consumed += n

            val usable = filled - filled % frameBytes
            if (usable > 0) {
                view.clear().limit(usable)
                pipeline.write(view)
                val tail = filled - usable
                if (tail > 0) buffer.copyInto(buffer, 0, usable, filled)
                filled = tail
            }
            onBytes(consumed)
        }
    }

    private fun InputStream.readFully(dst: ByteArray): Boolean {
        var off = 0
        while (off < dst.size) {
            val n = read(dst, off, dst.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    /** [InputStream.skip] may legally skip less (or 0); fall back to reading. */
    private fun InputStream.skipFully(count: Long) {
        var rem = count
        var scratch: ByteArray? = null
        while (rem > 0) {
            val skipped = skip(rem)
            if (skipped > 0) {
                rem -= skipped
                continue
            }
            val s = scratch ?: ByteArray(8 * 1024).also { scratch = it }
            val n = read(s, 0, minOf(rem, s.size.toLong()).toInt())
            if (n < 0) throw AudioImportException.Corrupted("WAV truncated inside a chunk")
            rem -= n
        }
    }

    private fun ByteArray.hasTag(offset: Int, tag: String): Boolean =
        (0 until 4).all { this[offset + it] == tag[it].code.toByte() }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Long =
        (u16(offset).toLong() or (u16(offset + 2).toLong() shl 16))
}
