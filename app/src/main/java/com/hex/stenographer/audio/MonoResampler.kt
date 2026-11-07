package com.hex.stenographer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal fun interface FloatSink {
    fun write(src: FloatArray, offset: Int, length: Int)
}

/** Streaming mono sample-rate converter. Output sample `n` is aligned to input time `n * in / out`. */
internal interface MonoResampler {
    fun process(input: FloatArray, offset: Int, length: Int, sink: FloatSink)

    /** Emits the remaining tail. The resampler must not be used afterwards. */
    fun flush(sink: FloatSink)

    companion object {
        fun create(inputRate: Int, outputRate: Int): MonoResampler =
            if (inputRate == outputRate) PassThrough else SincResampler(inputRate, outputRate)
    }

    private object PassThrough : MonoResampler {
        override fun process(input: FloatArray, offset: Int, length: Int, sink: FloatSink) =
            sink.write(input, offset, length)

        override fun flush(sink: FloatSink) = Unit
    }
}

/**
 * Polyphase windowed-sinc (Kaiser) resampler for an arbitrary rational ratio.
 *
 * - Anti-aliasing cutoff sits at [rolloff] of the lower Nyquist frequency, so 44.1/48 kHz -> 16 kHz
 *   does not fold 8–24 kHz content into the speech band.
 * - Exact rational stepping (`out/in` reduced by gcd) keeps the timeline drift-free on long files;
 *   for exotic rates the phase table is capped at [maxPhases] and the nearest phase is used.
 * - Each phase is normalized to unity DC gain.
 * - Zero group delay: output `n` is centred on input time `n * in / out`, so timestamps stay aligned
 *   with the source file.
 */
internal class SincResampler(
    inputRate: Int,
    outputRate: Int,
    zeroCrossings: Int = 12,
    rolloff: Double = 0.92,
    kaiserBeta: Double = 7.5,
    maxPhases: Int = 1024,
) : MonoResampler {

    private val up: Int
    private val down: Int
    private val phases: Int
    private val halfTaps: Int
    private val taps: Int
    private val table: FloatArray

    // Input history window: buf[0] holds absolute input index bufStart.
    private var buf: FloatArray
    private var bufStart: Long
    private var bufLen: Int
    private var totalInput = 0L

    // Position of the next output in input samples: base + phaseNum / up.
    private var base = 0L
    private var phaseNum = 0

    private val out = FloatArray(OUT_BLOCK)
    private var outLen = 0
    private var finished = false

    init {
        require(inputRate > 0 && outputRate > 0) { "Rates must be positive: $inputRate -> $outputRate" }
        val g = gcd(inputRate, outputRate)
        up = outputRate / g
        down = inputRate / g

        // Cutoff in cycles per *input* sample.
        val fc = 0.5 * min(1.0, outputRate.toDouble() / inputRate) * rolloff
        val halfWidth = zeroCrossings / (2.0 * fc)
        halfTaps = ceil(halfWidth).toInt()
        taps = 2 * halfTaps
        phases = min(up, maxPhases)
        table = buildTable(fc, halfWidth, kaiserBeta)

        buf = FloatArray(taps + INPUT_BLOCK)
        // Virtual zeros before the stream so that output 0 sees a full window.
        bufStart = -(halfTaps - 1).toLong()
        bufLen = halfTaps - 1
    }

    private fun buildTable(fc: Double, halfWidth: Double, beta: Double): FloatArray {
        val t = FloatArray(phases * taps)
        val i0Beta = besselI0(beta)
        val coef = DoubleArray(taps)
        for (p in 0 until phases) {
            val frac = p.toDouble() / phases
            var sum = 0.0
            for (j in 0 until taps) {
                val x = frac + (halfTaps - 1 - j) // distance from tap j to the output instant
                val h = if (abs(x) >= halfWidth) {
                    0.0
                } else {
                    val r = x / halfWidth
                    2 * fc * sinc(2 * fc * x) * besselI0(beta * sqrt(1 - r * r)) / i0Beta
                }
                coef[j] = h
                sum += h
            }
            val offset = p * taps
            for (j in 0 until taps) t[offset + j] = (coef[j] / sum).toFloat()
        }
        return t
    }

    override fun process(input: FloatArray, offset: Int, length: Int, sink: FloatSink) {
        check(!finished) { "Resampler already flushed" }
        var off = offset
        var rem = length
        while (rem > 0) {
            makeRoom()
            val n = min(rem, buf.size - bufLen)
            input.copyInto(buf, bufLen, off, off + n)
            bufLen += n
            totalInput += n
            off += n
            rem -= n
            produce(bufStart + bufLen - halfTaps, sink)
        }
    }

    override fun flush(sink: FloatSink) {
        if (finished) return
        finished = true
        // Pad with zeros so tail windows are complete; emit every output with time < totalInput.
        var pad = halfTaps
        while (pad > 0) {
            makeRoom()
            val n = min(pad, buf.size - bufLen)
            buf.fill(0f, bufLen, bufLen + n)
            bufLen += n
            pad -= n
        }
        produce(totalInput, sink)
    }

    /** Emits outputs whose window base is `< endBaseExclusive` and fits into the buffer. */
    private fun produce(endBaseExclusive: Long, sink: FloatSink) {
        val limit = min(endBaseExclusive, bufStart + bufLen - halfTaps)
        while (true) {
            var b = base
            var p: Int
            if (phases == up) {
                p = phaseNum
            } else {
                p = ((phaseNum.toLong() * phases + up / 2) / up).toInt()
                if (p == phases) {
                    p = 0
                    b++
                }
            }
            if (b >= limit) break

            val start = (b - halfTaps + 1 - bufStart).toInt()
            val tOff = p * taps
            var acc = 0f
            for (j in 0 until taps) acc += buf[start + j] * table[tOff + j]
            out[outLen++] = acc
            if (outLen == OUT_BLOCK) {
                sink.write(out, 0, outLen)
                outLen = 0
            }

            phaseNum += down
            base += phaseNum / up
            phaseNum %= up
        }
        if (outLen > 0) {
            sink.write(out, 0, outLen)
            outLen = 0
        }
    }

    /** Ensures free space at the end of [buf] by dropping consumed history, or by growing it. */
    private fun makeRoom() {
        if (bufLen < buf.size) return
        val drop = (base - halfTaps + 1 - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
        if (drop > 0) {
            buf.copyInto(buf, 0, drop, bufLen)
            bufStart += drop
            bufLen -= drop
        } else {
            buf = buf.copyOf(buf.size * 2)
        }
    }

    private companion object {
        const val INPUT_BLOCK = 16_384
        const val OUT_BLOCK = 4_096

        fun gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while (y != 0) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Modified Bessel function of the first kind, order 0 (power series). */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (term > sum * 1e-12) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}
