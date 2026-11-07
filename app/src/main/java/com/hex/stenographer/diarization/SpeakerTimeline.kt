package com.hex.stenographer.diarization

import java.util.TreeMap
import kotlin.math.roundToLong

/** Raw diarizer output: seconds from file start and an arbitrary cluster label. */
internal data class RawSegment(val startSec: Float, val endSec: Float, val cluster: Int)

data class TimelineConfig(
    /** Same-speaker intervals separated by at most this pause are merged (start value from the spec). */
    val mergeGapMs: Int = 300,
    /** Fragments shorter than this after overlap resolution are dropped. */
    val minSpanMs: Int = 100,
) {
    init {
        require(mergeGapMs >= 0)
        require(minSpanMs >= 0)
    }
}

/**
 * Turns overlapping, arbitrarily labelled diarizer segments into a clean single-speaker timeline.
 *
 * 1. Convert to sample indices, clamped to the recording.
 * 2. Overlaps: exactly one active speaker at a time. The overlapped region goes to the segment with
 *    the longer original duration; on a tie, to the one that starts earlier. The other segment keeps
 *    only its non-overlapping parts (the second voice is lost there; see README).
 * 3. Merge consecutive same-speaker spans separated by at most [TimelineConfig.mergeGapMs]. The
 *    list is sorted and non-overlapping, so nobody else speaks in such a gap.
 * 4. Drop fragments shorter than [TimelineConfig.minSpanMs], then merge again: removing a blip
 *    can bring two parts of the same speaker together.
 * 5. Renumber speakers 1..N by first appearance.
 *
 * Pure function of its input: deterministic and unit-tested without native code.
 */
internal object SpeakerTimeline {

    fun build(
        raw: List<RawSegment>,
        totalSamples: Int,
        sampleRate: Int,
        config: TimelineConfig = TimelineConfig(),
    ): List<SpeechSpan> {
        val segments = raw.mapNotNull { s ->
            val start = toSample(s.startSec, sampleRate, totalSamples)
            val end = toSample(s.endSec, sampleRate, totalSamples)
            if (end > start) Piece(s.cluster, start, end) else null
        }
        if (segments.isEmpty()) return emptyList()

        val exclusive = resolveOverlaps(segments)
        val mergeGap = msToSamples(config.mergeGapMs, sampleRate)
        val minLength = msToSamples(config.minSpanMs, sampleRate)
        val cleaned = merge(merge(exclusive, mergeGap).filter { it.end - it.start >= minLength }, mergeGap)
        return renumber(cleaned)
    }

    private class Piece(val cluster: Int, val start: Int, val end: Int)

    private fun resolveOverlaps(segments: List<Piece>): List<Piece> {
        val byPriority = segments.sortedWith(
            compareByDescending<Piece> { it.end - it.start }.thenBy { it.start }.thenBy { it.cluster },
        )
        val occupied = TreeMap<Int, Int>() // start -> end of already assigned ranges, disjoint
        val result = ArrayList<Piece>(segments.size)
        val free = ArrayList<Piece>()
        for (seg in byPriority) {
            free.clear()
            var cursor = seg.start
            // A range that starts before us may still cover our beginning.
            occupied.floorEntry(cursor)?.let { if (it.value > cursor) cursor = it.value }
            if (cursor < seg.end) {
                for ((blockStart, blockEnd) in occupied.subMap(cursor, true, seg.end, false)) {
                    if (blockStart > cursor) free += Piece(seg.cluster, cursor, blockStart)
                    cursor = maxOf(cursor, blockEnd)
                }
                if (cursor < seg.end) free += Piece(seg.cluster, cursor, seg.end)
            }
            // Registered after iterating: the map must not change while subMap is being walked.
            for (p in free) occupied[p.start] = p.end
            result += free
        }
        return result
    }

    private fun merge(pieces: List<Piece>, maxGap: Int): List<Piece> {
        if (pieces.isEmpty()) return pieces
        val sorted = pieces.sortedBy { it.start }
        val out = ArrayList<Piece>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            cur = if (next.cluster == cur.cluster && next.start - cur.end <= maxGap) {
                Piece(cur.cluster, cur.start, maxOf(cur.end, next.end))
            } else {
                out += cur
                next
            }
        }
        out += cur
        return out
    }

    private fun renumber(pieces: List<Piece>): List<SpeechSpan> {
        val ids = HashMap<Int, Int>()
        return pieces.map { p ->
            val id = ids.getOrPut(p.cluster) { ids.size + 1 }
            SpeechSpan(id, p.start, p.end)
        }
    }

    private fun toSample(sec: Float, rate: Int, total: Int): Int =
        (sec.toDouble() * rate).roundToLong().coerceIn(0L, total.toLong()).toInt()

    private fun msToSamples(ms: Int, rate: Int): Int = (ms.toLong() * rate / 1000).toInt()
}
