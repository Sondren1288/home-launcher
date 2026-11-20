package dev.sondre.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSearchTest {

    private val apps = listOf("Calculator", "Calendar", "Camera", "Gmail", "Google Maps", "Maps.me", "Yr", "Ruter")

    private fun search(q: String, boost: (String) -> Int = { 0 }) = AppSearch.filter(apps, q, { it }, boost)

    @Test
    fun `empty query matches everything in original order`() {
        assertEquals(apps, search("  "))
    }

    @Test
    fun `prefix beats word prefix beats contains`() {
        // Camera only matches as a subsequence (c-a-M-er-A), so it comes last.
        assertEquals(listOf("Maps.me", "Google Maps", "Gmail", "Camera"), search("ma"))
    }

    @Test
    fun `initials match`() {
        assertEquals(listOf("Gmail", "Google Maps"), search("gm"))
    }

    @Test
    fun `subsequence match`() {
        assertEquals(listOf("Calculator"), search("clcl"))
    }

    @Test
    fun `case and accents are ignored`() {
        assertEquals(0, AppSearch.score("Åpent", "ap"))
        assertEquals(0, AppSearch.score("Éclair", "ECL"))
    }

    @Test
    fun `no match returns null`() {
        assertNull(AppSearch.score("Camera", "xyz"))
    }

    @Test
    fun `boost breaks ties within the same match quality`() {
        assertEquals(listOf("Calendar", "Calculator", "Camera"), search("ca") { if (it == "Calendar") 10 else 0 })
    }

    @Test
    fun `sections keep favorite order and exclude favorites from frequent`() {
        val counts = mapOf("Yr" to 3, "Gmail" to 9, "Camera" to 1, "Ruter" to 3)
        val s = AppSearch.sections(apps, { it }, listOf("Ruter", "Calendar", "Uninstalled"), emptyList(), counts, frequentLimit = 2)
        assertEquals(listOf("Ruter", "Calendar"), s.favorites)
        assertEquals(listOf("Gmail", "Yr"), s.frequent)
        assertEquals(apps, s.ungrouped)
    }

    @Test
    fun `groups keep their order, members are alphabetical, and are removed from ungrouped`() {
        val groups = listOf(
            AppGroup("travel", "Travel", listOf("Ruter", "Google Maps", "Uninstalled")),
            AppGroup("empty", "Empty", emptyList()),
            AppGroup("photo", "Photo", listOf("Camera", "Ruter")), // Ruter already in Travel
        )
        val s = AppSearch.sections(apps, { it }, emptyList(), groups, emptyMap(), frequentLimit = 5)
        assertEquals(listOf("Travel", "Empty", "Photo"), s.groups.map { it.group.name })
        assertEquals(listOf("Google Maps", "Ruter"), s.groups[0].apps)
        assertEquals(emptyList<String>(), s.groups[1].apps)
        assertEquals(listOf("Camera"), s.groups[2].apps)
        assertEquals(listOf("Calculator", "Calendar", "Gmail", "Maps.me", "Yr"), s.ungrouped)
    }

    @Test
    fun `favorites and frequent still include grouped apps`() {
        val groups = listOf(AppGroup("g", "G", listOf("Gmail", "Yr")))
        val s = AppSearch.sections(apps, { it }, listOf("Yr"), groups, mapOf("Gmail" to 2), frequentLimit = 5)
        assertEquals(listOf("Yr"), s.favorites)
        assertEquals(listOf("Gmail"), s.frequent)
    }

    // children: (owner app, label)
    private val subs = listOf(
        "WhatsApp" to "Dorian Gray", "WhatsApp" to "Anna", "Contacts" to "Dorian Gray",
        "Contacts" to "Dora", "Contacts" to "Isidor", "Chrome" to "New incognito tab",
    )
    private val withSubs = listOf("Chrome", "Contacts", "Dodo", "WhatsApp")

    private fun deep(q: String, boost: (String) -> Int = { 0 }) = AppSearch.searchWithChildren(
        withSubs, subs, q, label = { it }, key = { it },
        childLabel = { it.second }, childOwner = { it.first }, boost = boost,
    ).map { m -> m.item to m.children.map { it.second } }

    @Test
    fun `children appear under their app, and the app is shown even if only a child matches`() {
        assertEquals(
            listOf("Contacts" to listOf("Dorian Gray"), "WhatsApp" to listOf("Dorian Gray")),
            deep("dorian"),
        )
    }

    @Test
    fun `apps matching by name come before apps matching only through children`() {
        assertEquals(
            listOf("Dodo" to emptyList(), "Contacts" to listOf("Dora", "Dorian Gray"), "WhatsApp" to listOf("Dorian Gray")),
            deep("do"),
        )
    }

    @Test
    fun `children only match on prefixes, not inside words`() {
        // "dor" is inside "Isidor" but that's not a prefix, so only Dora and Dorian match.
        assertEquals(listOf("Dora", "Dorian Gray"), deep("dor").first { it.first == "Contacts" }.second)
        assertEquals(listOf("Chrome" to listOf("New incognito tab")), deep("incog"))
    }

    @Test
    fun `boost orders apps that rank equally`() {
        assertEquals(listOf("WhatsApp", "Contacts"), deep("dorian") { if (it == "WhatsApp") 5 else 0 }.map { it.first })
    }

    @Test
    fun `blank query gives no deep results`() {
        assertEquals(emptyList<Any>(), deep(" "))
    }

    @Test
    fun `letters strip accents but keep Norwegian letters, and non-letters go under hash`() {
        assertEquals("E", AppSearch.letterOf("Éclair"))
        assertEquals("A", AppSearch.letterOf(" aimo"))
        assertEquals("Å", AppSearch.letterOf("åpent"))
        assertEquals("Ø", AppSearch.letterOf("Østlandet"))
        assertEquals("#", AppSearch.letterOf("1Password"))
        assertEquals("#", AppSearch.letterOf(""))
    }

    @Test
    fun `a divider goes before each new letter, including the first`() {
        val list = AppSearch.withLetterDividers(listOf("1Password", "Autopay", "Azur Lane", "BankID", "Bolt"), { it })
        val rendered = list.map {
            when (it) {
                is AppSearch.Lettered.Divider -> "-${it.letter}-"
                is AppSearch.Lettered.Item -> it.item
            }
        }
        assertEquals(listOf("-#-", "1Password", "-A-", "Autopay", "Azur Lane", "-B-", "BankID", "Bolt"), rendered)
    }
}
