package com.example.hex.llm

import org.junit.Assert.*
import org.junit.Test

class SafeTextPolisherTest {
    private val item = Utterance("speaker_1", 100, 900, "привет как дела")
    private val input = mapOf("u_0" to item.text)
    private fun rejected(json: String) {
        assertThrows(Exception::class.java) { SafeTextPolisher.validateReply(json, input) }
    }
    private fun session(reply: () -> String, onClose: () -> Unit = {}) = object : CompletionSession {
        override fun complete(system: String, inputJson: String, maxTokens: Int, timeoutMs: Long) = reply()
        override fun close() = onClose()
    }
    @Test fun punctuationAndCaseAreAccepted() {
        assertEquals("Привет, как дела?", SafeTextPolisher.validateReply("""[{"id":"u_0","text":"Привет, как дела?"}]""", input)["u_0"])
    }
    @Test fun changedWordsAreRejected() {
        listOf("здравствуйте как дела", "привет друг как дела", "привет дела", "как привет дела").forEach {
            rejected("""[{"id":"u_0","text":"$it"}]""")
        }
    }
    @Test fun duplicateMissingUnknownIdsAreRejected() {
        rejected("[]")
        rejected("""[{"id":"x","text":"привет как дела"}]""")
        rejected("""[{"id":"u_0","text":"привет как дела"},{"id":"u_0","text":"привет как дела"}]""")
    }
    @Test fun malformedAndNonStrictJsonIsRejected() {
        listOf("```json\n[]\n```", "[] trailing", "[{'id':'u_0','text':'привет как дела'}]",
            """[{"id":"u_0","id":"u_0","text":"привет как дела"}]""",
            """[{"id":"u_0","text":42}]""", """[{"id":"u_0","text":"привет как дела","extra":1}]""").forEach(::rejected)
    }
    @Test fun invisibleDirectionControlsAreRejected() {
        rejected("""[{"id":"u_0","text":"привет как дела\u202e"}]""")
    }
    @Test fun tokenBoundariesNumbersYoAndSymbolsAreProtected() {
        listOf("не верно" to "неверно", "15 рублей" to "пятнадцать рублей", "ёлка" to "елка", "2 + 3" to "2 3").forEach { (a,b) ->
            assertNotEquals(SafeTextPolisher.lexicalTokens(a), SafeTextPolisher.lexicalTokens(b))
        }
    }
    @Test fun responseOrderDoesNotAffectMapping() {
        val reply = """[{"id":"b","text":"Пока!"},{"id":"a","text":"Привет!"}]"""
        assertEquals("Привет!", SafeTextPolisher.validateReply(reply, mapOf("a" to "привет", "b" to "пока"))["a"])
    }
    @Test fun metadataIsPreservedAndSessionCloses() {
        var closed = false
        val s = session({ """[{"id":"u_0","text":"Привет, как дела?"}]""" }, { closed = true })
        assertEquals(listOf(item.copy(text = "Привет, как дела?")), SafeTextPolisher({s}).polish(listOf(item)))
        assertTrue(closed)
    }
    @Test fun failedBatchFallsBackAtomically() {
        val events = mutableListOf<PolishEvent>()
        val s = session({ """[{"id":"u_0","text":"Привет, как дела?"},{"id":"u_1","text":"подмена"}]""" })
        val items = listOf(item, item.copy(speakerId = "speaker_2", startMs = 1000, endMs = 2000))
        assertEquals(items, SafeTextPolisher({s}, {events += it}, batchSize = 2).polish(items))
        assertTrue(events.single().status.startsWith("fallback"))
    }
    @Test fun timeoutDiscardsEvenValidReply() {
        var clock = 0L
        val s = session({ clock += 31_000_000_000L; """[{"id":"u_0","text":"Привет, как дела?"}]""" })
        assertEquals(listOf(item), SafeTextPolisher({s}, nanoTime = {clock}).polish(listOf(item)))
    }
    @Test fun emptyAndOversizedInputNeverLoadModel() {
        val polisher = SafeTextPolisher({ error("Must not load") })
        assertEquals(emptyList<Utterance>(), polisher.polish(emptyList()))
        val big = item.copy(text = "я".repeat(601))
        assertEquals(listOf(big), polisher.polish(listOf(big)))
    }
    @Test fun modelFailureRetainsOriginal() {
        assertEquals(listOf(item), SafeTextPolisher({ throw IllegalStateException("No model") }).polish(listOf(item)))
    }
    @Test fun jsonRoundTripAndSilence() {
        assertEquals(listOf(item), TranscriptJson.decode(TranscriptJson.encode(listOf(item), 1000), 1000))
        assertEquals(emptyList<Utterance>(), TranscriptJson.decode("{\"transcript\":[]}", 0))
    }
    @Test fun exportRejectsInvalidTimesAndOrder() {
        listOf(item.copy(startMs = -1), item.copy(endMs = 100), item.copy(endMs = 1001)).forEach {
            assertThrows(Exception::class.java) { TranscriptJson.encode(listOf(it), 1000) }
        }
        assertThrows(Exception::class.java) { TranscriptJson.encode(listOf(item.copy(startMs = 200), item), 1000) }
    }
    @Test fun exportRejectsWrongTypesAndExtraFields() {
        val json = TranscriptJson.encode(listOf(item), 1000)
        listOf(json.replace("100,", "100.0,"), json.replace("100,", "\"100\","), json.replace("\"text\":", "\"debug\":0,\"text\":")).forEach {
            assertThrows(Exception::class.java) { TranscriptJson.decode(it, 1000) }
        }
    }
}
