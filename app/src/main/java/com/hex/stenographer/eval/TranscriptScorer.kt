package com.hex.stenographer.eval

import com.hex.stenographer.transcript.Utterance
import kotlin.math.abs

/** Quality of a transcript against a reference, with the metrics from the spec (section 07). */
data class TranscriptScore(
    val wer: WerResult,
    val referenceUtterances: Int,
    val hypothesisUtterances: Int,
    /** Reference utterances overlapped by at least one hypothesis utterance. */
    val matched: Int,
    /** Reference utterances nobody overlaps (lost turns, e.g. short replies). */
    val missed: Int,
    /** Hypothesis utterances overlapping no reference speech (false turns, e.g. in silence). */
    val extra: Int,
    /** Mean and max |error| of matched starts/ends, ms. */
    val meanStartErrorMs: Double,
    val meanEndErrorMs: Double,
    val maxBoundaryErrorMs: Long,
    /** Matched utterances whose mapped speaker differs from the reference one. */
    val speakerErrors: Int,
    val referenceSpeakers: Int,
    val hypothesisSpeakers: Int,
    /** Hypothesis id -> reference id, decided once for the whole recording. */
    val speakerMapping: Map<String, String>,
    /** (missed + false alarm + confusion) / reference speech, on a 10 ms grid. */
    val diarizationErrorRate: Double,
) {
    fun summary(): String = buildString {
        append("WER=%.3f (S=%d D=%d I=%d N=%d) ".format(wer.wer, wer.substitutions, wer.deletions, wer.insertions, wer.referenceWords))
        append("DER=%.3f speakers=%d/%d ".format(diarizationErrorRate, hypothesisSpeakers, referenceSpeakers))
        append("utt=%d/%d matched=%d missed=%d extra=%d speakerErr=%d ".format(hypothesisUtterances, referenceUtterances, matched, missed, extra, speakerErrors))
        append("startErr=%.0fms endErr=%.0fms maxErr=%dms".format(meanStartErrorMs, meanEndErrorMs, maxBoundaryErrorMs))
    }
}

object TranscriptScorer {

    private const val FRAME_MS = 10L

    fun score(reference: List<Utterance>, hypothesis: List<Utterance>): TranscriptScore {
        val ref = reference.sortedBy { it.startMs }
        val hyp = hypothesis.sortedBy { it.startMs }
        val wer = Wer.compute(ref.joinToString(" ") { it.text }, hyp.joinToString(" ") { it.text })
        val mapping = mapSpeakers(ref, hyp)

        var matched = 0
        var speakerErrors = 0
        var startErr = 0L
        var endErr = 0L
        var maxErr = 0L
        for (r in ref) {
            val best = hyp.maxByOrNull { overlap(it, r) }?.takeIf { overlap(it, r) > 0 } ?: continue
            matched++
            val ds = abs(best.startMs - r.startMs)
            val de = abs(best.endMs - r.endMs)
            startErr += ds
            endErr += de
            maxErr = maxOf(maxErr, ds, de)
            if (mapping[best.speakerId] != r.speakerId) speakerErrors++
        }
        val extra = hyp.count { h -> ref.none { overlap(it, h) > 0 } }

        return TranscriptScore(
            wer = wer,
            referenceUtterances = ref.size,
            hypothesisUtterances = hyp.size,
            matched = matched,
            missed = ref.size - matched,
            extra = extra,
            meanStartErrorMs = if (matched == 0) 0.0 else startErr.toDouble() / matched,
            meanEndErrorMs = if (matched == 0) 0.0 else endErr.toDouble() / matched,
            maxBoundaryErrorMs = maxErr,
            speakerErrors = speakerErrors,
            referenceSpeakers = ref.distinctBy { it.speakerId }.size,
            hypothesisSpeakers = hyp.distinctBy { it.speakerId }.size,
            speakerMapping = mapping,
            diarizationErrorRate = der(ref, hyp, mapping),
        )
    }

    /** One-to-one, greedy by the largest total overlap between a hypothesis and a reference speaker. */
    private fun mapSpeakers(ref: List<Utterance>, hyp: List<Utterance>): Map<String, String> {
        val totals = HashMap<Pair<String, String>, Long>()
        for (h in hyp) for (r in ref) {
            val o = overlap(h, r)
            if (o > 0) totals.merge(h.speakerId to r.speakerId, o, Long::plus)
        }
        val mapping = HashMap<String, String>()
        val usedRef = HashSet<String>()
        for ((pair, _) in totals.entries.sortedByDescending { it.value }) {
            val (h, r) = pair
            if (h !in mapping && r !in usedRef) {
                mapping[h] = r
                usedRef += r
            }
        }
        return mapping
    }

    private fun der(ref: List<Utterance>, hyp: List<Utterance>, mapping: Map<String, String>): Double {
        val end = maxOf(ref.maxOfOrNull { it.endMs } ?: 0, hyp.maxOfOrNull { it.endMs } ?: 0)
        val frames = (end / FRAME_MS + 1).toInt()
        val refAt = labels(ref, frames) { it.speakerId }
        val hypAt = labels(hyp, frames) { mapping[it.speakerId] ?: "unmapped:${it.speakerId}" }
        var speech = 0
        var errors = 0
        for (i in 0 until frames) {
            val r = refAt[i]
            val h = hypAt[i]
            if (r != null) speech++
            if (r != h) errors++ // miss, false alarm or confusion
        }
        return if (speech == 0) 0.0 else errors.toDouble() / speech
    }

    private fun labels(items: List<Utterance>, frames: Int, label: (Utterance) -> String): Array<String?> {
        val out = arrayOfNulls<String>(frames)
        for (u in items) {
            val a = (u.startMs / FRAME_MS).toInt()
            val b = minOf(frames, ((u.endMs + FRAME_MS - 1) / FRAME_MS).toInt())
            val l = label(u)
            for (i in a until b) out[i] = l
        }
        return out
    }

    private fun overlap(a: Utterance, b: Utterance): Long =
        maxOf(0L, minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs))
}
