package dev.sondre.launcher

import kotlin.math.pow

/**
 * "Most used" score: every launch adds 1, and the total halves every [HALF_LIFE_DAYS].
 * So opening an app often counts, and recent opens count more than old ones: an app opened
 * daily last spring has faded by autumn, while one opened a few times this week ranks high.
 *
 * Pure and Android-free so it's unit tested on the JVM.
 */
object Frecency {

    /** How fast old launches fade. Higher = longer memory. */
    const val HALF_LIFE_DAYS = 14.0
    private const val HALF_LIFE_MS = HALF_LIFE_DAYS * 24 * 60 * 60 * 1000

    /** [score] as it was at [at] (epoch ms). */
    data class Usage(val score: Double, val at: Long)

    /** The score at [now], after decay. */
    fun valueAt(usage: Usage, now: Long): Double {
        val elapsed = (now - usage.at).coerceAtLeast(0)
        return usage.score * 0.5.pow(elapsed / HALF_LIFE_MS)
    }

    /** One more launch at [now]. */
    fun bump(usage: Usage?, now: Long): Usage =
        Usage((usage?.let { valueAt(it, now) } ?: 0.0) + 1.0, now)
}
