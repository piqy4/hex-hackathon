package com.hex.stenographer.audio

import java.nio.ByteBuffer

/**
 * Streaming conversion of decoder output into the final [PcmAudio]:
 * interleaved PCM -> mono float -> resampled -> chunked storage.
 *
 * Keeps the output on the source timeline: when presentation timestamps are supplied, pre-roll
 * samples with negative time are dropped and discontinuities longer than [GAP_TOLERANCE_US] are
 * filled with silence, so that `sample index / rate` equals time from the start of the file.
 *
 * Memory: only the output buffer grows with the file; working buffers are fixed-size.
 * Not thread-safe; a single decoder thread drives it.
 */
internal class PcmPipeline(
    private val targetRate: Int,
    private val maxDurationMs: Long,
) {
    private val maxSamples = maxDurationMs * targetRate / 1000

    init {
        require(maxSamples in 1..Int.MAX_VALUE) { "maxDurationMs does not fit a FloatArray" }
    }

    private val output = FloatChunkBuffer(
        maxSize = maxSamples,
        onOverflow = { throw AudioImportException.TooLong(maxDurationMs) },
    )
    private val sink = FloatSink(output::write)
    private val scratch = FloatArray(SCRATCH_FRAMES)

    private var converter: InterleavedToMono? = null
    private var resampler: MonoResampler? = null
    private var inputRate = 0

    // Timeline of the current input-rate segment (a new one starts on a sample-rate change).
    private var segmentStartUs = 0L
    private var segmentFrames = 0L

    val isConfigured: Boolean get() = converter != null

    fun setFormat(encoding: PcmEncoding, channels: Int, sampleRate: Int) {
        if (sampleRate <= 0) throw AudioImportException.Corrupted("sample rate $sampleRate")
        if (channels !in 1..InterleavedToMono.MAX_CHANNELS) {
            throw AudioImportException.UnsupportedFormat("$channels channels")
        }
        val c = converter
        if (c == null || c.encoding != encoding || c.channels != channels) {
            converter = InterleavedToMono(encoding, channels)
        }
        if (sampleRate != inputRate) {
            resampler?.flush(sink)
            resampler = MonoResampler.create(sampleRate, targetRate)
            inputRate = sampleRate
            segmentStartUs = output.size * US_PER_S / targetRate
            segmentFrames = 0
        }
    }

    /**
     * Rejects early, before decoding, files whose PCM certainly cannot fit: the duration limit and
     * the heap headroom (the final exact-size copy briefly doubles the footprint).
     */
    fun ensureCapacity(durationUs: Long) {
        if (durationUs <= 0) return
        if (durationUs / 1000 > maxDurationMs) throw AudioImportException.TooLong(maxDurationMs)
        val required = 2L * Float.SIZE_BYTES * (durationUs * targetRate / US_PER_S)
        val rt = Runtime.getRuntime()
        val available = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (required > available) throw AudioImportException.InsufficientMemory()
    }

    /**
     * Consumes all whole frames in [data] (position advances).
     *
     * @param ptsUs presentation time of the first frame, or `null` for gapless sources (WAV).
     */
    fun write(data: ByteBuffer, ptsUs: Long? = null) {
        val conv = checkNotNull(converter) { "setFormat() must be called first" }
        val res = checkNotNull(resampler)

        if (ptsUs != null) {
            var pts = ptsUs
            if (pts < 0) {
                val dropFrames = ceilDiv(-pts * inputRate, US_PER_S)
                val available = (data.remaining() / conv.frameBytes).toLong()
                if (dropFrames >= available) {
                    data.position(data.limit())
                    return
                }
                data.position(data.position() + (dropFrames * conv.frameBytes).toInt())
                pts = 0
            }
            val gapUs = pts - (segmentStartUs + segmentFrames * US_PER_S / inputRate)
            if (gapUs > GAP_TOLERANCE_US) writeSilence(gapUs * inputRate / US_PER_S, res)
        }

        while (true) {
            val n = conv.convert(data, scratch)
            if (n == 0) break
            res.process(scratch, 0, n, sink)
            segmentFrames += n
        }
    }

    fun finish(): PcmAudio {
        resampler?.flush(sink)
        resampler = null
        return PcmAudio.of(output.toFloatArray(), targetRate)
    }

    /** Silence goes through the resampler so the filter state stays continuous. */
    private fun writeSilence(frames: Long, res: MonoResampler) {
        scratch.fill(0f)
        var rem = frames
        while (rem > 0) {
            val n = minOf(rem, SCRATCH_FRAMES.toLong()).toInt()
            res.process(scratch, 0, n, sink)
            segmentFrames += n
            rem -= n
        }
    }

    private companion object {
        const val SCRATCH_FRAMES = 8_192
        const val US_PER_S = 1_000_000L

        /** Decoder timestamps jitter by a frame or so; only real discontinuities are padded. */
        const val GAP_TOLERANCE_US = 100_000L

        fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
    }
}
