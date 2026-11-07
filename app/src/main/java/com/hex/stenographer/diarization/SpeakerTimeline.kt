package com.hex.stenographer.diarization

import java.util.TreeMap
import kotlin.math.roundToLong

/** Raw diarizer output: seconds from file start and an arbitrary cluster label. */
internal data class RawSegment(val startSec: Float, val endSec: Float, val cluster: Int)

enum class OverlapPolicy { SplitTransitions, LatestStart, LongestSegment }

data class TimelineConfig(
    /** Same-speaker intervals separated by at most this pause are merged (start value from the spec). */
    val mergeGapMs: Int = 300,
    /** Fragments shorter than this after overlap resolution are dropped. */
    val minSpanMs: Int = 100,
    /** Split crossing transitions, retaining nested short interjections. */
    val overlapPolicy: OverlapPolicy = OverlapPolicy.SplitTransitions,
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
 * 2. Overlaps: exactly one active speaker at a time. Crossing transitions split at their midpoint;
 *    a short nested interruption is not swallowed by a long enclosing turn. The earlier speaker resumes
 *    after the interruption when its interval continues. This is turn arbitration, not source separation.
 *    LongestSegment retains the original baseline for controlled comparisons.
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

        val exclusive = resolveOverlaps(segments, config.overlapPolicy)
        val mergeGap = msToSamples(config.mergeGapMs, sampleRate)
        val minLength = msToSamples(config.minSpanMs, sampleRate)
        val cleaned = merge(merge(exclusive, mergeGap).filter { it.end - it.start >= minLength }, mergeGap)
        return renumber(cleaned)
    }

    private class Piece(val cluster: Int, val start: Int, val end: Int)

    private fun resolveOverlaps(segments: List<Piece>, policy: OverlapPolicy): List<Piece> {
        val adjusted = if (policy == OverlapPolicy.SplitTransitions) splitTransitions(segments) else segments
        val byPriority = adjusted.sortedWith(
            when (policy) {
                OverlapPolicy.SplitTransitions, OverlapPolicy.LatestStart -> compareByDescending<Piece> { it.start }
                    .thenByDescending { it.end - it.start }.thenBy { it.cluster }
                OverlapPolicy.LongestSegment -> compareByDescending<Piece> { it.end - it.start }
                    .thenBy { it.start }.thenBy { it.cluster }
            },
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

    /** For crossing turns, neither binary onset nor offset is a reliable exclusive boundary.
     * Split their shared transition at its midpoint. Fully nested interruptions stay intact.
     * This does not claim to recover both voices from simultaneous speech.
     */
    private fun splitTransitions(pieces: List<Piece>): List<Piece> {
        val sorted = pieces.sortedWith(compareBy<Piece> { it.start }.thenBy { it.end }.thenBy { it.cluster })
        val starts = sorted.map { it.start }.toIntArray()
        val ends = sorted.map { it.end }.toIntArray()
        for (i in sorted.indices) {
            val a = sorted[i]
            var j = i + 1
            while (j < sorted.size && sorted[j].start < a.end) {
                val b = sorted[j]
                if (a.cluster != b.cluster && a.start < b.start && a.end < b.end) {
                    val middle = b.start + (a.end - b.start) / 2
                    ends[i] = minOf(ends[i], middle)
                    starts[j] = maxOf(starts[j], middle)
                }
                j++
            }
        }
        return sorted.indices.mapNotNull { i ->
            if (ends[i] > starts[i]) Piece(sorted[i].cluster, starts[i], ends[i]) else null
        }
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
