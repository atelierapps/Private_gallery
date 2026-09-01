package com.atelierapps.vault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.SingletonImageLoader
import com.atelierapps.vault.session.VaultSession
import com.atelierapps.vault.ui.grid.VaultGridScreen
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import com.atelierapps.vault.ui.theme.Danger
import com.atelierapps.vault.ui.theme.Hairline
import com.atelierapps.vault.ui.theme.Space
import com.atelierapps.vault.ui.theme.Surface
import com.atelierapps.vault.ui.theme.TagPicker
import com.atelierapps.vault.ui.theme.VaultIconButton
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Scrim
import androidx.compose.material.icons.outlined.Add
import com.atelierapps.vault.session.TileAnchor
import com.atelierapps.vault.session.AppDisguise
import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.navigationBarsPadding
import kotlinx.coroutines.launch
import com.atelierapps.vault.ui.lock.FloatingLockButton
import com.atelierapps.vault.session.BackupPrefs
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ContentCopy

/**
 * Home screen (spec §7, §8): filter bar over the decrypting grid, plus a
 * multi-select mode — long-press a tile to start, then delete the selection or
 * move it back out to the gallery.
 */
@Composable
fun VaultHome(
    onOpen: (id: String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onCamera: () -> Unit,
    onTrash: () -> Unit,
    onDuplicates: () -> Unit,
    onRules: () -> Unit,
    onAlbums: () -> Unit,
    onTags: () -> Unit,
    onSettings: () -> Unit,
    onExportSelection: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    vm: GridViewModel = viewModel(),
) {
    val context = LocalContext.current
    // Re-read display prefs whenever we resume (e.g. returning from Settings).
    var prefsEpoch by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(context) {
        val owner = context as? androidx.lifecycle.LifecycleOwner
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) prefsEpoch++
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    val dateHeadersPref = remember(prefsEpoch) { com.atelierapps.vault.session.DisplayPrefs.dateHeaders(context) }
    val columnsPref = remember(prefsEpoch) { com.atelierapps.vault.session.DisplayPrefs.columns(context) }
    val filter by vm.filter.collectAsState()
    val sources by vm.sourceChips.collectAsState()
    val tags by vm.tagChips.collectAsState()
    val sort by vm.sort.collectAsState()
    val media by vm.media.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val working by vm.working.collectAsState()
    val query by vm.query.collectAsState()
    val trashCount by vm.trashCount.collectAsState()
    val duplicateCount by vm.duplicateCount.collectAsState()
    val albums by vm.albums.collectAsState()

    var confirmDelete by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showAlbumDialog by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf(false) }
    var organising by remember { mutableStateOf(false) }

    // Said once, when there is finally something to lose. A vault whose only
    // copy dies with the install has to tell you that before it happens, not
    // in a help page you would have to go looking for. Shown at fifteen items
    // rather than the first, so it lands when it means something.
    var backupNotice by remember { mutableStateOf(false) }
    LaunchedEffect(media.size) {
        if (media.size >= 15 &&
            BackupPrefs.lastBackupAtMillis.value == 0L &&
            !BackupPrefs.warned(context)
        ) {
            backupNotice = true
        }
    }

    // Bulk actions used to complete in total silence — thirty items would
    // vanish with nothing to say it worked, or that it hadn't.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(message: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            // One at a time: a queue of stale confirmations is worse than none.
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = action,
                withDismissAction = action == null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    // Back should undo the mode you're in before it leaves the app. Without
    // this, backing out of a 40-item selection closed the vault outright — and
    // on a gesture-navigation phone that is one careless swipe from the edge.
    // One handler rather than several: two enabled BackHandlers race on
    // registration order, and this makes the priority explicit.
    BackHandler(enabled = selectionMode || searchOpen) {
        when {
            selectionMode -> vm.clearSelection()
            else -> { searchOpen = false; vm.setQuery("") }
        }
    }

    Box(modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                SelectionBar(
                    count = selectedIds.size,
                    onClose = vm::clearSelection,
                    onSelectAll = vm::selectAll,
                    onTag = { showTagDialog = true },
                    onAlbum = { showAlbumDialog = true },
                    onMove = { confirmMove = true },
                    onOrganise = { organising = true },
                    onExport = { onExportSelection(selectedIds) },
                    onDelete = { confirmDelete = true },
                )
            } else {
                TopAppRow(
                    onExport = onExport,
                    onRestore = onRestore,
                    onCamera = onCamera,
                    onShuffle = {
                        val order = vm.shuffledIds()
                        if (order.isNotEmpty()) {
                            com.atelierapps.vault.ui.viewer.ViewerSession.orderedIds = order
                            com.atelierapps.vault.ui.viewer.ViewerSession.startPlaying = true
                            // Shuffle isn't anchored to a tile, so open the plain way.
                            TileAnchor.clear()
                            onOpen(order.first())
                        }
                    },
                    onTrash = onTrash,
                    onDuplicates = onDuplicates,
                    onRules = onRules,
                    onAlbums = onAlbums,
                    onTags = onTags,
                    onSettings = onSettings,
                    trashCount = trashCount,
                    duplicateCount = duplicateCount,
                    onToggleSearch = { searchOpen = !searchOpen; if (!searchOpen) vm.setQuery("") },
                    onLockNow = {
                        VaultSession.lock()
                        runCatching { SingletonImageLoader.get(context).memoryCache?.clear() }
                    },
                )
                if (searchOpen) {
                    SearchField(query = query, onQueryChange = vm::setQuery)
                }
                FilterBar(
                    filter = filter,
                    sources = sources,
                    tags = tags,
                    sort = sort,
                    onSetType = vm::setType,
                    onToggleSource = vm::toggleSource,
                    onToggleTag = vm::toggleTag,
                    onSetDate = vm::setDate,
                    onSetSort = vm::setSort,
                    onClearAll = vm::clearAll,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            VaultGridScreen(
                media = media,
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                onOpen = { id ->
                    // Give the viewer the current visible order (filter/tag/sort/pin)
                    // so paging and slideshow stay in this context.
                    com.atelierapps.vault.ui.viewer.ViewerSession.orderedIds = media.map { it.media.id }
                    onOpen(id)
                },
                onLongPress = vm::longPress,
                onToggleSelect = vm::toggleSelection,
                modifier = Modifier.weight(1f),
                showSectionHeaders = dateHeadersPref &&
                    (sort == SortOrder.RECENT || sort == SortOrder.NEWEST || sort == SortOrder.OLDEST),
                dateOf = if (sort == SortOrder.RECENT) {
                    { it.media.importedAtMillis }
                } else {
                    { it.media.dateTakenMillis }
                },
                initialColumns = columnsPref,
                onImport = onImport,
                onCamera = onCamera,
            )
        }

        if (!selectionMode) {
            FloatingActionButton(
                onClick = onImport,
                containerColor = Brass,
                contentColor = BrassInk,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Import")
            }
        }

        if (working) {
            Box(
                Modifier.fillMaxSize().background(Scrim),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Brass)
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        // Stood down during selection: the selection bar already owns the top of
        // the screen and a stray tap there would throw the selection away.
        FloatingLockButton(visible = !selectionMode)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selectedIds.size} item(s)?") },
            text = { Text("They move to Recently deleted, where you can restore them for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelected { ids ->
                        say("${ids.size} moved to Recently deleted", "Undo") { vm.restoreTrashed(ids) }
                    }
                }) {
                    Text("Delete", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    // Unlike delete, this one really is one-way: the media is written to the
    // gallery and purged here, so there is nothing left to undo it from. Ask
    // before, since we can't offer a way back after.
    if (confirmMove) {
        AlertDialog(
            onDismissRequest = { confirmMove = false },
            title = { Text("Move ${selectedIds.size} item(s) out?") },
            text = {
                Text(
                    "They're decrypted into your device gallery and removed from here. " +
                        "This does not go to Recently deleted and can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmMove = false
                    vm.moveSelectedToGallery { moved, failed ->
                        say(
                            if (failed == 0) "$moved moved to your gallery"
                            else "$moved moved, $failed couldn't be",
                        )
                    }
                }) {
                    Text("Move out", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmMove = false }) { Text("Cancel") } },
        )
    }

    if (backupNotice) {
        AlertDialog(
            onDismissRequest = { backupNotice = false; BackupPrefs.setWarned(context) },
            title = { Text("If you uninstall, this is gone") },
            text = {
                Text(
                    "Your ${media.size} items live in this app's private storage, and the " +
                        "key that decrypts them belongs to this install. Uninstalling " +
                        "deletes both. There is no cloud copy, no account, and no way to " +
                        "get any of it back afterwards — not by reinstalling, not by any " +
                        "other means.\n\nAn export writes everything to a folder you " +
                        "choose, which survives on its own and can be restored later. " +
                        "It's the only copy that outlives the app.",
                )
            },
            confirmButton = {
                TextButton(onClick = { backupNotice = false; BackupPrefs.setWarned(context); onExport() }) {
                    Text("Back up now", color = Brass)
                }
            },
            dismissButton = {
                TextButton(onClick = { backupNotice = false; BackupPrefs.setWarned(context) }) {
                    Text("Later", color = Muted)
                }
            },
        )
    }

    if (organising) {
        OrganiseDialog(
            selection = media.filter { it.media.id in selectedIds },
            existingTags = tags,
            onApply = { template, dateFormat, tagNames, source ->
                organising = false
                vm.organiseSelected(template, dateFormat, tagNames, source) { n ->
                    say("$n item(s) organised")
                }
            },
            onDismiss = { organising = false },
        )
    }

    if (showTagDialog) {
        TagDialog(
            existingTags = tags,
            onApply = { names ->
                vm.tagSelected(names) { n -> say("Tagged $n item(s)") }
                showTagDialog = false
            },
            onDismiss = { showTagDialog = false },
        )
    }

    if (showAlbumDialog) {
        AlbumPickerDialog(
            albums = albums,
            onPick = { id ->
                val name = albums.firstOrNull { it.id == id }?.name
                vm.addSelectedToAlbum(id) { n ->
                    say(if (name != null) "$n added to $name" else "$n added to album")
                }
                showAlbumDialog = false
            },
            onCreate = { name ->
                vm.addSelectedToNewAlbum(name) { n -> say("$n added to $name") }
                showAlbumDialog = false
            },
            onDismiss = { showAlbumDialog = false },
        )
    }
}

@Composable
private fun AlbumPickerDialog(
    albums: List<com.atelierapps.vault.data.entity.AlbumEntity>,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newAlbum by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album") },
        text = {
            Column {
                if (albums.isEmpty()) {
                    Text("No albums yet — name one below.", color = Muted, fontSize = 13.sp)
                } else {
                    // Bounded and scrollable: AlertDialog gives its body
                    // `weight(1f, fill = false)` and no scroll of its own, so a
                    // long list is simply cut off at the dialog's edge — taking
                    // the field below it with it.
                    Column(
                        Modifier.heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        albums.forEach { album ->
                            Text(
                                album.name,
                                color = Ink, fontSize = 15.sp,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onPick(album.id) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                }
                androidx.compose.material3.OutlinedTextField(
                    value = newAlbum,
                    onValueChange = { newAlbum = it },
                    placeholder = { Text("New album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(newAlbum) },
                enabled = newAlbum.isNotBlank(),
            ) { Text("Create & add", color = Brass) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search name, tag, or source", color = Muted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun TagDialog(
    existingTags: List<com.atelierapps.vault.data.entity.TagEntity>,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val picked = remember { mutableStateListOf<String>() }
    var newTag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add tags") },
        text = {
            androidx.compose.foundation.layout.Column {
                TagPicker(
                    tags = existingTags,
                    isPicked = { name -> picked.any { it.equals(name, ignoreCase = true) } },
                    onToggle = { name ->
                        if (picked.any { it.equals(name, ignoreCase = true) }) {
                            picked.removeAll { it.equals(name, ignoreCase = true) }
                        } else {
                            picked.add(name)
                        }
                    },
                )
                androidx.compose.material3.OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    placeholder = { Text("New tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val all = (picked + newTag.split(",")).map { it.trim() }.filter { it.isNotEmpty() }
                onApply(all)
            }) { Text("Apply", color = Brass) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TopAppRow(
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onCamera: () -> Unit,
    onShuffle: () -> Unit,
    onTrash: () -> Unit,
    onDuplicates: () -> Unit,
    onRules: () -> Unit,
    onAlbums: () -> Unit,
    onTags: () -> Unit,
    onSettings: () -> Unit,
    trashCount: Int,
    duplicateCount: Int,
    onToggleSearch: () -> Unit,
    onLockNow: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, top = Space.sm, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            AppDisguise.currentLabel(LocalContext.current),
            color = Ink,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        VaultIconButton(Icons.Outlined.PhotoCamera, "Camera", onCamera)
        VaultIconButton(Icons.Outlined.Shuffle, "Shuffle play", onShuffle)
        VaultIconButton(Icons.Outlined.Search, "Search", onToggleSearch)
        Box {
            VaultIconButton(Icons.Outlined.MoreVert, "More", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuSection("Library")
                MenuRow(Icons.Outlined.PhotoAlbum, "Albums") { menu = false; onAlbums() }
                MenuRow(Icons.Outlined.LocalOffer, "Tags") { menu = false; onTags() }
                MenuRow(Icons.Outlined.AutoAwesome, "Auto-tag rules") { menu = false; onRules() }
                MenuRow(
                    Icons.Outlined.ContentCopy,
                    "Duplicates" + if (duplicateCount > 0) "  ($duplicateCount)" else "",
                ) { menu = false; onDuplicates() }
                MenuRow(
                    Icons.Outlined.DeleteOutline,
                    "Recently deleted" + if (trashCount > 0) "  ($trashCount)" else "",
                ) { menu = false; onTrash() }

                HorizontalDivider(color = Hairline)
                MenuSection("Backup")
                MenuRow(Icons.Outlined.Upload, "Export / back up") { menu = false; onExport() }
                MenuRow(Icons.Outlined.Restore, "Restore from backup") { menu = false; onRestore() }

                HorizontalDivider(color = Hairline)
                MenuRow(Icons.Outlined.Lock, "Lock now") { menu = false; onLockNow() }
                MenuRow(Icons.Outlined.Settings, "Settings") { menu = false; onSettings() }
            }
        }
    }
}

/** One row of the overflow menu: leading icon, label, consistent everywhere. */
@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun MenuSection(title: String) {
    Text(
        title,
        color = Brass, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onTag: () -> Unit,
    onAlbum: () -> Unit,
    onMove: () -> Unit,
    onOrganise: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    // The common actions stay on the bar; the rarer ones (leaving the vault) sit
    // behind an overflow so the row doesn't turn into seven cramped buttons.
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Surface).padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VaultIconButton(Icons.Outlined.Close, "Clear selection", onClose)
        Text(
            "$count selected",
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = Space.xs),
        )
        VaultIconButton(Icons.Outlined.SelectAll, "Select all", onSelectAll)
        VaultIconButton(Icons.Outlined.LocalOffer, "Tag", onTag, enabled = count > 0)
        VaultIconButton(Icons.Outlined.PhotoAlbum, "Add to album", onAlbum, enabled = count > 0)
        VaultIconButton(Icons.Outlined.DeleteOutline, "Delete", onDelete, tint = Danger, enabled = count > 0)
        Box {
            VaultIconButton(Icons.Outlined.MoreVert, "More", { more = true }, enabled = count > 0)
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                MenuRow(Icons.Outlined.Edit, "Rename & organise…") {
                    more = false
                    onOrganise()
                }
                MenuRow(Icons.Outlined.Upload, "Export selection…") { more = false; onExport() }
                MenuRow(Icons.Outlined.PhotoLibrary, "Move back to gallery") { more = false; onMove() }
            }
        }
    }
}
