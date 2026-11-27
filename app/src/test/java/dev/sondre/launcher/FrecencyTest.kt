package dev.sondre.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrecencyTest {

    private val day = 24L * 60 * 60 * 1000
    private val halfLife = (Frecency.HALF_LIFE_DAYS * day).toLong()

    private fun launches(vararg times: Long): Frecency.Usage? =
        times.fold(null as Frecency.Usage?) { u, t -> Frecency.bump(u, t) }

    @Test
    fun `each launch adds one`() {
        val u = launches(0, 0, 0)!!
        assertEquals(3.0, Frecency.valueAt(u, 0), 1e-9)
    }

    @Test
    fun `score halves every half-life`() {
        val u = launches(0)!!
        assertEquals(0.5, Frecency.valueAt(u, halfLife), 1e-9)
        assertEquals(0.25, Frecency.valueAt(u, 2 * halfLife), 1e-9)
    }

    @Test
    fun `many launches still beat a single newer one`() {
        val often = launches(*LongArray(10) { it * day })!! // daily, ten days
        val once = launches(10 * day)!!
        val now = 10 * day
        assertTrue(Frecency.valueAt(often, now) > Frecency.valueAt(once, now))
    }

    @Test
    fun `old heavy use fades below recent regular use`() {
        val spring = launches(*LongArray(60) { it * day })!! // daily for two months
        val autumnStart = 240 * day
        val autumn = launches(*LongArray(4) { autumnStart + it * day })!! // four days in a row
        val now = autumnStart + 4 * day
        assertTrue(Frecency.valueAt(autumn, now) > Frecency.valueAt(spring, now))
    }

    @Test
    fun `a clock going backwards doesn't inflate the score`() {
        val u = launches(10 * day)!!
        assertEquals(1.0, Frecency.valueAt(u, 0), 1e-9)
    }
}
