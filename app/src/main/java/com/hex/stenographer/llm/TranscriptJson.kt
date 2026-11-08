package com.hex.stenographer.llm

import com.hex.stenographer.transcript.Utterance

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Streaming parser rejects duplicate keys, non-JSON syntax, wrong types and trailing data. */
object TranscriptJson {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    fun encode(items: List<Utterance>, durationMs: Long): String {
        validate(items, durationMs)
        return gson.toJson(mapOf("transcript" to items.map {
            linkedMapOf("speaker_id" to it.speakerId, "start_ms" to it.startMs,
                "end_ms" to it.endMs, "text" to it.text)
        }))
    }

    fun validate(items: List<Utterance>, durationMs: Long) {
        require(durationMs >= 0) { "Negative duration" }
        require(items.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }) { "Unsorted transcript" }
        items.forEach {
            require(it.speakerId.matches(Regex("speaker_[1-9][0-9]*"))) { "Invalid speaker ID" }
            require(it.startMs >= 0 && it.startMs < it.endMs && it.endMs <= durationMs) { "Invalid interval" }
            require(it.text.isNotBlank()) { "Empty text" }
        }
    }

    fun decode(json: String, durationMs: Long): List<Utterance> = reader(json).use { r ->
        r.beginObject()
        require(r.hasNext() && r.nextName() == "transcript") { "Expected transcript" }
        val items = mutableListOf<Utterance>()
        r.beginArray()
        while (r.hasNext()) {
            val keys = mutableSetOf<String>()
            var speaker: String? = null
            var start: Long? = null
            var end: Long? = null
            var text: String? = null
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                require(keys.add(key)) { "Duplicate field" }
                when (key) {
                    "speaker_id" -> speaker = string(r)
                    "start_ms" -> start = integer(r)
                    "end_ms" -> end = integer(r)
                    "text" -> text = string(r)
                    else -> error("Unexpected field: $key")
                }
            }
            r.endObject()
            items += Utterance(requireNotNull(speaker), requireNotNull(start), requireNotNull(end), requireNotNull(text))
        }
        r.endArray()
        require(!r.hasNext()) { "Unexpected root field" }
        r.endObject()
        require(r.peek() == JsonToken.END_DOCUMENT) { "Trailing data" }
        validate(items, durationMs)
        items
    }

    internal fun reader(json: String) = JsonReader(StringReader(json)).apply { strictness = Strictness.STRICT }
    internal fun string(r: JsonReader): String {
        require(r.peek() == JsonToken.STRING) { "Expected string" }
        return r.nextString()
    }
    private fun integer(r: JsonReader): Long {
        require(r.peek() == JsonToken.NUMBER) { "Expected integer" }
        val value = r.nextString()
        require(value.matches(Regex("0|[1-9][0-9]*"))) { "Expected nonnegative integer" }
        return value.toLong()
    }
}
