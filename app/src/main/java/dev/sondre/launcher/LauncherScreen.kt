package dev.sondre.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tweak these to taste. */
private object Style {
    val iconSize = 24.dp
    val rowVerticalPadding = 7.dp
    val labelSize = 16.sp
    val scrim = Color.Black.copy(alpha = 0.55f)
    val text = Color.White
    val dim = Color.White.copy(alpha = 0.55f)
}

@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        vm.resetEvents.collect {
            focusManager.clearFocus()
            listState.scrollToItem(0)
        }
    }

    // A launcher must never "go back" anywhere; back only clears search / hides the keyboard.
    BackHandler {
        if (state.query.isNotEmpty()) vm.setQuery("") else focusManager.clearFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Style.scrim)
            .systemBarsPadding()
            .imePadding()
    ) {
        SearchField(
            query = state.query,
            onQueryChange = vm::setQuery,
            onGo = vm::launchTopResult,
        )

        val row: @Composable (AppEntry) -> Unit = { app ->
            AppRow(
                app = app,
                isFavorite = app.key in state.favoriteKeys,
                loadIcon = { a, px -> withContext(Dispatchers.IO) { vm.repository.loadIcon(a, px) } },
                cachedIcon = vm.repository::cachedIcon,
                onClick = { vm.launch(app) },
                onToggleFavorite = { vm.toggleFavorite(app) },
                onAppInfo = { vm.openAppInfo(app) },
                onUninstall = { vm.uninstall(app) },
            )
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (state.isSearching) {
                if (state.results.isEmpty()) {
                    item { Hint("No apps match \"${state.query}\"") }
                }
                section("results", null, state.results, row)
            } else {
                if (state.favorites.isEmpty()) {
                    item(key = "fav-hint") { Header("Favorites"); Hint("Long-press an app to add it here") }
                } else {
                    section("fav", "Favorites", state.favorites, row)
                }
                section("freq", "Most used", state.frequent, row)
                section("all", "All apps", state.all, row)
            }
        }
    }
}

/** Keys are prefixed per section because an app can appear in several sections. */
private fun LazyListScope.section(
    prefix: String,
    title: String?,
    apps: List<AppEntry>,
    row: @Composable (AppEntry) -> Unit,
) {
    if (apps.isEmpty()) return
    if (title != null) item(key = "$prefix-header") { Header(title) }
    items(apps, key = { "$prefix:${it.key}" }) { row(it) }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onGo: () -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search apps", color = Style.dim) },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.12f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = Style.text,
            unfocusedTextColor = Style.text,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun Header(title: String) {
    Text(
        text = title.uppercase(),
        color = Style.dim,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = Style.dim,
        fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppEntry,
    isFavorite: Boolean,
    loadIcon: suspend (AppEntry, Int) -> ImageBitmap?,
    cachedIcon: (AppEntry, Int) -> ImageBitmap?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val iconPx = with(LocalDensity.current) { Style.iconSize.roundToPx() }
    val icon by produceState(initialValue = cachedIcon(app, iconPx), app.key, iconPx) {
        if (value == null) value = loadIcon(app, iconPx)
    }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 20.dp, vertical = Style.rowVerticalPadding),
        ) {
            val bmp = icon
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(Style.iconSize))
            } else {
                Spacer(Modifier.size(Style.iconSize))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = app.label,
                color = Style.text,
                fontSize = Style.labelSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Remove from favorites" else "Add to favorites") },
                onClick = { menuOpen = false; onToggleFavorite() },
            )
            DropdownMenuItem(text = { Text("App info") }, onClick = { menuOpen = false; onAppInfo() })
            DropdownMenuItem(text = { Text("Uninstall") }, onClick = { menuOpen = false; onUninstall() })
        }
    }
}
