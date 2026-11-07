package com.hex.stenographer.transcript

import com.hex.stenographer.asr.AsrChunk
import com.hex.stenographer.asr.Recognition
import com.hex.stenographer.asr.TranscriptText
import com.hex.stenographer.diarization.SpeechSpan
import kotlin.math.roundToInt

/** Assign whole recognized words, never pieces of a word, to the diarization timeline. */
internal object WordSpeakers {
    fun assign(chunk: AsrChunk, result: Recognition, spans: List<SpeechSpan>, rate: Int): List<Utterance>? {
        val times = result.tokenStartsSec
        if (result.tokens.isEmpty() || times.size != result.tokens.size ||
            times.any { !it.isFinite() || it < 0 } || times.indices.drop(1).any { times[it] < times[it-1] }) return null
        val text = StringBuilder()
        val indices = mutableListOf<Int>()
        result.tokens.forEachIndexed { i, token ->
            val piece = token.replace('▁', ' ')
            if (piece.contains('<') || piece.contains('>')) return null
            piece.forEach { text.append(it); indices += i }
        }
        fun normalized(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()
        if (normalized(text.toString()) != normalized(result.text)) return null
        val out = mutableListOf<Utterance>()
        for (word in Regex("\\S+").findAll(text)) {
            val first = indices[word.range.first]
            val last = indices[word.range.last]
            val start = (chunk.startSample + (times[first] * rate).roundToInt()).coerceIn(chunk.startSample, chunk.endSample - 1)
            val next = if (last + 1 < times.size) times[last + 1] else (chunk.length.toFloat() / rate)
            val duration = result.tokenDurationsSec.getOrNull(last)?.takeIf { it.isFinite() && it > 0 }
            // CTC has emission times, not durations. A delayed space may follow a long pause;
            // never charge that entire silence to the preceding word when choosing its speaker.
            val finish = minOf(next, times[last] + (duration ?: 0.12f))
            val end = (chunk.startSample + (finish * rate).roundToInt()).coerceIn(start + 1, chunk.endSample)
            val speaker = spans.maxWithOrNull(compareBy<SpeechSpan> {
                maxOf(0, minOf(end, it.endSample) - maxOf(start, it.startSample))
            }.thenBy { -kotlin.math.abs((start.toLong()+end)/2 - (it.startSample.toLong()+it.endSample)/2) }) ?: return null
            val startMs = start.toLong() * 1000 / rate
            val endMs = end.toLong() * 1000 / rate
            if (endMs <= startMs) return null
            val previous = out.lastOrNull()
            if (previous != null && previous.speakerId == speaker.speakerId && startMs - previous.endMs <= 1000) {
                out[out.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, endMs), text = previous.text + " " + word.value)
            } else out += Utterance(speaker.speakerId, startMs, endMs, word.value)
        }
        // Clean only after attribution: repeated words across different speakers are real turns.
        return out.mapNotNull { item -> TranscriptText.clean(item.text)?.let { item.copy(text = it) } }
    }

    /** Union across speaker changes: ASR sees the complete words on both sides. */
    fun regions(spans: List<SpeechSpan>, rate: Int): List<SpeechSpan> {
        val out = mutableListOf<SpeechSpan>()
        for (span in spans.sortedBy { it.startSample }) {
            val previous = out.lastOrNull()
            if (previous != null && span.startSample - previous.endSample <= rate * 3 / 10) {
                out[out.lastIndex] = previous.copy(endSample = maxOf(previous.endSample, span.endSample))
            } else out += span.copy(speaker = 1)
        }
        return out
    }
}
