package com.hex.stenographer.asr

import com.hex.stenographer.diarization.SpeechSpan

/** A piece of one speaker's turn sent to the recognizer, in samples of the source timeline. */
data class AsrChunk(val speaker: Int, val startSample: Int, val endSample: Int) {
    init {
        require(startSample in 0 until endSample) { "invalid chunk [$startSample, $endSample)" }
    }

    val length: Int get() = endSample - startSample
}

data class ChunkingConfig(
    /** Longer turns are split; Whisper itself accepts at most 30 s. */
    val maxChunkMs: Int = 25_000,
    /** Splits are searched in `[start + minSplitMs, start + maxChunkMs]` so pieces are not tiny. */
    val minSplitMs: Int = 5_000,
    /** A pause is at least this long... */
    val minPauseMs: Int = 100,
    /** ...with frame energy below this fraction of the turn's median frame energy (power, not RMS). */
    val pauseEnergyRatio: Float = 0.05f,
    val frameMs: Int = 20,
    /** Chunks shorter than this are not worth decoding: Whisper hallucinates on them. */
    val minChunkMs: Int = 250,
) {
    init {
        require(maxChunkMs in 1_000..WHISPER_MAX_MS)
        require(minSplitMs in 0 until maxChunkMs)
        require(minPauseMs > 0 && frameMs > 0 && minChunkMs >= 0)
        require(pauseEnergyRatio > 0f)
    }

    companion object {
        const val WHISPER_MAX_MS = 30_000
    }
}

/**
 * Splits diarized turns into recognizer chunks of at most [ChunkingConfig.maxChunkMs]. A long turn
 * is cut at the pause closest to the limit inside the search window; without a pause, at the limit.
 * A tiny remainder is glued back to the previous piece if that stays within Whisper's 30 s.
 *
 * Pure and allocation-light: frame energies are computed only for turns that need splitting.
 */
object ChunkPlanner {

    fun plan(
        samples: FloatArray,
        sampleRate: Int,
        spans: List<SpeechSpan>,
        config: ChunkingConfig = ChunkingConfig(),
    ): List<AsrChunk> {
        val maxLen = ms(config.maxChunkMs, sampleRate)
        val minLen = ms(config.minChunkMs, sampleRate)
        val result = ArrayList<AsrChunk>(spans.size)
        for (span in spans) {
            val end = minOf(span.endSample, samples.size)
            if (end - span.startSample <= maxLen) {
                if (end - span.startSample >= minLen) result += AsrChunk(span.speaker, span.startSample, end)
                continue
            }
            result += splitLong(samples, sampleRate, span.speaker, span.startSample, end, config)
                .filter { it.length >= minLen }
        }
        return result
    }

    private fun splitLong(
        samples: FloatArray,
        rate: Int,
        speaker: Int,
        start: Int,
        end: Int,
        config: ChunkingConfig,
    ): List<AsrChunk> {
        val frame = ms(config.frameMs, rate)
        val energies = frameEnergies(samples, start, end, frame)
        val threshold = median(energies) * config.pauseEnergyRatio
        val minPauseFrames = maxOf(1, config.minPauseMs / config.frameMs)
        val maxLen = ms(config.maxChunkMs, rate)
        val minSplit = ms(config.minSplitMs, rate)

        val pieces = ArrayList<AsrChunk>()
        var cursor = start
        while (end - cursor > maxLen) {
            val fromFrame = (cursor + minSplit - start) / frame
            val toFrame = minOf(energies.size, (cursor + maxLen - start) / frame)
            val pause = latestPause(energies, fromFrame, toFrame, threshold, minPauseFrames)
            val split = if (pause >= 0) start + pause * frame + frame / 2 else cursor + maxLen
            pieces += AsrChunk(speaker, cursor, split)
            cursor = split
        }
        pieces += AsrChunk(speaker, cursor, end)

        // Glue a tiny tail back when Whisper can still take the combined piece.
        if (pieces.size >= 2) {
            val tail = pieces.last()
            val prev = pieces[pieces.size - 2]
            if (tail.length < ms(config.minChunkMs, rate) &&
                tail.endSample - prev.startSample <= ms(ChunkingConfig.WHISPER_MAX_MS, rate)
            ) {
                pieces.removeAt(pieces.size - 1)
                pieces[pieces.size - 1] = AsrChunk(speaker, prev.startSample, tail.endSample)
            }
        }
        return pieces
    }

    /** Index of the quietest frame inside the last qualifying pause in `[from, to)`, or -1. */
    private fun latestPause(e: FloatArray, from: Int, to: Int, threshold: Float, minFrames: Int): Int {
        var i = to - 1
        while (i >= from) {
            if (e[i] >= threshold) {
                i--
                continue
            }
            val runEnd = i
            var best = i
            while (i >= from && e[i] < threshold) {
                if (e[i] < e[best]) best = i
                i--
            }
            if (runEnd - i >= minFrames) return best
        }
        return -1
    }

    private fun frameEnergies(samples: FloatArray, start: Int, end: Int, frame: Int): FloatArray {
        val count = (end - start) / frame
        val out = FloatArray(count)
        var p = start
        for (f in 0 until count) {
            var acc = 0f
            for (k in 0 until frame) {
                val x = samples[p + k]
                acc += x * x
            }
            out[f] = acc / frame
            p += frame
        }
        return out
    }

    private fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf().also { it.sort() }
        return sorted[sorted.size / 2]
    }

    private fun ms(ms: Int, rate: Int): Int = (ms.toLong() * rate / 1000).toInt()
}
