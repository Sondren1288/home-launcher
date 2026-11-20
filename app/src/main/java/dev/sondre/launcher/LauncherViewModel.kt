package dev.sondre.launcher

import android.app.Application
import android.content.ActivityNotFoundException
import android.database.ContentObserver
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ids for collapsible sections. */
object SectionId {
    const val FAVORITES = "fav"
    const val FREQUENT = "freq"
    const val OTHER = "other"
    fun group(id: String) = "group:$id"
}

typealias SearchResult = AppSearch.Match<AppEntry, SubEntry>

data class UiState(
    val query: String = "",
    val favorites: List<AppEntry> = emptyList(),
    val frequent: List<AppEntry> = emptyList(),
    val groups: List<AppSearch.GroupSection<AppEntry>> = emptyList(),
    val ungrouped: List<AppEntry> = emptyList(),
    val results: List<SearchResult> = emptyList(),
    val favoriteKeys: Set<String> = emptySet(),
    /** app key -> group it belongs to */
    val groupOfApp: Map<String, AppGroup> = emptyMap(),
    val expanded: Set<String> = emptySet(),
) {
    val isSearching get() = query.isNotBlank()
}

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    val repository = AppRepository(app)
    private val usage = UsageStore(app)
    private val subSource = SubItemSource(app)

    private val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    private val shortcuts = MutableStateFlow<List<RawSubItem>>(emptyList())
    private val contacts = MutableStateFlow<List<RawSubItem>>(emptyList())

    /**
     * The search text lives in Compose snapshot state and the text field reads it directly.
     * Routing it through the async [state] flow made the field lag a frame behind, which
     * put the cursor in the wrong place while typing.
     */
    var query by mutableStateOf("")
        private set

    /** Every section starts collapsed. Not persisted. */
    private val expanded = MutableStateFlow<Set<String>>(emptySet())

    private val _resetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when Home is pressed while the launcher is already showing. */
    val resetEvents = _resetEvents.asSharedFlow()

    private val _showContactsPrompt = MutableStateFlow(false)
    /** Whether to offer "also search contacts" (permission not granted and not declined). */
    val showContactsPrompt: StateFlow<Boolean> = _showContactsPrompt.asStateFlow()

    private data class UserData(
        val favorites: List<String>,
        val groups: List<AppGroup>,
        val counts: Map<String, Int>,
    )

    private val userData = combine(usage.favorites, usage.groups, usage.launchCounts, ::UserData)

    /** Shortcuts and contacts, attached to the app they belong to. */
    private val subItems = combine(apps, shortcuts, contacts) { apps, sc, ct -> attachToApps(sc + ct, apps) }

    val state: StateFlow<UiState> =
        combine(apps, snapshotFlow { query }, userData, expanded, subItems) { apps, query, user, expanded, subs ->
            val s = AppSearch.sections(apps, { it.key }, user.favorites, user.groups, user.counts, FREQUENT_LIMIT)
            UiState(
                query = query,
                favorites = s.favorites,
                frequent = s.frequent,
                groups = s.groups,
                ungrouped = s.ungrouped,
                results = search(apps, subs, query, user.counts),
                favoriteKeys = user.favorites.toSet(),
                // reversed() so the first group wins on duplicates, matching AppSearch.sections
                groupOfApp = user.groups.reversed().flatMap { g -> g.appKeys.map { it to g } }.toMap(),
                expanded = expanded,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private fun search(apps: List<AppEntry>, subs: List<SubEntry>, query: String, counts: Map<String, Int>) =
        AppSearch.searchWithChildren(
            items = apps, children = subs, query = query,
            label = { it.label }, key = { it.key },
            childLabel = { it.label }, childOwner = { it.ownerKey },
            boost = { counts[it.key] ?: 0 },
        )

    private val callback = repository.registerCallback(
        onChange = { reload(); reloadShortcuts() },
        // Pinned shortcuts (web apps) are listed as apps, so reload those too.
        onShortcutsChanged = { reload(); reloadShortcuts() },
    )

    private var contactsReloadJob: Job? = null
    private var contactsObserver: ContentObserver? = null

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            apps.value = withContext(Dispatchers.IO) { repository.loadApps() }
        }
    }

    private fun reloadShortcuts() {
        viewModelScope.launch {
            shortcuts.value = withContext(Dispatchers.IO) { subSource.loadShortcuts() }
        }
    }

    private fun reloadContacts() {
        viewModelScope.launch {
            contacts.value = withContext(Dispatchers.IO) { subSource.loadContacts() }
        }
    }

    /** Called from Activity.onStart: permissions or the default-launcher role may have changed. */
    fun onStart() {
        reload() // pinned shortcuts only become visible once we're the default launcher
        reloadShortcuts()
        val granted = subSource.hasContactsPermission()
        _showContactsPrompt.value = !granted && !usage.contactsPromptDismissed
        if (granted) {
            reloadContacts()
            observeContacts()
        } else {
            contacts.value = emptyList()
        }
    }

    fun onContactsPermissionResult(granted: Boolean) {
        if (!granted) usage.contactsPromptDismissed = true
        onStart()
    }

    fun dismissContactsPrompt() {
        usage.contactsPromptDismissed = true
        _showContactsPrompt.value = false
    }

    private fun observeContacts() {
        if (contactsObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // Sync adapters fire bursts of changes; reload once things settle.
                contactsReloadJob?.cancel()
                contactsReloadJob = viewModelScope.launch {
                    delay(1500)
                    reloadContacts()
                }
            }
        }
        runCatching {
            getApplication<Application>().contentResolver
                .registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
            contactsObserver = observer
        }
    }

    fun updateQuery(q: String) {
        query = q
    }

    fun toggleSection(id: String) {
        expanded.value = expanded.value.let { if (id in it) it - id else it + id }
    }

    /** Swipe up on the home screen: open Favorites and All apps. */
    fun expandHome() {
        expanded.value = expanded.value + SectionId.FAVORITES + SectionId.OTHER
    }

    /** Back: close whatever is open. Returns false if nothing was open. */
    fun collapseAll(): Boolean {
        if (expanded.value.isEmpty()) return false
        expanded.value = emptySet()
        return true
    }

    fun launch(app: AppEntry, sourceBounds: Rect? = null) {
        try {
            repository.launch(app, sourceBounds)
            usage.recordLaunch(app.key)
            query = ""
        } catch (e: ActivityNotFoundException) {
            toast("Couldn't open ${app.label}")
            reload()
        } catch (e: SecurityException) {
            toast("Couldn't open ${app.label}")
        } catch (e: IllegalStateException) { // pinned shortcut gone / user locked
            toast("Couldn't open ${app.label}")
            reload()
        }
    }

    fun removeShortcut(app: AppEntry) {
        runCatching { repository.removeShortcut(app) }.onFailure { toast("Couldn't remove ${app.label}") }
        reload()
    }

    fun launchSub(sub: SubEntry) {
        if (subSource.launch(sub.target)) {
            query = ""
        } else {
            toast("Couldn't open ${sub.label}")
            reloadShortcuts()
        }
    }

    /** Go on the keyboard: open the top app, or its first sub-result if only that matched. */
    fun launchTopResult() {
        // Compute from the current text rather than state.results, which may lag a keystroke.
        val counts = usage.launchCounts.value
        val subs = attachToApps(shortcuts.value + contacts.value, apps.value)
        val top = search(apps.value, subs, query, counts).firstOrNull() ?: return
        if (top.matchedItself || top.children.isEmpty()) launch(top.item) else launchSub(top.children.first())
    }

    fun toggleFavorite(app: AppEntry) = usage.toggleFavorite(app.key)

    fun moveToGroup(app: AppEntry, groupId: String?) = usage.setAppGroup(app.key, groupId)

    fun moveToNewGroup(app: AppEntry, name: String) {
        if (name.isBlank()) return
        usage.setAppGroup(app.key, usage.createGroup(name))
    }

    fun renameGroup(group: AppGroup, name: String) {
        if (name.isNotBlank()) usage.renameGroup(group.id, name)
    }

    fun deleteGroup(group: AppGroup) = usage.deleteGroup(group.id)

    fun moveGroup(group: AppGroup, delta: Int) = usage.moveGroup(group.id, delta)

    fun openAppInfo(app: AppEntry) = repository.openAppInfo(app)

    fun uninstall(app: AppEntry) = repository.uninstall(app)

    /** Home pressed while the launcher is showing: clear search and collapse everything. */
    fun onHomePressed() {
        query = ""
        expanded.value = emptySet()
        _resetEvents.tryEmit(Unit)
    }

    private fun toast(msg: String) = Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()

    override fun onCleared() {
        repository.unregisterCallback(callback)
        contactsObserver?.let { getApplication<Application>().contentResolver.unregisterContentObserver(it) }
    }

    private companion object {
        const val FREQUENT_LIMIT = 5
    }
}
