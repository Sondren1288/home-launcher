package dev.sondre.launcher

import android.app.Application
import android.content.ActivityNotFoundException
import android.graphics.Rect
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val query: String = "",
    val favorites: List<AppEntry> = emptyList(),
    val frequent: List<AppEntry> = emptyList(),
    val all: List<AppEntry> = emptyList(),
    val results: List<AppEntry> = emptyList(),
    val favoriteKeys: Set<String> = emptySet(),
) {
    val isSearching get() = query.isNotBlank()
}

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    val repository = AppRepository(app)
    private val usage = UsageStore(app)

    private val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    private val query = MutableStateFlow("")

    private val _resetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when Home is pressed while the launcher is already showing. */
    val resetEvents = _resetEvents.asSharedFlow()

    val state: StateFlow<UiState> =
        combine(apps, query, usage.favorites, usage.launchCounts) { apps, query, favorites, counts ->
            val sections = AppSearch.sections(apps, { it.key }, favorites, counts, FREQUENT_LIMIT)
            UiState(
                query = query,
                favorites = sections.favorites,
                frequent = sections.frequent,
                all = sections.all,
                results = if (query.isBlank()) emptyList()
                else AppSearch.filter(apps, query, { it.label }, { counts[it.key] ?: 0 }),
                favoriteKeys = favorites.toSet(),
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val callback = repository.registerCallback { reload() }

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            apps.value = withContext(Dispatchers.IO) { repository.loadApps() }
        }
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun launch(app: AppEntry, sourceBounds: Rect? = null) {
        try {
            repository.launch(app, sourceBounds)
            usage.recordLaunch(app.key)
            query.value = ""
        } catch (e: ActivityNotFoundException) {
            toast("Couldn't open ${app.label}")
            reload()
        } catch (e: SecurityException) {
            toast("Couldn't open ${app.label}")
        }
    }

    fun launchTopResult() {
        state.value.results.firstOrNull()?.let { launch(it) }
    }

    fun toggleFavorite(app: AppEntry) = usage.toggleFavorite(app.key)

    fun openAppInfo(app: AppEntry) = repository.openAppInfo(app)

    fun uninstall(app: AppEntry) = repository.uninstall(app)

    fun onHomePressed() {
        query.value = ""
        _resetEvents.tryEmit(Unit)
    }

    private fun toast(msg: String) = Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()

    override fun onCleared() {
        repository.unregisterCallback(callback)
    }

    private companion object {
        const val FREQUENT_LIMIT = 5
    }
}
