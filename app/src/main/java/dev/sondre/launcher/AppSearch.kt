package dev.sondre.launcher

import java.text.Normalizer

/** A user-made folder. An app belongs to at most one group. */
data class AppGroup(val id: String, val name: String, val appKeys: List<String>)

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

    /** The better [score] of [label] and [altLabel] (e.g. an app's own name when the user renamed it). */
    fun bestScore(label: String, altLabel: String?, query: String): Int? {
        val a = score(label, query)
        val b = altLabel?.let { score(it, query) }
        return if (a == null) b else if (b == null) a else minOf(a, b)
    }

    /**
     * Filter [items] by [query]. Ordered by match quality, then by [boost] (higher first,
     * e.g. launch score), then by original order (the input is expected to be alphabetical).
     * An item also matches on its [altLabel], if it has one.
     */
    fun <T> filter(
        items: List<T>,
        query: String,
        label: (T) -> String,
        boost: (T) -> Double = { 0.0 },
        altLabel: (T) -> String? = { null },
    ): List<T> =
        items.mapNotNull { item -> bestScore(label(item), altLabel(item), query)?.let { s -> Triple(item, s, boost(item)) } }
            .sortedWith(compareBy<Triple<T, Int, Double>> { it.second }.thenByDescending { it.third })
            .map { it.first }

    /** An app in the search results, with matching sub-items (chats, contacts…) beneath it. */
    data class Match<T, C>(val item: T, val children: List<C>, val matchedItself: Boolean)

    /**
     * Search apps and their sub-items together. An app is included if its own label matches
     * or any of its children match; it ranks by the better of the two. On equal rank, apps
     * matching by their own name come first, then higher [boost], then input order.
     *
     * Children only match on prefix / word prefix / initials ([childMaxScore]) so a large
     * contact list doesn't flood the results with loose matches. An app also matches on its
     * [altLabel], if it has one.
     */
    fun <T, C> searchWithChildren(
        items: List<T>,
        children: List<C>,
        query: String,
        label: (T) -> String,
        key: (T) -> String,
        childLabel: (C) -> String,
        childOwner: (C) -> String,
        boost: (T) -> Double = { 0.0 },
        childMaxScore: Int = 2,
        childLimit: Int = 5,
        altLabel: (T) -> String? = { null },
        /** Extra words a child matches on besides its label (e.g. "wlan" for Wi-Fi). */
        childKeywords: (C) -> List<String> = { emptyList() },
    ): List<Match<T, C>> {
        if (query.isBlank()) return emptyList()
        val kids: Map<String, List<Pair<C, Int>>> = children
            .mapNotNull { c ->
                val best = (listOf(childLabel(c)) + childKeywords(c)).mapNotNull { score(it, query) }.minOrNull()
                best?.takeIf { it <= childMaxScore }?.let { c to it }
            }
            .groupBy { childOwner(it.first) }
            .mapValues { (_, v) ->
                v.sortedWith(compareBy<Pair<C, Int>> { it.second }.thenBy { normalize(childLabel(it.first)) })
            }

        data class Ranked(val match: Match<T, C>, val rank: Int, val boost: Double)
        return items.mapNotNull { item ->
            val own = bestScore(label(item), altLabel(item), query)
            val mine = kids[key(item)].orEmpty()
            if (own == null && mine.isEmpty()) return@mapNotNull null
            val rank = minOf(own ?: Int.MAX_VALUE, mine.firstOrNull()?.second ?: Int.MAX_VALUE)
            Ranked(Match(item, mine.take(childLimit).map { it.first }, own != null), rank, boost(item))
        }
            .sortedWith(
                compareBy<Ranked> { it.rank }
                    .thenBy { if (it.match.matchedItself) 0 else 1 }
                    .thenByDescending { it.boost }
            )
            .map { it.match }
    }

    data class GroupSection<T>(val group: AppGroup, val apps: List<T>)

    private val nordicLetters = setOf('Æ', 'Ø', 'Å')

    /**
     * The letter an app is filed under in the A–Z list: the first character, uppercased, with
     * accents stripped (É → E) except for Æ/Ø/Å, which sort as their own letters. Anything that
     * isn't a letter goes under "#".
     */
    fun letterOf(label: String): String {
        val c = label.trim().firstOrNull()?.uppercaseChar() ?: return "#"
        if (!c.isLetter()) return "#"
        if (c in nordicLetters) return c.toString()
        return normalize(c.toString()).uppercase().ifEmpty { c.toString() }
    }

    /** An entry in a list with letter dividers: either a divider or an item. */
    sealed interface Lettered<out T> {
        data class Divider(val letter: String) : Lettered<Nothing>
        data class Item<T>(val item: T) : Lettered<T>
    }

    /** Insert a divider before every run of items filed under a new letter (including the first). */
    fun <T> withLetterDividers(items: List<T>, label: (T) -> String): List<Lettered<T>> {
        val out = ArrayList<Lettered<T>>(items.size + 30)
        var last: String? = null
        for (item in items) {
            val letter = letterOf(label(item))
            if (letter != last) {
                out += Lettered.Divider(letter)
                last = letter
            }
            out += Lettered.Item(item)
        }
        return out
    }

    data class Sections<T>(
        val frequent: List<T>,
        val groups: List<GroupSection<T>>,
    )

    /**
     * Folders and Most used. "All apps" is simply [all]: apps in a group stay in it too.
     * @param all every app, alphabetical; group members keep this order
     * @param groups groups in user-defined order; keys of uninstalled apps are skipped
     * @param scores launch score per key (see [Frecency]); higher is used more
     */
    fun <T> sections(
        all: List<T>,
        key: (T) -> String,
        groups: List<AppGroup>,
        scores: Map<String, Double>,
        frequentLimit: Int,
    ): Sections<T> {
        val frequent = all
            .filter { (scores[key(it)] ?: 0.0) > 0.0 }
            .sortedByDescending { scores[key(it)] ?: 0.0 } // stable: ties stay alphabetical
            .take(frequentLimit)

        // First group wins if a key somehow ended up in two groups.
        val groupOf = HashMap<String, String>()
        for (g in groups) for (k in g.appKeys) groupOf.putIfAbsent(k, g.id)
        val members = all.filter { key(it) in groupOf }.groupBy { groupOf.getValue(key(it)) }

        return Sections(
            frequent = frequent,
            groups = groups.map { GroupSection(it, members[it.id].orEmpty()) },
        )
    }
}
