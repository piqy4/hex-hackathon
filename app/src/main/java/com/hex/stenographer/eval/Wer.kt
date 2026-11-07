package com.hex.stenographer.eval

data class WerResult(val substitutions: Int, val deletions: Int, val insertions: Int, val referenceWords: Int) {
    val errors: Int get() = substitutions + deletions + insertions

    /** (S + D + I) / N; 0 for an empty reference with an empty hypothesis. */
    val wer: Double
        get() = if (referenceWords == 0) (if (errors == 0) 0.0 else 1.0) else errors.toDouble() / referenceWords
}

/**
 * Word error rate with the normalization from the spec, applied identically to both sides:
 * lowercase, `ё` -> `е`, punctuation and symbols become spaces, whitespace collapsed.
 * Numbers are compared as written (`5` and `пять` differ); choose one convention for references.
 */
object Wer {

    fun normalize(text: String): List<String> {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            sb.append(
                when {
                    ch == 'ё' -> 'е'
                    ch.isLetterOrDigit() -> ch
                    else -> ' '
                },
            )
        }
        return sb.split(' ').filter { it.isNotEmpty() }
    }

    fun compute(reference: String, hypothesis: String): WerResult = compute(normalize(reference), normalize(hypothesis))

    fun compute(ref: List<String>, hyp: List<String>): WerResult {
        val n = ref.size
        val m = hyp.size
        // costs[i][j]: edit distance between ref[0, i) and hyp[0, j). The full matrix is kept for the
        // backtrace; transcripts are a few thousand words at most.
        val costs = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) costs[i][0] = i
        for (j in 0..m) costs[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val sub = costs[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                costs[i][j] = minOf(sub, costs[i - 1][j] + 1, costs[i][j - 1] + 1)
            }
        }
        // Backtrace to split the distance into S, D, I.
        var i = n
        var j = m
        var s = 0
        var d = 0
        var ins = 0
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && costs[i][j] == costs[i - 1][j - 1] + (if (ref[i - 1] == hyp[j - 1]) 0 else 1) -> {
                    if (ref[i - 1] != hyp[j - 1]) s++
                    i--
                    j--
                }
                i > 0 && costs[i][j] == costs[i - 1][j] + 1 -> {
                    d++
                    i--
                }
                else -> {
                    ins++
                    j--
                }
            }
        }
        return WerResult(s, d, ins, n)
    }
}
