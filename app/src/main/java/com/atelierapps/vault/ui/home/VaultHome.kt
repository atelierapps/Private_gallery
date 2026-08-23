package com.atelierapps.vault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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

private val Ink = Color(0xFFE9EEF0)
private val Brass = Color(0xFFD8B463)

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
    val albums by vm.albums.collectAsState()

    var confirmDelete by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showAlbumDialog by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Color(0xFF0E1113))) {
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                SelectionBar(
                    count = selectedIds.size,
                    onClose = vm::clearSelection,
                    onSelectAll = vm::selectAll,
                    onTag = { showTagDialog = true },
                    onAlbum = { showAlbumDialog = true },
                    onMove = vm::moveSelectedToGallery,
                    onExport = { onExportSelection(selectedIds) },
                    onDelete = { confirmDelete = true },
                )
            } else {
                TopAppRow(
                    onExport = onExport,
                    onRestore = onRestore,
                    onCamera = onCamera,
                    onTrash = onTrash,
                    onRules = onRules,
                    onAlbums = onAlbums,
                    onTags = onTags,
                    onSettings = onSettings,
                    trashCount = trashCount,
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
                onLongPress = vm::startSelection,
                onToggleSelect = vm::toggleSelection,
                modifier = Modifier.weight(1f),
                showSectionHeaders = dateHeadersPref && (sort == SortOrder.NEWEST || sort == SortOrder.OLDEST),
                initialColumns = columnsPref,
                onImport = onImport,
                onCamera = onCamera,
            )
        }

        if (!selectionMode) {
            FloatingActionButton(
                onClick = onImport,
                containerColor = Brass,
                contentColor = Color(0xFF1A1509),
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Text("+", fontSize = 26.sp)
            }
        }

        if (working) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC06080A)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Brass)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selectedIds.size} item(s)?") },
            text = { Text("They'll be permanently removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) {
                    Text("Delete", color = Color(0xFFE08A7A))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (showTagDialog) {
        TagDialog(
            existingTags = tags,
            onApply = { names -> vm.tagSelected(names); showTagDialog = false },
            onDismiss = { showTagDialog = false },
        )
    }

    if (showAlbumDialog) {
        AlbumPickerDialog(
            albums = albums,
            onPick = { id -> vm.addSelectedToAlbum(id); showAlbumDialog = false },
            onCreate = { name -> vm.addSelectedToNewAlbum(name); showAlbumDialog = false },
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
                    albums.forEach { album ->
                        Text(
                            album.name,
                            color = Ink, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPick(album.id) }
                                .padding(vertical = 10.dp),
                        )
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

@OptIn(ExperimentalLayoutApi::class)
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
                if (existingTags.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        existingTags.take(12).forEach { tag ->
                            val on = picked.contains(tag.name)
                            androidx.compose.material3.FilterChip(
                                selected = on,
                                onClick = { if (on) picked.remove(tag.name) else picked.add(tag.name) },
                                label = { Text("#${tag.name}") },
                            )
                        }
                    }
                }
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
    onTrash: () -> Unit,
    onRules: () -> Unit,
    onAlbums: () -> Unit,
    onTags: () -> Unit,
    onSettings: () -> Unit,
    trashCount: Int,
    onToggleSearch: () -> Unit,
    onLockNow: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Link", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onCamera) { Text("📷", fontSize = 15.sp) }
        TextButton(onClick = onToggleSearch) { Text("🔍", fontSize = 15.sp) }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", color = Ink, fontSize = 20.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuSection("Library")
                DropdownMenuItem(text = { Text("Albums") }, onClick = { menu = false; onAlbums() })
                DropdownMenuItem(text = { Text("Tags") }, onClick = { menu = false; onTags() })
                DropdownMenuItem(text = { Text("Auto-tag rules") }, onClick = { menu = false; onRules() })
                DropdownMenuItem(
                    text = { Text("Recently deleted" + if (trashCount > 0) "  ($trashCount)" else "") },
                    onClick = { menu = false; onTrash() },
                )

                HorizontalDivider()
                MenuSection("Backup")
                DropdownMenuItem(text = { Text("Export / back up") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Restore from backup") }, onClick = { menu = false; onRestore() })

                HorizontalDivider()
                DropdownMenuItem(text = { Text("Lock now") }, onClick = { menu = false; onLockNow() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
            }
        }
    }
}

private val Muted = Color(0xFF8A969E)

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
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    // The common actions stay on the bar; the rarer ones (leaving the vault) sit
    // behind an overflow so the row doesn't turn into seven cramped buttons.
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF171C20)).padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClose) { Text("✕", color = Ink, fontSize = 16.sp) }
        Text(
            "$count",
            color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        TextButton(onClick = onSelectAll) { Text("All", color = Ink) }
        TextButton(onClick = onTag, enabled = count > 0) { Text("Tag", color = Ink) }
        TextButton(onClick = onAlbum, enabled = count > 0) { Text("Album", color = Ink) }
        TextButton(onClick = onDelete, enabled = count > 0) { Text("Delete", color = Color(0xFFE08A7A)) }
        Box {
            TextButton(onClick = { more = true }, enabled = count > 0) {
                Text("⋮", color = Ink, fontSize = 18.sp)
            }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(
                    text = { Text("Export selection…") },
                    onClick = { more = false; onExport() },
                )
                DropdownMenuItem(
                    text = { Text("Move back to gallery") },
                    onClick = { more = false; onMove() },
                )
            }
        }
    }
}
