package com.hex.stenographer.audio

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Brings the recording's speech level to [targetDbfs] in place, gain only (timeline unchanged), so
 * diarization and ASR see quiet far-field recordings at a normal level.
 *
 * The level is the median energy of the loudest [speechFraction] of 20 ms frames: silence does not drag it
 * down and isolated clicks do not dominate it, unlike peak normalization (a single 0.8 click next
 * to −41 dBFS speech made peak normalization a no-op). Gain is limited to
 * [[minGainDb], [maxGainDb]]; samples are clamped to ±1, which only touches such clicks.
 *
 * @return the applied gain in dB.
 */
fun PcmAudio.normalizeLoudnessInPlace(
    targetDbfs: Double = -23.0,
    speechFraction: Double = 0.3,
    minGainDb: Double = -12.0,
    maxGainDb: Double = 30.0,
): Double {
    require(speechFraction > 0 && speechFraction <= 1)
    val level = speechLevelDbfs(speechFraction) ?: return 0.0
    val gainDb = (targetDbfs - level).coerceIn(minGainDb, maxGainDb)
    if (kotlin.math.abs(gainDb) < 0.5) return 0.0
    val gain = 10.0.pow(gainDb / 20).toFloat()
    for (i in samples.indices) samples[i] = (samples[i] * gain).coerceIn(-1f, 1f)
    return gainDb
}

/** Median energy of the loudest [fraction] of 20 ms frames, dBFS; `null` for silence or empty audio. */
internal fun PcmAudio.speechLevelDbfs(fraction: Double): Double? {
    val frame = sampleRate / 50
    val n = samples.size / frame
    if (n == 0) return null
    val energy = DoubleArray(n)
    var p = 0
    for (f in 0 until n) {
        var acc = 0.0
        for (k in 0 until frame) {
            val x = samples[p + k].toDouble()
            acc += x * x
        }
        energy[f] = acc / frame
        p += frame
    }
    energy.sort()
    // Median of the loudest frames: robust both to silence and to a few click frames.
    val from = (n * (1 - fraction)).toInt().coerceIn(0, n - 1)
    val level = energy[from + (n - from) / 2]
    return if (level <= 1e-12) null else 10 * log10(level)
}

/** Shorthand for tests and tools: RMS of the whole signal in dBFS. */
internal fun rmsDbfs(x: FloatArray): Double {
    var acc = 0.0
    for (v in x) acc += v.toDouble() * v
    return 20 * log10(sqrt(acc / x.size).coerceAtLeast(1e-9))
}
