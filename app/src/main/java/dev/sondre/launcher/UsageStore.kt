package dev.sondre.launcher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Groups, launch counts/scores, hidden apps and custom names, persisted in SharedPreferences.
 * Keys are [AppEntry.key]s.
 * We count launches ourselves instead of using UsageStatsManager, which would need
 * the user to grant a special permission in Settings.
 */
class UsageStore(context: Context) {

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val countPrefs = context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE)
    /** "score@epochMs" per app; see [Frecency]. */
    private val scorePrefs = context.getSharedPreferences("launch_scores", Context.MODE_PRIVATE)

    private val _groups = MutableStateFlow(decodeGroups(prefs.getString(KEY_GROUPS, null)))
    /** Groups in user-defined order. */
    val groups: StateFlow<List<AppGroup>> = _groups.asStateFlow()

    private val _counts = MutableStateFlow(
        countPrefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
    )
    /** Lifetime launch counts. Kept as a record; ranking uses [launchScores]. */
    val launchCounts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    private val _scores = MutableStateFlow(loadScores())
    /** Launch scores, decayed with [Frecency] when read. */
    val launchScores: StateFlow<Map<String, Frecency.Usage>> = _scores.asStateFlow()

    private val _hidden = MutableStateFlow(prefs.getStringSet(KEY_HIDDEN, null).orEmpty().toSet())
    /** Apps hidden from the lists. They're still found by search. */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _labels = MutableStateFlow(decodeLabels(prefs.getString(KEY_LABELS, null)))
    /** Names the user gave apps, by key. */
    val customLabels: StateFlow<Map<String, String>> = _labels.asStateFlow()

    fun recordLaunch(key: String, now: Long = System.currentTimeMillis()) {
        val next = (_counts.value[key] ?: 0) + 1
        _counts.value = _counts.value + (key to next)
        countPrefs.edit().putInt(key, next).apply()

        val usage = Frecency.bump(_scores.value[key], now)
        _scores.value = _scores.value + (key to usage)
        scorePrefs.edit().putString(key, "${usage.score}@${usage.at}").apply()
    }

    /**
     * Scores from prefs. On first run after the upgrade there are none yet, so seed them from
     * the lifetime counts as if those launches happened now: the old ranking carries over and
     * then fades like any other history.
     */
    private fun loadScores(): Map<String, Frecency.Usage> {
        val stored = scorePrefs.all.mapNotNull { (k, v) ->
            val parts = (v as? String)?.split('@') ?: return@mapNotNull null
            val score = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            k to Frecency.Usage(score, at)
        }.toMap()
        if (stored.isNotEmpty() || _counts.value.isEmpty()) return stored
        val now = System.currentTimeMillis()
        val seeded = _counts.value.mapValues { (_, n) -> Frecency.Usage(n.toDouble(), now) }
        scorePrefs.edit().apply { seeded.forEach { (k, u) -> putString(k, "${u.score}@${u.at}") } }.apply()
        return seeded
    }

    fun setHidden(key: String, hidden: Boolean) {
        val next = if (hidden) _hidden.value + key else _hidden.value - key
        _hidden.value = next
        // Write a copy: SharedPreferences must not be handed a set it may later see mutated.
        prefs.edit().putStringSet(KEY_HIDDEN, HashSet(next)).apply()
    }

    /** Gives the app a custom name, or restores its own name when [label] is null or blank. */
    fun setCustomLabel(key: String, label: String?) {
        val name = label?.trim().orEmpty()
        val next = if (name.isEmpty()) _labels.value - key else _labels.value + (key to name)
        _labels.value = next
        prefs.edit().putString(KEY_LABELS, JSONObject(next as Map<*, *>).toString()).apply()
    }

    private fun decodeLabels(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        val o = JSONObject(json)
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    /** Set once the user declines contact search, so we stop offering it. */
    var contactsPromptDismissed: Boolean
        get() = prefs.getBoolean(KEY_CONTACTS_PROMPT_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTACTS_PROMPT_DISMISSED, value).apply()

    /** Creates a group at the end of the list and returns its id. */
    fun createGroup(name: String): String {
        val id = UUID.randomUUID().toString()
        saveGroups(_groups.value + AppGroup(id, name.trim(), emptyList()))
        return id
    }

    fun renameGroup(id: String, name: String) =
        saveGroups(_groups.value.map { if (it.id == id) it.copy(name = name.trim()) else it })

    /** Deletes the group; its apps are untouched (they're still in All apps). */
    fun deleteGroup(id: String) = saveGroups(_groups.value.filterNot { it.id == id })

    fun moveGroup(id: String, delta: Int) {
        val list = _groups.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        saveGroups(list)
    }

    /** Moves the app into [groupId], or out of all groups when null. */
    fun setAppGroup(appKey: String, groupId: String?) =
        saveGroups(_groups.value.map { g ->
            val without = g.appKeys - appKey
            g.copy(appKeys = if (g.id == groupId) without + appKey else without)
        })

    private fun saveGroups(groups: List<AppGroup>) {
        _groups.value = groups
        prefs.edit().putString(KEY_GROUPS, encodeGroups(groups)).apply()
    }

    private fun encodeGroups(groups: List<AppGroup>): String =
        JSONArray(groups.map { g ->
            JSONObject().put("id", g.id).put("name", g.name).put("apps", JSONArray(g.appKeys))
        }).toString()

    private fun decodeGroups(json: String?): List<AppGroup> {
        if (json.isNullOrEmpty()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val apps = o.getJSONArray("apps")
            AppGroup(o.getString("id"), o.getString("name"), (0 until apps.length()).map { apps.getString(it) })
        }
    }

    private companion object {
        const val KEY_GROUPS = "groups"
        const val KEY_CONTACTS_PROMPT_DISMISSED = "contacts_prompt_dismissed"
        const val KEY_HIDDEN = "hidden_apps"
        const val KEY_LABELS = "custom_labels"
    }
}
