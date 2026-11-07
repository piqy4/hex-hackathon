package com.hex.stenographer.audio

/**
 * Append-only float storage made of fixed-size chunks.
 *
 * Unlike a doubling array it never over-allocates by more than one chunk and never copies on
 * growth; the single exact-size copy happens in [toFloatArray]. Chunks are released while being
 * copied, so the GC can reclaim them under pressure.
 */
internal class FloatChunkBuffer(
    private val maxSize: Long,
    private val onOverflow: () -> Nothing,
) : FloatSink {

    private val chunks = ArrayList<FloatArray?>()
    private var current: FloatArray? = null
    private var pos = CHUNK

    var size: Long = 0
        private set

    override fun write(src: FloatArray, offset: Int, length: Int) {
        reserve(length.toLong())
        var off = offset
        var rem = length
        while (rem > 0) {
            val chunk = currentChunk()
            val n = minOf(rem, CHUNK - pos)
            src.copyInto(chunk, pos, off, off + n)
            pos += n
            off += n
            rem -= n
        }
    }

    /** Builds the exact-size array and empties this buffer. */
    fun toFloatArray(): FloatArray {
        val result = FloatArray(size.toInt())
        var dst = 0
        for (i in chunks.indices) {
            val chunk = chunks[i] ?: continue
            val n = minOf(CHUNK, result.size - dst)
            chunk.copyInto(result, dst, 0, n)
            dst += n
            chunks[i] = null
        }
        chunks.clear()
        current = null
        pos = CHUNK
        size = 0
        return result
    }

    private fun reserve(count: Long) {
        val newSize = size + count
        if (newSize > maxSize) onOverflow()
        size = newSize
    }

    private fun currentChunk(): FloatArray {
        val c = current
        if (c != null && pos < CHUNK) return c
        val fresh = FloatArray(CHUNK)
        chunks.add(fresh)
        current = fresh
        pos = 0
        return fresh
    }

    private companion object {
        /** 256 KiB per chunk = 4.1 s of 16 kHz audio. */
        const val CHUNK = 1 shl 16
    }
}
