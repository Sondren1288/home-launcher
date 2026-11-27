package dev.sondre.launcher

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import android.os.SystemClock
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tweak these to taste. */
private object Style {
    val iconSize = 24.dp
    val rowVerticalPadding = 7.dp
    val labelSize = 16.sp
    val subLabelSize = 15.sp
    /** How dark the wallpaper gets while a section or the search is open. */
    const val scrimAlpha = 0.66f
    val text = Color.White
    val dim = Color.White.copy(alpha = 0.55f)
    /** Collapsed chevrons sit on the bare wallpaper, so keep them brighter. */
    val chevron = Color.White.copy(alpha = 0.85f)
    /** Tile behind the chevron + section icon. */
    val badge = Color.Black.copy(alpha = 0.35f)
    val searchBar = Color.White.copy(alpha = 0.10f)
    /** Extra indent for apps inside an open group. */
    val groupIndent = 24.dp
    /** Width of long-press menus and dialogs, as a fraction of the screen. */
    const val menuWidthFraction = 0.9f
    /** Long-press menus start here, left of the app icons (20dp) so they cover them fully. */
    val menuLeft = 8.dp
    val menuDivider = Color.White.copy(alpha = 0.18f)
    /** Clock: sized so time + date roughly span the three collapsed section tiles. */
    val clockTimeSize = 80.sp
    val clockDateSize = 15.sp
    /** Font of the clock's time: JetBrains Mono, weight 100–800 (700 = Bold). */
    val clockFont = ClockFonts.jetBrainsMono(500)
    val clockTop = 6.dp
    /** Letter in the A–Z dividers. */
    val letterSize = 11.sp
    /** How far a swipe on the home screen must travel (up: open lists, down: notifications). */
    val swipeDistance = 48.dp
}

/** Dialogs ignore the platform's narrow default width so they can use [Style.menuWidthFraction]. */
private val WideDialog = DialogProperties(usePlatformDefaultWidth = false)

/** Dialogs opened from long-press menus. */
private sealed interface DialogState {
    data class PickGroup(val app: AppEntry) : DialogState
    data class RenameApp(val app: AppEntry) : DialogState
    data class RenameGroup(val group: AppGroup) : DialogState
    data class DeleteGroup(val group: AppGroup) : DialogState
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val showContactsPrompt by vm.showContactsPrompt.collectAsStateWithLifecycle()
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onContactsPermissionResult(it)
    }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    LaunchedEffect(Unit) {
        vm.resetEvents.collect {
            focusManager.clearFocus()
            dialog = null
            listState.scrollToItem(0)
        }
    }

    // A launcher must never "go back" anywhere. Back clears the search, then hides the keyboard,
    // then closes any open sections.
    var searchFocused by remember { mutableStateOf(false) }
    BackHandler {
        when {
            vm.query.isNotEmpty() -> vm.updateQuery("")
            searchFocused -> focusManager.clearFocus()
            else -> vm.collapseAll()
        }
    }

    // Stop the cursor (and drop focus) once the keyboard has been dismissed, e.g. with the
    // keyboard's own hide button or the back gesture.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) focusManager.clearFocus()
        imeWasVisible = imeVisible
    }

    // Darken the wallpaper only while something is open: a section, or the search.
    val dimmed = state.expanded.isNotEmpty() || searchFocused || vm.query.isNotEmpty()
    val scrimAlpha by animateFloatAsState(if (dimmed) Style.scrimAlpha else 0f, label = "scrim")
    val searchListState = rememberLazyListState()
    // A new query starts at the best match (bottom of the reversed list).
    LaunchedEffect(state.query) { searchListState.scrollToItem(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            .statusBarsPadding()
    ) {
        val appRow: @Composable (AppEntry, Dp, Boolean) -> Unit = { app, indent, inSearch ->
            AppRow(
                app = app,
                indent = indent,
                // In search, say why an app looks unfamiliar: renamed, or normally hidden.
                note = if (!inSearch) null else listOfNotNull(app.ownLabel, "hidden".takeIf { app.hidden })
                    .joinToString(" · ").ifEmpty { null },
                group = state.groupOfApp[app.key],
                loadIcon = { a, px -> withContext(Dispatchers.IO) { vm.repository.loadIcon(a, px) } },
                cachedIcon = vm.repository::cachedIcon,
                onClick = { vm.launch(app) },
                onPickGroup = { dialog = DialogState.PickGroup(app) },
                onRemoveFromGroup = { vm.moveToGroup(app, null) },
                onAppInfo = { vm.openAppInfo(app) },
                onUninstall = { vm.uninstall(app) },
                onRemoveShortcut = { vm.removeShortcut(app) },
                onRename = { dialog = DialogState.RenameApp(app) },
                onResetName = { vm.resetName(app) },
                onSetHidden = { vm.setHidden(app, it) },
            )
        }
        val row: @Composable (AppEntry) -> Unit = { appRow(it, 0.dp, false) }

        // The clock sits on the layer *behind* the list and fades out as soon as anything opens,
        // so expanded rows never draw on top of it.
        val clockShown = state.expanded.isEmpty() && !searchFocused && vm.query.isEmpty()
        val clockAlpha by animateFloatAsState(if (clockShown) 1f else 0f, label = "clock")
        val context = LocalContext.current
        var clockBounds by remember { mutableStateOf(Rect.Zero) }
        // Double-tap to lock: time and place of the previous tap on the blank area.
        var lastBlankTap by remember { mutableStateOf<Pair<Long, Offset>?>(null) }
        var showLockSetup by remember { mutableStateOf(false) }
        val viewConfig = LocalViewConfiguration.current

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Clock(
                timeSize = Style.clockTimeSize,
                dateSize = Style.clockDateSize,
                font = Style.clockFont,
                color = Style.text,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = Style.clockTop, end = 20.dp)
                    .alpha(clockAlpha)
                    .onGloballyPositioned { clockBounds = it.boundsInRoot() },
            )

        LazyColumn(
            // Search results use their own state and grow upwards from the search bar.
            state = if (state.isSearching) searchListState else listState,
            reverseLayout = state.isSearching,
            modifier = Modifier
                .fillMaxSize()
                .clearFocusOnPress(focusManager)
                // The list covers the clock, so taps there are picked up here: only taps
                // nothing else in the list used (not scrolls, not section headers).
                .onUnusedTap { local, root ->
                    if (!clockShown) return@onUnusedTap
                    if (clockBounds.contains(root)) {
                        lastBlankTap = null
                        openClock(context)
                        return@onUnusedTap
                    }
                    // The blank area starts below both the section tiles and the clock.
                    val tilesBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                        ?.let { (it.offset + it.size).toFloat() } ?: 0f
                    val clockBottom = clockBounds.bottom - (root.y - local.y) // root → list coords
                    if (local.y <= maxOf(tilesBottom, clockBottom)) {
                        lastBlankTap = null
                        return@onUnusedTap
                    }
                    val now = SystemClock.uptimeMillis()
                    val prev = lastBlankTap
                    val isDouble = prev != null &&
                        now - prev.first <= viewConfig.doubleTapTimeoutMillis + 100 &&
                        (local - prev.second).getDistance() <= viewConfig.touchSlop * 4
                    if (isDouble) {
                        lastBlankTap = null
                        if (!LockService.lock()) showLockSetup = true
                    } else {
                        lastBlankTap = now to local
                    }
                }
                // On the bare home screen: swipe up opens Folders + Most used,
                // swipe down pulls down the notification shade.
                // Whenever not searching: swipe right opens everything (incl. every folder),
                // swipe left closes the folders, then everything.
                .onSwipe(
                    verticalEnabled = clockShown,
                    horizontalEnabled = !state.isSearching && !searchFocused,
                    minDistance = Style.swipeDistance,
                    onUp = vm::expandHome,
                    onDown = { expandNotifications(context) },
                    onRight = vm::expandAll,
                    onLeft = vm::swipeClose,
                ),
        ) {
            if (state.isSearching) {
                // reverseLayout: the first item emitted sits at the bottom, next to the search
                // bar, so the best match is closest to your thumb. Within a result the app must
                // still appear above its chats/contacts, so those are emitted first, reversed.
                if (state.results.isEmpty()) {
                    item(key = "web-search") { WebSearchRow(state.query, onClick = vm::searchWeb) }
                }
                for (r in state.results) {
                    items(r.children.asReversed(), key = { "res:${r.item.key}:${it.id}" }) { sub ->
                        SubRow(sub.label, onClick = { vm.launchSub(sub) })
                    }
                    item(key = "res:${r.item.key}") { appRow(r.item, 0.dp, true) }
                }
                if (showContactsPrompt) {
                    item(key = "contacts-prompt") {
                        ContactsPrompt(
                            onAllow = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
                            onDismiss = vm::dismissContactsPrompt,
                        )
                    }
                }
            } else {
                val groups = state.groups
                section(
                    id = SectionId.FOLDERS,
                    title = "Folders",
                    icon = Icons.Filled.Folder,
                    apps = emptyList(),
                    count = groups.size,
                    state = state, onToggle = vm::toggleSection, row = row,
                    emptyHint = "Long-press an app and choose \"Move to group\"",
                ) {
                    groups.forEachIndexed { index, (group, apps) ->
                        val groupId = SectionId.group(group.id)
                        val open = groupId in state.expanded
                        item(key = "folder:${group.id}") {
                            GroupRow(
                                name = group.name,
                                count = apps.size,
                                open = open,
                                onToggle = { vm.toggleSection(groupId) },
                                actions = buildList {
                                    add(MenuAction("Rename") { dialog = DialogState.RenameGroup(group) })
                                    if (index > 0) add(MenuAction("Move up") { vm.moveGroup(group, -1) })
                                    if (index < groups.lastIndex) add(MenuAction("Move down") { vm.moveGroup(group, +1) })
                                    add(MenuAction("Delete group") { dialog = DialogState.DeleteGroup(group) })
                                },
                            )
                        }
                        if (open) {
                            if (apps.isEmpty()) {
                                item(key = "folder-hint:${group.id}") {
                                    Hint(
                                        "Long-press an app and choose \"Move to group\"",
                                        Modifier.padding(start = Style.groupIndent),
                                    )
                                }
                            }
                            items(apps, key = { "folder:${group.id}:${it.key}" }) { appRow(it, Style.groupIndent, false) }
                        }
                    }
                }
                section(
                    id = SectionId.FREQUENT, title = "Most used", icon = Icons.Filled.Schedule,
                    apps = state.frequent,
                    state = state, onToggle = vm::toggleSection, row = row,
                    emptyHint = "Apps you open often will show up here",
                )
                // Every app, A–Z: apps in folders are listed here too.
                section(
                    id = SectionId.OTHER,
                    title = "All apps",
                    icon = Icons.Filled.Apps,
                    apps = state.all,
                    state = state, onToggle = vm::toggleSection, row = row,
                    letterDividers = true,
                )
            }
        }
        } // Box(clock + list)

        if (showLockSetup) {
            AlertDialog(
                onDismissRequest = { showLockSetup = false },
                shape = RectangleShape,
                properties = WideDialog,
                modifier = Modifier.fillMaxWidth(Style.menuWidthFraction),
                title = { Text("Double-tap to lock") },
                text = {
                    Text(
                        "To lock the phone with a double-tap, switch on \"Home: double-tap to lock\" under " +
                            "Settings → Accessibility → Installed apps. It's only used to lock the screen, " +
                            "and fingerprint/face unlock keep working."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showLockSetup = false; LockService.openSettings(context) }) {
                        Text("Open settings")
                    }
                },
                dismissButton = { TextButton(onClick = { showLockSetup = false }) { Text("Not now") } },
            )
        }

        // Full-width, square bar at the bottom. Its background runs under the navigation bar,
        // and it rides on top of the keyboard when that's open.
        Box(
            Modifier
                .fillMaxWidth()
                .background(Style.searchBar)
                .navigationBarsPadding()
                .imePadding()
        ) {
            SearchField(
                query = vm.query,
                onQueryChange = vm::updateQuery,
                onGo = vm::launchTopResult,
                onFocusChange = { searchFocused = it },
            )
        }
    }

    when (val d = dialog) {
        null -> {}
        is DialogState.PickGroup -> GroupPickerDialog(
            app = d.app,
            groups = state.groups.map { it.group },
            current = state.groupOfApp[d.app.key],
            onPick = { vm.moveToGroup(d.app, it.id); dialog = null },
            onCreate = { vm.moveToNewGroup(d.app, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is DialogState.RenameApp -> NameDialog(
            title = "Rename ${d.app.ownLabel ?: d.app.label}",
            initial = d.app.label,
            confirmLabel = "Rename",
            // Blank is allowed here: it restores the app's own name.
            allowBlank = true,
            hint = "Search finds it by both names. Leave empty to restore the original.",
            onConfirm = { vm.rename(d.app, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is DialogState.RenameGroup -> NameDialog(
            title = "Rename group",
            initial = d.group.name,
            confirmLabel = "Rename",
            onConfirm = { vm.renameGroup(d.group, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is DialogState.DeleteGroup -> AlertDialog(
            onDismissRequest = { dialog = null },
            shape = RectangleShape,
            properties = WideDialog,
            modifier = Modifier.fillMaxWidth(Style.menuWidthFraction),
            title = { Text("Delete \"${d.group.name}\"?") },
            text = { Text("The apps in it go back to the main list. They are not uninstalled.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteGroup(d.group); dialog = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Drops focus (hiding the keyboard and stopping the cursor) when a short tap inside this element
 * is released. Long-presses keep it: hiding the keyboard under the finger would shift the
 * bottom-anchored search results while the long-press menu opens. Only observes the Initial
 * pass, so taps, long-presses and scrolls still reach the children.
 */
private fun Modifier.clearFocusOnPress(focusManager: FocusManager): Modifier = pointerInput(focusManager) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation(PointerEventPass.Initial)
        }
        if (up != null) focusManager.clearFocus()
    }
}

/**
 * Calls [onTap] (with the position in root coordinates) for taps that no child consumed:
 * a scroll or a click on a row/header cancels it. Runs in the Final pass so children go first.
 */
private fun Modifier.onUnusedTap(onTap: (local: Offset, root: Offset) -> Unit): Modifier = composed {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentOnTap by rememberUpdatedState(onTap)
    onGloballyPositioned { coords = it }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            val up = waitForUpOrCancellation(pass = PointerEventPass.Final) ?: return@awaitEachGesture
            coords?.let { currentOnTap(up.position, it.localToRoot(up.position)) }
        }
    }
}

/**
 * Calls [onUp] / [onDown] for a mostly-vertical swipe of at least [minDistance]. Observes the
 * Initial pass and only consumes the rest of a gesture once it has fired, so normal scrolling
 * is untouched (and is off anyway while [enabled] is false).
 */
private fun Modifier.onSwipe(
    verticalEnabled: Boolean,
    horizontalEnabled: Boolean,
    minDistance: Dp,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRight: () -> Unit,
    onLeft: () -> Unit,
): Modifier = composed {
    val currentVertical by rememberUpdatedState(verticalEnabled)
    val currentHorizontal by rememberUpdatedState(horizontalEnabled)
    val currentOnUp by rememberUpdatedState(onUp)
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnRight by rememberUpdatedState(onRight)
    val currentOnLeft by rememberUpdatedState(onLeft)
    pointerInput(Unit) {
        val threshold = minDistance.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val verticalOn = currentVertical
            val horizontalOn = currentHorizontal
            if (!verticalOn && !horizontalOn) return@awaitEachGesture
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val d = change.position - down.position
                val ax = kotlin.math.abs(d.x)
                val ay = kotlin.math.abs(d.y)
                if (!fired && verticalOn && ay > 2 * ax && ay > threshold) {
                    fired = true
                    if (d.y < 0) currentOnUp() else currentOnDown()
                } else if (!fired && horizontalOn && ax > 2 * ay && ax > threshold) {
                    fired = true
                    if (d.x > 0) currentOnRight() else currentOnLeft()
                }
                // After firing, the rest of this swipe shouldn't scroll the list or, since the
                // release is consumed too, count as a tap on the row it started on.
                if (fired) change.consume()
                if (!change.pressed) break
            }
        }
    }
}

/**
 * A collapsible section: header row with a chevron, and the apps when expanded.
 * Item keys are prefixed with the section id because an app can appear in several sections.
 */
private fun LazyListScope.section(
    id: String,
    title: String,
    icon: ImageVector,
    apps: List<AppEntry>,
    state: UiState,
    onToggle: (String) -> Unit,
    row: @Composable (AppEntry) -> Unit,
    count: Int = apps.size,
    emptyHint: String? = null,
    /** Insert "B ──── B" dividers between letters (for the A–Z list). */
    letterDividers: Boolean = false,
    /** Extra items shown above [apps] while expanded (the folders, for "Folders"). */
    nested: LazyListScope.() -> Unit = {},
) {
    val expanded = id in state.expanded
    item(key = "header:$id") {
        SectionHeader(title, icon, count, expanded, onToggle = { onToggle(id) })
    }
    if (!expanded) return
    if (count == 0 && emptyHint != null) {
        item(key = "hint:$id") { Hint(emptyHint) }
    }
    nested()
    if (letterDividers) {
        val entries = AppSearch.withLetterDividers(apps) { it.label }
        items(
            entries,
            key = {
                when (it) {
                    is AppSearch.Lettered.Divider -> "$id:letter:${it.letter}"
                    is AppSearch.Lettered.Item -> "$id:${it.item.key}"
                }
            },
            contentType = { if (it is AppSearch.Lettered.Divider) "divider" else "app" },
        ) {
            when (it) {
                is AppSearch.Lettered.Divider -> LetterDivider(it.letter)
                is AppSearch.Lettered.Item -> row(it.item)
            }
        }
    } else {
        items(apps, key = { "$id:${it.key}" }) { row(it) }
    }
}

/** "B ──────── B": a full-width divider that names the letter coming up. */
@Composable
private fun LetterDivider(letter: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
    ) {
        Text(letter, color = Style.dim, fontSize = Style.letterSize, fontFamily = FontFamily.Monospace)
        HorizontalDivider(
            thickness = 0.5.dp,
            color = Style.dim.copy(alpha = 0.3f),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(letter, color = Style.dim, fontSize = Style.letterSize, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onGo: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search apps", tint = Style.dim) },
        singleLine = true,
        shape = RectangleShape,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = Style.text,
            unfocusedTextColor = Style.text,
            cursorColor = Style.text,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChange(it.isFocused) },
    )
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    // Points right when collapsed, down when expanded.
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // Collapsed: only the tile is tappable, so taps next to it reach the clock behind.
            .then(if (expanded) Modifier.fillMaxWidth() else Modifier)
            .clickable(
                onClickLabel = if (expanded) "Collapse $title" else "Expand $title",
                onClick = onToggle,
            )
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    ) {
            // Chevron + section icon on a darker, sharp-cornered tile so they read on any wallpaper.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Style.badge, RectangleShape)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Style.chevron,
                    modifier = Modifier.size(22.dp).rotate(rotation),
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = Style.chevron,
                    modifier = Modifier.padding(start = 2.dp, end = 4.dp).size(18.dp),
                )
            }
            // Collapsed sections show only the tile; the title appears once expanded.
            if (expanded) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title.uppercase(),
                    color = Style.dim,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = count.toString(), color = Style.dim.copy(alpha = 0.35f), fontSize = 12.sp)
            }
    }
}

/** A group ("folder") inside All apps: looks like an app row; tap to open, long-press for options. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    name: String,
    count: Int,
    open: Boolean,
    onToggle: () -> Unit,
    actions: List<MenuAction>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (open) "Close $name" else "Open $name",
                    onClick = onToggle,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 20.dp, vertical = Style.rowVerticalPadding),
        ) {
            Icon(
                imageVector = if (open) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                contentDescription = null,
                tint = Style.text,
                modifier = Modifier.size(Style.iconSize),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = name,
                color = Style.text,
                fontSize = Style.labelSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = count.toString(), color = Style.dim, fontSize = 13.sp)
        }
        LauncherMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
    }
}

/** One entry in a [LauncherMenu]. */
private data class MenuAction(val label: String, val onClick: () -> Unit)

/**
 * Long-press menu: wide, sharp corners, thin dividers between entries. It starts a little
 * left of the app icons so it fully covers the rows underneath.
 */
@Composable
private fun LauncherMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>) {
    val screen = LocalConfiguration.current.screenWidthDp.dp
    val left = Style.menuLeft
    val right = screen * (1 + Style.menuWidthFraction) / 2 // same right edge as a centred menu
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(right - left),
        offset = DpOffset(left, 0.dp),
        shape = RectangleShape,
        // Not focusable: a focusable popup steals window focus, which makes Android hide the
        // keyboard (and shifts the search results) when long-pressing a result.
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
    ) {
        // Without focus the popup doesn't get Back itself, so close it from the activity's handler.
        BackHandler(onBack = onDismiss)
        actions.forEachIndexed { i, action ->
            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = Style.menuDivider)
            DropdownMenuItem(
                text = { Text(action.label) },
                onClick = { onDismiss(); action.onClick() },
                contentPadding = PaddingValues(start = 20.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Style.dim,
        fontSize = 14.sp,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** Shown when nothing matches: search the web for the query instead. */
@Composable
private fun WebSearchRow(query: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Search the web", onClick = onClick)
            .padding(horizontal = 20.dp, vertical = Style.rowVerticalPadding),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Style.text, modifier = Modifier.size(Style.iconSize))
        Spacer(Modifier.width(14.dp))
        Text(
            text = "Search the web for \"${query.trim()}\"",
            color = Style.text,
            fontSize = Style.labelSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A chat, contact or shortcut, indented under its app so it lines up with the app's name. */
@Composable
private fun SubRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Style.text.copy(alpha = 0.85f),
        fontSize = Style.subLabelSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp + Style.iconSize + 14.dp, end = 20.dp, top = 5.dp, bottom = 5.dp),
    )
}

@Composable
private fun ContactsPrompt(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
    ) {
        Text("Also search contacts?", color = Style.dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("No thanks", color = Style.dim) }
        TextButton(onClick = onAllow) { Text("Allow") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppEntry,
    group: AppGroup?,
    loadIcon: suspend (AppEntry, Int) -> ImageBitmap?,
    cachedIcon: (AppEntry, Int) -> ImageBitmap?,
    onClick: () -> Unit,
    onPickGroup: () -> Unit,
    onRemoveFromGroup: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
    onRemoveShortcut: () -> Unit,
    onRename: () -> Unit,
    onResetName: () -> Unit,
    onSetHidden: (Boolean) -> Unit,
    /** Extra start padding, for apps shown inside an open group. */
    indent: Dp = 0.dp,
    /** Small dim text after the name (in search: the original name, "hidden"). */
    note: String? = null,
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
                .padding(start = 20.dp + indent, end = 20.dp)
                .padding(vertical = Style.rowVerticalPadding),
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
                modifier = Modifier.weight(1f, fill = false),
            )
            if (note != null) {
                Spacer(Modifier.width(8.dp))
                Text(text = note, color = Style.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LauncherMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            actions = if (app.isOtherHome) {
                // Search-only (One UI Home): a group would show it outside search, and it can't be uninstalled.
                listOf(MenuAction("App info", onAppInfo))
            } else buildList {
                add(MenuAction(if (group == null) "Move to group…" else "Move to another group…", onPickGroup))
                if (group != null) add(MenuAction("Remove from \"${group.name}\"", onRemoveFromGroup))
                add(MenuAction("Rename…", onRename))
                if (app.ownLabel != null) add(MenuAction("Restore name \"${app.ownLabel}\"", onResetName))
                // Hidden apps only show up in search, which is where they're unhidden.
                add(if (app.hidden) MenuAction("Unhide") { onSetHidden(false) } else MenuAction("Hide") { onSetHidden(true) })
                if (app.isShortcut) {
                    // A pinned shortcut / web app: it has no app info or uninstall of its own.
                    add(MenuAction("Remove from launcher", onRemoveShortcut))
                } else {
                    add(MenuAction("App info", onAppInfo))
                    add(MenuAction("Uninstall", onUninstall))
                }
            },
        )
    }
}

@Composable
private fun GroupPickerDialog(
    app: AppEntry,
    groups: List<AppGroup>,
    current: AppGroup?,
    onPick: (AppGroup) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        properties = WideDialog,
        modifier = Modifier.fillMaxWidth(Style.menuWidthFraction),
        title = { Text("Move ${app.label} to") },
        text = {
            Column {
                if (groups.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        for (g in groups) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(g) }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text(g.name, modifier = Modifier.weight(1f))
                                if (g.id == current?.id) Icon(Icons.Filled.Check, contentDescription = "Current group")
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New group") },
                    singleLine = true,
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (newName.isNotBlank()) onCreate(newName) }),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { onCreate(newName) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    allowBlank: Boolean = false,
    hint: String? = null,
) {
    var name by remember { mutableStateOf(initial) }
    val valid = allowBlank || name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        properties = WideDialog,
        modifier = Modifier.fillMaxWidth(Style.menuWidthFraction),
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name) }),
                )
                if (hint != null) {
                    Text(hint, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(name) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
