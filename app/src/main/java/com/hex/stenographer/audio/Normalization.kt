package com.hex.stenographer.audio

import kotlin.math.abs

/**
 * Scales the recording in place so its peak reaches [targetPeak], with gain capped at [maxGain]
 * so near-silent files are not blown up to noise. Gain only, no clipping, no change of timeline:
 * safe to run once after import and share the result between diarization and ASR.
 *
 * @return the gain applied (1 when nothing changed).
 */
fun PcmAudio.normalizePeakInPlace(targetPeak: Float = 0.95f, maxGain: Float = 20f): Float {
    require(targetPeak > 0f && targetPeak <= 1f)
    require(maxGain >= 1f)
    var peak = 0f
    for (x in samples) {
        val a = abs(x)
        if (a > peak) peak = a
    }
    if (peak == 0f) return 1f
    val gain = minOf(targetPeak / peak, maxGain)
    if (abs(gain - 1f) < 0.01f) return 1f
    for (i in samples.indices) samples[i] *= gain
    return gain
}
