package com.hex.stenographer.audio

/** Forwards progress in `[0, 1]` only when the whole percent changes, so callers are not flooded. */
internal class ProgressReporter(private val sink: (Float) -> Unit) {
    private var lastPercent = -1

    fun report(fraction: Float) {
        val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
        if (percent <= lastPercent) return
        lastPercent = percent
        sink(percent / 100f)
    }
}
