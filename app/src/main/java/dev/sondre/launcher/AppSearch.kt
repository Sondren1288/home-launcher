package dev.sondre.launcher

import java.text.Normalizer

/**
 * Pure, Android-free search and grouping logic so it can be unit tested on the JVM.
 */
object AppSearch {

    private val combiningMarks = Regex("\\p{Mn}+")
    private val wordSeparators = Regex("[\\s\\-_.:]+")

    /** Lowercase and strip accents so "e" finds "É" and "a" finds "å". */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()

    /**
     * Lower is better; null means no match.
     *  0: label starts with query          ("ma"  -> "Maps")
     *  1: a word starts with query         ("ma"  -> "Google Maps")
     *  2: word initials start with query   ("gm"  -> "Google Maps")
     *  3: label contains query             ("aps" -> "Maps")
     *  4: query is a subsequence of label  ("gml" -> "Gmail")
     */
    fun score(label: String, query: String): Int? {
        val q = normalize(query.trim())
        if (q.isEmpty()) return 0
        val l = normalize(label)
        val words = l.split(wordSeparators).filter { it.isNotEmpty() }
        return when {
            l.startsWith(q) -> 0
            words.any { it.startsWith(q) } -> 1
            words.joinToString("") { it.take(1) }.startsWith(q) -> 2
            l.contains(q) -> 3
            isSubsequence(q, l.replace(wordSeparators, "")) -> 4
            else -> null
        }
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var i = 0
        for (c in haystack) {
            if (i < needle.length && needle[i] == c) i++
        }
        return i == needle.length
    }

    /**
     * Filter [items] by [query]. Ordered by match quality, then by [boost] (higher first,
     * e.g. launch count), then by original order (the input is expected to be alphabetical).
     */
    fun <T> filter(
        items: List<T>,
        query: String,
        label: (T) -> String,
        boost: (T) -> Int = { 0 },
    ): List<T> =
        items.mapNotNull { item -> score(label(item), query)?.let { s -> Triple(item, s, boost(item)) } }
            .sortedWith(compareBy<Triple<T, Int, Int>> { it.second }.thenByDescending { it.third })
            .map { it.first }

    data class Sections<T>(val favorites: List<T>, val frequent: List<T>, val all: List<T>)

    /**
     * @param favoriteKeys favorites in user-defined order
     * @param counts launch count per key
     */
    fun <T> sections(
        all: List<T>,
        key: (T) -> String,
        favoriteKeys: List<String>,
        counts: Map<String, Int>,
        frequentLimit: Int,
    ): Sections<T> {
        val byKey = all.associateBy(key)
        val favorites = favoriteKeys.mapNotNull { byKey[it] }
        val favSet = favoriteKeys.toSet()
        val frequent = all
            .filter { key(it) !in favSet && (counts[key(it)] ?: 0) > 0 }
            .sortedByDescending { counts[key(it)] ?: 0 } // stable: ties stay alphabetical
            .take(frequentLimit)
        return Sections(favorites, frequent, all)
    }
}
