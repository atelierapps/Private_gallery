package com.atelierapps.vault.filter

/**
 * Search that forgives a typo.
 *
 * It used to be one substring test: the whole query had to appear, exactly,
 * in a name, tag, note or source. "beach sunset" missed an item tagged both,
 * because the two words were in different places, and "beahc" missed
 * everything. Now each word of the query is matched on its own, anywhere in the
 * item, and a word that matches nothing exactly may still match a word that is
 * one or two letters off.
 *
 * Results that match exactly are ranked ahead of near misses (see [score]), so
 * a typo-tolerant search never buries the thing you spelled right.
 *
 * Plain Kotlin with no Android in it, so it can be unit-tested on the JVM.
 */
object Search {

    /** Nothing matched. */
    const val NONE = 0

    /** Every word matched, at least one only as a near miss. */
    const val NEAR = 1

    /** Every word appears as typed. */
    const val EXACT = 2

    /**
     * How well [fields] (any case) answer [query]. A blank query matches
     * everything exactly.
     */
    fun score(query: String, fields: List<String>): Int {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return EXACT
        val hay = fields.map { it.lowercase() }
        // The whole phrase as typed, the old behaviour: still exact.
        if (hay.any { it.contains(query.trim().lowercase()) }) return EXACT
        var near = false
        var words: List<String>? = null
        for (t in tokens) {
            if (hay.any { it.contains(t) }) continue
            val allowed = allowedEdits(t)
            if (allowed == 0) return NONE
            val w = words ?: hay.flatMap { splitWords(it) }.also { words = it }
            if (w.none { closeEnough(t, it, allowed) }) return NONE
            near = true
        }
        return if (near) NEAR else EXACT
    }

    /** Lowercased words of the query; a leading '#' is dropped, so "#beach" finds the tag. */
    fun tokens(query: String): List<String> =
        query.lowercase().split(WHITESPACE)
            .map { it.trimStart('#') }
            .filter { it.isNotEmpty() }

    /**
     * Short words must be exact: at three letters one edit turns "cat" into
     * "car", "hat" and "cut", and a search that finds all of those finds
     * nothing in particular.
     */
    private fun allowedEdits(token: String): Int = when {
        token.length < 4 -> 0
        token.length < 8 -> 1
        else -> 2
    }

    /**
     * Against the whole word, and against its start: a word still being typed
     * with a slip in it ("beautif" for "beautiful", "beuatiful") matches too.
     */
    private fun closeEnough(token: String, word: String, allowed: Int): Boolean {
        if (kotlin.math.abs(word.length - token.length) <= allowed &&
            distance(token, word, allowed) <= allowed
        ) {
            return true
        }
        if (word.length > token.length) {
            return distance(token, word.substring(0, token.length), allowed) <= allowed
        }
        return false
    }

    private fun splitWords(text: String): List<String> =
        text.split(NON_WORD).filter { it.length >= 2 }

    /**
     * Edit distance counting a swapped pair of neighbours as one edit ("beahc"
     * for "beach"), since that is the commonest slip on a phone keyboard and
     * plain Levenshtein charges it as two. Anything over [limit] comes back
     * as limit + 1, which is all the caller needs to know.
     */
    internal fun distance(a: String, b: String, limit: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var before = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var d = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d = minOf(d, before[j - 2] + 1)
                }
                cur[j] = d
            }
            val t = before; before = prev; prev = cur; cur = t
        }
        return minOf(prev[b.length], limit + 1)
    }

    private val WHITESPACE = Regex("\\s+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
