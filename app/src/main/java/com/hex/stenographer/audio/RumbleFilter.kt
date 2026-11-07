package com.hex.stenographer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place Butterworth high-pass: suppresses DC/rumble, never removes time or allocates PCM copies.
 * Opt-in: not a speech separator, and not a cure for broadband noise or competing speech.
 */
fun PcmAudio.reduceRumbleInPlace(cutoffHz: Double = 70.0) {
    require(cutoffHz > 0 && cutoffHz < sampleRate / 2.0)
    val w = 2 * PI * cutoffHz / sampleRate
    val c = cos(w)
    val alpha = sin(w) / sqrt(2.0)
    val a0 = 1 + alpha
    val b0 = (1 + c) / (2 * a0)
    val b1 = -(1 + c) / a0
    val a1 = -2 * c / a0
    val a2 = (1 - alpha) / a0
    var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
    for (i in samples.indices) {
        val x = samples[i].toDouble()
        val y = b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
        samples[i] = y.toFloat().coerceIn(-1f, 1f)
        x2 = x1; x1 = x; y2 = y1; y1 = y
    }
}
