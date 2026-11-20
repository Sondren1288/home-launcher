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
        val s = AppSearch.sections(apps, { it }, favoriteKeys = listOf("Ruter", "Calendar", "Uninstalled"), counts, frequentLimit = 2)
        assertEquals(listOf("Ruter", "Calendar"), s.favorites)
        assertEquals(listOf("Gmail", "Yr"), s.frequent)
        assertEquals(apps, s.all)
    }
}
