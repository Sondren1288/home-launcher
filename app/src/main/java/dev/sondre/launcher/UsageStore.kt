package dev.sondre.launcher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Favorites and launch counts, persisted in SharedPreferences.
 * We count launches ourselves instead of using UsageStatsManager, which would need
 * the user to grant a special permission in Settings.
 */
class UsageStore(context: Context) {

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val countPrefs = context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE)

    private val _favorites = MutableStateFlow(
        prefs.getString(KEY_FAVORITES, "").orEmpty().split('\n').filter { it.isNotEmpty() }
    )
    /** Favorite app keys, in the order they were added. */
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private val _counts = MutableStateFlow(
        countPrefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
    )
    val launchCounts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    fun recordLaunch(key: String) {
        val next = (_counts.value[key] ?: 0) + 1
        _counts.value = _counts.value + (key to next)
        countPrefs.edit().putInt(key, next).apply()
    }

    fun toggleFavorite(key: String) {
        val current = _favorites.value
        val next = if (key in current) current - key else current + key
        _favorites.value = next
        prefs.edit().putString(KEY_FAVORITES, next.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
    }
}
