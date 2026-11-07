package com.hex.stenographer.audio

import java.nio.ByteBuffer

/** Interleaved integer/float PCM layouts produced by WAV files and MediaCodec decoders. */
internal enum class PcmEncoding(val bytesPerSample: Int) {
    U8(1), S16(2), S24(3), S32(4), F32(4), F64(8)
}

/**
 * Converts interleaved multichannel PCM to mono float by averaging channels.
 *
 * The hot formats (S16, F32) are bulk-copied through typed buffer views and mixed over primitive
 * arrays; the rest use absolute reads. Source buffers must be little-endian (WAV is LE by spec,
 * MediaCodec emits native order, which is LE on every Android ABI).
 *
 * Reuses scratch arrays between calls; not thread-safe.
 */
internal class InterleavedToMono(val encoding: PcmEncoding, val channels: Int) {

    init {
        require(channels in 1..MAX_CHANNELS) { "Unsupported channel count: $channels" }
    }

    val frameBytes: Int = encoding.bytesPerSample * channels

    private var shortScratch = ShortArray(0)
    private var floatScratch = FloatArray(0)

    /**
     * Consumes whole frames from [src], advancing its position, and writes at most `dst.size`
     * mono samples to [dst]. Trailing bytes that do not form a full frame are left in [src].
     *
     * @return number of frames written.
     */
    fun convert(src: ByteBuffer, dst: FloatArray): Int {
        val frames = minOf(src.remaining() / frameBytes, dst.size)
        if (frames == 0) return 0
        val total = frames * channels
        when (encoding) {
            PcmEncoding.S16 -> {
                if (shortScratch.size < total) shortScratch = ShortArray(total)
                src.asShortBuffer().get(shortScratch, 0, total)
                mixS16(shortScratch, frames, dst)
            }

            PcmEncoding.F32 -> {
                if (floatScratch.size < total) floatScratch = FloatArray(total)
                src.asFloatBuffer().get(floatScratch, 0, total)
                mixF32(floatScratch, frames, dst)
            }

            else -> mixGeneric(src, frames, dst)
        }
        src.position(src.position() + frames * frameBytes)
        return frames
    }

    private fun mixS16(s: ShortArray, frames: Int, dst: FloatArray) {
        when (channels) {
            1 -> for (i in 0 until frames) dst[i] = s[i] * S16_SCALE
            2 -> {
                val k = S16_SCALE * 0.5f
                var j = 0
                for (i in 0 until frames) {
                    dst[i] = (s[j] + s[j + 1]) * k
                    j += 2
                }
            }

            else -> {
                val k = S16_SCALE / channels
                var j = 0
                for (i in 0 until frames) {
                    var acc = 0
                    for (c in 0 until channels) acc += s[j + c]
                    dst[i] = acc * k
                    j += channels
                }
            }
        }
    }

    private fun mixF32(f: FloatArray, frames: Int, dst: FloatArray) {
        when (channels) {
            1 -> f.copyInto(dst, 0, 0, frames)
            2 -> {
                var j = 0
                for (i in 0 until frames) {
                    dst[i] = (f[j] + f[j + 1]) * 0.5f
                    j += 2
                }
            }

            else -> {
                val k = 1f / channels
                var j = 0
                for (i in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until channels) acc += f[j + c]
                    dst[i] = acc * k
                    j += channels
                }
            }
        }
    }

    private fun mixGeneric(src: ByteBuffer, frames: Int, dst: FloatArray) {
        val bps = encoding.bytesPerSample
        val k = 1f / channels
        var p = src.position()
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += readSample(src, p)
                p += bps
            }
            dst[i] = acc * k
        }
    }

    private fun readSample(b: ByteBuffer, i: Int): Float = when (encoding) {
        PcmEncoding.U8 -> ((b.get(i).toInt() and 0xFF) - 128) * U8_SCALE
        PcmEncoding.S16 -> b.getShort(i) * S16_SCALE
        // Little-endian 24-bit; the signed high byte sign-extends the result.
        PcmEncoding.S24 -> ((b.get(i).toInt() and 0xFF) or
            ((b.get(i + 1).toInt() and 0xFF) shl 8) or
            (b.get(i + 2).toInt() shl 16)) * S24_SCALE

        PcmEncoding.S32 -> b.getInt(i) * S32_SCALE
        PcmEncoding.F32 -> b.getFloat(i)
        PcmEncoding.F64 -> b.getDouble(i).toFloat()
    }

    companion object {
        const val MAX_CHANNELS = 32
        private const val U8_SCALE = 1f / 128f
        private const val S16_SCALE = 1f / 32768f
        private const val S24_SCALE = 1f / 8388608f
        private const val S32_SCALE = 1f / 2147483648f
    }
}
