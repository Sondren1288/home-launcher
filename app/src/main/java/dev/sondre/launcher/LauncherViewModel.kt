package dev.sondre.launcher

import android.app.Application
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.content.Intent
import android.net.Uri
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
import java.text.Collator

/** Ids for collapsible sections. */
object SectionId {
    const val FOLDERS = "folders"
    const val FREQUENT = "freq"
    const val OTHER = "other"
    fun group(id: String) = "group:$id"
}

typealias SearchResult = AppSearch.Match<AppEntry, SubEntry>

data class UiState(
    val query: String = "",
    val frequent: List<AppEntry> = emptyList(),
    val groups: List<AppSearch.GroupSection<AppEntry>> = emptyList(),
    /** Every app, A–Z, including those in folders. */
    val all: List<AppEntry> = emptyList(),
    val results: List<SearchResult> = emptyList(),
    /** The answer when the query is arithmetic ("5*12/3" → "20"), else null. */
    val calculation: String? = null,
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
    private val settingsPages = MutableStateFlow<List<RawSubItem>>(emptyList())

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
        val groups: List<AppGroup>,
        val scores: Map<String, Frecency.Usage>,
        val hidden: Set<String>,
        val labels: Map<String, String>,
    )

    private val userData = combine(usage.groups, usage.launchScores, usage.hidden, usage.customLabels, ::UserData)

    /** Shortcuts and contacts, attached to the app they belong to. */
    private val subItems = combine(apps, shortcuts, contacts, settingsPages) { apps, sc, ct, st ->
        attachToApps(sc + ct + st, apps)
    }

    val state: StateFlow<UiState> =
        combine(apps, snapshotFlow { query }, userData, expanded, subItems) { raw, query, user, expanded, subs ->
            val apps = withUserData(raw, user.hidden, user.labels)
            val scores = scoresNow(user.scores)
            // Other home apps (One UI Home) and hidden apps are search-only.
            val listed = apps.filterNot { it.isOtherHome || it.hidden }
            val s = AppSearch.sections(listed, { it.key }, user.groups, scores, FREQUENT_LIMIT)
            UiState(
                query = query,
                frequent = s.frequent,
                groups = s.groups,
                all = listed,
                results = search(apps, subs, query, scores),
                calculation = Calculator.calculate(query),
                // reversed() so the first group wins on duplicates, matching AppSearch.sections
                groupOfApp = user.groups.reversed().flatMap { g -> g.appKeys.map { it to g } }.toMap(),
                expanded = expanded,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private fun search(apps: List<AppEntry>, subs: List<SubEntry>, query: String, scores: Map<String, Double>) =
        AppSearch.searchWithChildren(
            items = apps, children = subs, query = query,
            label = { it.label }, key = { it.key },
            childLabel = { it.label }, childOwner = { it.ownerKey },
            childKeywords = { it.keywords },
            boost = { scores[it.key] ?: 0.0 },
            // A renamed app is found by its own name too.
            altLabel = { it.ownLabel },
        )

    private fun scoresNow(scores: Map<String, Frecency.Usage>): Map<String, Double> {
        val now = System.currentTimeMillis()
        return scores.mapValues { Frecency.valueAt(it.value, now) }
    }

    /** Applies custom names (re-sorting A–Z by the shown name) and hidden flags. */
    private fun withUserData(apps: List<AppEntry>, hidden: Set<String>, labels: Map<String, String>): List<AppEntry> {
        if (hidden.isEmpty() && labels.isEmpty()) return apps
        val out = apps.map { a ->
            val custom = labels[a.key]
            a.copy(
                label = custom ?: a.label,
                ownLabel = if (custom != null) a.label else null,
                hidden = a.key in hidden,
            )
        }
        if (labels.isEmpty()) return out
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return out.sortedWith(compareBy(collator) { it.label })
    }

    private val callback = repository.registerCallback(
        onChange = { reload(); reloadShortcuts() },
        // Pinned shortcuts (web apps) are listed as apps, so reload those too.
        onShortcutsChanged = { reload(); reloadShortcuts() },
    )

    private var contactsReloadJob: Job? = null
    private var contactsObserver: ContentObserver? = null

    init {
        reload()
        reloadSettingsPages()
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

    private fun reloadSettingsPages() {
        viewModelScope.launch {
            settingsPages.value = withContext(Dispatchers.IO) { subSource.loadSettingsPages() }
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

    /** Swipe up on the home screen: open Folders and Most used. */
    fun expandHome() {
        expanded.value = expanded.value + SectionId.FOLDERS + SectionId.FREQUENT
    }

    /** Swipe right: open every section, and every folder inside Folders. */
    fun expandAll() {
        expanded.value = expanded.value + SectionId.FOLDERS + SectionId.FREQUENT + SectionId.OTHER +
            state.value.groups.map { SectionId.group(it.group.id) }
    }

    /**
     * Swipe left: close the open folders and All apps, which leaves the swipe-up state
     * (Folders + Most used). If none of those are open, close everything.
     */
    fun swipeClose() {
        val open = expanded.value
        val first = open.filter { it.startsWith(SectionId.group("")) || it == SectionId.OTHER }
        expanded.value = if (first.isNotEmpty()) open - first.toSet() else emptySet()
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
        if (query.isBlank()) return
        Calculator.calculate(query)?.let { return copyResult(it) }
        val scores = scoresNow(usage.launchScores.value)
        val all = withUserData(apps.value, usage.hidden.value, usage.customLabels.value)
        val subs = attachToApps(shortcuts.value + contacts.value + settingsPages.value, apps.value)
        val top = search(all, subs, query, scores).firstOrNull() ?: return searchWeb()
        if (top.matchedItself || top.children.isEmpty()) launch(top.item) else launchSub(top.children.first())
    }

    /**
     * Searches the web for the current query: with the phone's search app (Google) if it has
     * one, else in the browser.
     */
    fun searchWeb() {
        val q = query.trim()
        if (q.isEmpty()) return
        val ctx = getApplication<Application>()
        val webSearch = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
        for (intent in listOf(webSearch, browser)) {
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                query = ""
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            } catch (e: SecurityException) {
                // try the next one
            }
        }
        toast("No app can search the web")
    }

    /** Copies a calculator result. Android 13+ confirms copies itself, so only toast before that. */
    fun copyResult(result: String) {
        val ctx = getApplication<Application>()
        ctx.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Result", result))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("Copied $result")
    }

    fun setHidden(app: AppEntry, hidden: Boolean) = usage.setHidden(app.key, hidden)

    /** Renames [app]; a blank name or its own name restores the original. */
    fun rename(app: AppEntry, name: String) {
        val own = app.ownLabel ?: app.label
        usage.setCustomLabel(app.key, name.takeUnless { it.trim() == own })
    }

    fun resetName(app: AppEntry) = usage.setCustomLabel(app.key, null)

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
