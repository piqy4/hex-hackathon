package com.hex.stenographer.asr

/**
 * Cleans raw ASR output (any model; mostly needed for Whisper): drops non-speech tags (`[Музыка]`, `♪`), collapses decoding loops
 * ("да да да да да" -> "да") and removes the well-known subtitle-credit hallucinations that Whisper
 * emits on silence and noise, especially in Russian. Never rewrites real words.
 */
object TranscriptText {

    private val NON_SPEECH = Regex("""\[[^\]]*]|♪+|\*[^*]*\*""")
    private val WHITESPACE = Regex("""\s+""")
    private val SENTENCE = Regex("""(?<=[.!?…])\s+""")

    /** Lowercased fragments; a sentence containing one of them is a hallucination, not speech. */
    private val HALLUCINATIONS = listOf(
        "субтитры сделал", "субтитры создавал", "субтитры подготовил", "субтитры делал",
        "редактор субтитров", "корректор а.", "продолжение следует", "спасибо за просмотр",
        "подписывайтесь на канал", "ставьте лайк", "dimatorzok", "amara.org",
        "thank you for watching", "thanks for watching", "subtitles by",
    )

    /** Longest phrase (in words) checked for consecutive repetition. */
    private const val MAX_LOOP_PHRASE = 6

    /** A phrase must repeat more than this many times in a row to count as a decoding loop. */
    private const val MAX_REPEATS = 2

    /** @return cleaned text, or `null` when nothing that looks like speech is left. */
    fun clean(raw: String): String? {
        var text = NON_SPEECH.replace(raw, " ").replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return null

        text = text.split(SENTENCE)
            .filterNot { sentence -> sentence.lowercase().let { s -> HALLUCINATIONS.any { it in s } } }
            .joinToString(" ")
        if (text.isEmpty()) return null

        text = collapseLoops(text.split(' ')).joinToString(" ")
        return text.takeIf { s -> s.any { it.isLetterOrDigit() } }
    }

    /** Keeps one copy of any 1..[MAX_LOOP_PHRASE]-word phrase repeated more than [MAX_REPEATS] times. */
    private fun collapseLoops(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var collapsed = false
            for (n in 1..MAX_LOOP_PHRASE) {
                if (i + n * (MAX_REPEATS + 1) > words.size) break
                var repeats = 1
                while (i + (repeats + 1) * n <= words.size && samePhrase(words, i, i + repeats * n, n)) repeats++
                if (repeats > MAX_REPEATS) {
                    for (k in 0 until n) out += words[i + k]
                    i += repeats * n
                    collapsed = true
                    break
                }
            }
            if (!collapsed) out += words[i++]
        }
        return out
    }

    private fun samePhrase(w: List<String>, a: Int, b: Int, n: Int): Boolean {
        for (k in 0 until n) if (!w[a + k].normalizedEquals(w[b + k])) return false
        return true
    }

    private fun String.normalizedEquals(other: String): Boolean =
        trimEnd(',', '.', '!', '?', '…').equals(other.trimEnd(',', '.', '!', '?', '…'), ignoreCase = true)
}
