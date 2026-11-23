package dev.sondre.launcher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Groups and launch counts, persisted in SharedPreferences.
 * We count launches ourselves instead of using UsageStatsManager, which would need
 * the user to grant a special permission in Settings.
 */
class UsageStore(context: Context) {

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val countPrefs = context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE)

    private val _groups = MutableStateFlow(decodeGroups(prefs.getString(KEY_GROUPS, null)))
    /** Groups in user-defined order. */
    val groups: StateFlow<List<AppGroup>> = _groups.asStateFlow()

    private val _counts = MutableStateFlow(
        countPrefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
    )
    val launchCounts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    fun recordLaunch(key: String) {
        val next = (_counts.value[key] ?: 0) + 1
        _counts.value = _counts.value + (key to next)
        countPrefs.edit().putInt(key, next).apply()
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
    }
}
