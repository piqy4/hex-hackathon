package com.hex.stenographer.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PcmImportTest {

    // ---------- WAV ----------

    @Test
    fun `wav header skips metadata chunks and odd padding`() {
        val pcm = s16Stereo(frames = 100) { i -> (i * 10).toShort() to (-i * 10).toShort() }
        val wav = wav(tag = 1, channels = 2, rate = 44_100, bits = 16, data = pcm, extraChunk = true)
        val probe = WavReader.probe(ByteArrayInputStream(wav))
        val format = (probe as WavProbe.Supported).format
        assertEquals(PcmEncoding.S16, format.encoding)
        assertEquals(2, format.channels)
        assertEquals(44_100, format.sampleRate)
        assertEquals(pcm.size.toLong(), format.dataBytes)
    }

    @Test
    fun `extensible float wav is supported and adpcm is delegated`() {
        val ext = wav(tag = 0xFFFE, subTag = 3, channels = 1, rate = 16_000, bits = 32, data = ByteArray(8))
        assertEquals(PcmEncoding.F32, (WavReader.probe(ByteArrayInputStream(ext)) as WavProbe.Supported).format.encoding)

        val adpcm = wav(tag = 0x11, channels = 1, rate = 8_000, bits = 4, blockAlign = 256, data = ByteArray(256))
        assertTrue(WavReader.probe(ByteArrayInputStream(adpcm)) is WavProbe.Unsupported)
        assertTrue(WavReader.probe(ByteArrayInputStream("ID3\u0004 not a wav".toByteArray())) is WavProbe.NotWav)
    }

    @Test
    fun `16 kHz stereo wav decodes to exact averaged mono`() = runBlocking {
        val frames = 1_000
        val pcm = s16Stereo(frames) { i -> (i * 16).toShort() to (i * 16 + 32).toShort() }
        val audio = decodeWav(wav(tag = 1, channels = 2, rate = 16_000, bits = 16, data = pcm))
        assertEquals(frames, audio.sampleCount)
        assertEquals(frames * 1000L / 16_000, audio.durationMs)
        for (i in 0 until frames) assertEquals((i * 16 + 16) / 32768f, audio.samples[i], 1e-7f)
    }

    @Test
    fun `truncated wav keeps whole frames`() = runBlocking {
        val pcm = s16Stereo(10) { 1.toShort() to 1.toShort() }
        val wav = wav(tag = 1, channels = 2, rate = 16_000, bits = 16, data = pcm, declaredDataBytes = 4_000)
        val truncated = wav.copyOf(wav.size - 2) // last frame cut in half
        assertEquals(9, decodeWav(truncated).sampleCount)
    }

    // ---------- Sample formats ----------

    @Test
    fun `generic formats convert to unit range`() {
        fun one(encoding: PcmEncoding, bytes: ByteArray): Float {
            val dst = FloatArray(1)
            InterleavedToMono(encoding, 1).convert(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), dst)
            return dst[0]
        }
        assertEquals(0f, one(PcmEncoding.U8, byteArrayOf(0x80.toByte())), 0f)
        assertEquals(-1f, one(PcmEncoding.U8, byteArrayOf(0)), 0f)
        assertEquals(-1f, one(PcmEncoding.S24, byteArrayOf(0, 0, 0x80.toByte())), 0f)
        assertEquals(0.5f, one(PcmEncoding.S24, byteArrayOf(0, 0, 0x40)), 0f)
        assertEquals(-0.5f, one(PcmEncoding.S32, le32(Int.MIN_VALUE / 2)), 0f)
    }

    // ---------- Resampler ----------

    @Test
    fun `resampler output length follows the rate ratio`() {
        for ((rate, n) in listOf(48_000 to 48_000, 44_100 to 44_100, 8_000 to 8_000, 22_050 to 12_345)) {
            val out = resample(FloatArray(n), rate, chunk = 997)
            val expected = ceilDiv(n.toLong() * 16_000, rate.toLong())
            assertEquals("rate $rate", expected, out.size.toLong())
        }
    }

    @Test
    fun `resampler keeps in-band tone and rejects aliasing`() {
        for (rate in listOf(44_100, 48_000)) {
            val inBand = resample(sine(1_000.0, rate, rate), rate, chunk = 4_096)
            val rms = rms(inBand, from = 1_000, to = inBand.size - 1_000)
            assertEquals("1 kHz gain at $rate", 1 / sqrt(2.0), rms, 0.01)

            // 10 kHz is above the 8 kHz output Nyquist and would alias to 6 kHz without filtering.
            val alias = resample(sine(10_000.0, rate, rate), rate, chunk = 4_096)
            assertTrue("aliasing at $rate", rms(alias, 1_000, alias.size - 1_000) < 1e-3)
        }
    }

    @Test
    fun `resampler preserves timing of an impulse`() {
        val rate = 44_100
        val input = FloatArray(rate)
        val impulseAt = 22_050 // exactly 0.5 s
        input[impulseAt] = 1f
        val out = resample(input, rate, chunk = 1_000)
        val peak = out.indices.maxBy { abs(out[it]) }
        assertEquals(8_000, peak)
    }

    // ---------- Timeline ----------

    @Test
    fun `pts gap is filled with silence and pre-roll is dropped`() {
        val pipeline = PcmPipeline(16_000, 60_000)
        pipeline.setFormat(PcmEncoding.S16, 1, 16_000)
        val block = ByteBuffer.allocate(1_600 * 2).order(ByteOrder.LITTLE_ENDIAN) // 100 ms
        repeat(1_600) { block.putShort(1000) }

        block.flip(); pipeline.write(block, ptsUs = -50_000) // half of it is pre-roll
        block.rewind(); pipeline.write(block, ptsUs = 50_000) // contiguous
        block.rewind(); pipeline.write(block, ptsUs = 1_000_000) // 850 ms gap
        val audio = pipeline.finish()

        assertEquals(16_000 + 1_600, audio.sampleCount)
        assertEquals(1000 / 32768f, audio.samples[0], 0f)
        assertEquals(0f, audio.samples[audio.msToSample(500)], 0f)
        assertEquals(1000 / 32768f, audio.samples[audio.msToSample(1_000)], 0f)
    }

    @Test(expected = AudioImportException.TooLong::class)
    fun `duration limit is enforced while streaming`() {
        val pipeline = PcmPipeline(16_000, 1_000)
        pipeline.setFormat(PcmEncoding.S16, 1, 16_000)
        pipeline.write(ByteBuffer.allocate(2 * 16_001).order(ByteOrder.LITTLE_ENDIAN))
    }

    // ---------- helpers ----------

    private suspend fun decodeWav(bytes: ByteArray): PcmAudio {
        val input = ByteArrayInputStream(bytes)
        val format = (WavReader.probe(input) as WavProbe.Supported).format
        val pipeline = PcmPipeline(16_000, 60_000)
        WavReader.decode(input, format, pipeline) {}
        return pipeline.finish()
    }

    private fun resample(input: FloatArray, rate: Int, chunk: Int): FloatArray {
        val out = FloatChunkBuffer(Int.MAX_VALUE.toLong()) { error("overflow") }
        val r = MonoResampler.create(rate, 16_000)
        var off = 0
        while (off < input.size) {
            val n = minOf(chunk, input.size - off)
            r.process(input, off, n, out)
            off += n
        }
        r.flush(out)
        return out.toFloatArray()
    }

    private fun sine(freq: Double, rate: Int, n: Int) =
        FloatArray(n) { sin(2 * PI * freq * it / rate).toFloat() }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b

    private fun s16Stereo(frames: Int, sample: (Int) -> Pair<Short, Short>): ByteArray {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val (l, r) = sample(i)
            b.putShort(l).putShort(r)
        }
        return b.array()
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    private fun wav(
        tag: Int,
        channels: Int,
        rate: Int,
        bits: Int,
        data: ByteArray,
        subTag: Int = 0,
        blockAlign: Int = channels * bits / 8,
        extraChunk: Boolean = false,
        declaredDataBytes: Int = data.size,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray())
        if (extraChunk) {
            body.write("LIST".toByteArray()); body.write(le32(3)); body.write(byteArrayOf(1, 2, 3, 0))
        }
        val fmt = ByteArrayOutputStream().apply {
            write(le16(tag)); write(le16(channels)); write(le32(rate))
            write(le32(rate * blockAlign)); write(le16(blockAlign)); write(le16(bits))
            if (tag == 0xFFFE) {
                write(le16(22)); write(le16(bits)); write(le32(0)); write(le16(subTag)); write(ByteArray(14))
            }
        }.toByteArray()
        body.write("fmt ".toByteArray()); body.write(le32(fmt.size)); body.write(fmt)
        body.write("data".toByteArray()); body.write(le32(declaredDataBytes)); body.write(data)
        val b = body.toByteArray()
        return "RIFF".toByteArray() + le32(b.size) + b
    }

}
