package com.atelierapps.vault.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelierapps.vault.ui.grid.VaultGridScreen
import com.atelierapps.vault.ui.viewer.ViewerSession
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Danger
import com.atelierapps.vault.ui.theme.Ink
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import com.atelierapps.vault.ui.theme.VaultIconButton
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.HeaderAction
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Scrim
import com.atelierapps.vault.ui.theme.SurfaceHigh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import com.atelierapps.vault.ui.home.SortOrder
import com.atelierapps.vault.ui.theme.Hairline
import com.atelierapps.vault.ui.theme.Muted

@Composable
fun AlbumScreen(
    vm: AlbumViewModel,
    albumName: String,
    onOpen: (id: String) -> Unit,
    onExport: (Set<String>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media by vm.media.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val working by vm.working.collectAsState()
    val query by vm.query.collectAsState()
    val sort by vm.sort.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    // Same rule as the main grid: back leaves the selection before it leaves
    // the album.
    BackHandler(enabled = selectionMode || searchOpen) {
        when {
            selectionMode -> vm.clearSelection()
            else -> { searchOpen = false; vm.setQuery("") }
        }
    }

    Box(modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                Row(
                    Modifier.fillMaxWidth().background(SurfaceHigh).padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VaultIconButton(Icons.Outlined.Close, "Close", vm::clearSelection)
                    Text(
                        "${selectedIds.size}",
                        color = Ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    TextButton(onClick = vm::selectAll) { Text("All", color = Ink) }
                    // Only meaningful for exactly one item, so it's enabled then.
                    TextButton(
                        onClick = vm::setCoverFromSelection,
                        enabled = selectedIds.size == 1,
                    ) { Text("Cover", color = Ink) }
                    TextButton(onClick = vm::removeSelected, enabled = selectedIds.isNotEmpty()) {
                        Text("Remove", color = Brass)
                    }
                    TextButton(onClick = { confirmDelete = true }, enabled = selectedIds.isNotEmpty()) {
                        Text("Delete", color = Danger)
                    }
                }
            } else {
                ScreenHeader(
                    title = albumName,
                    onBack = onClose,
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    backDescription = "Back",
                ) {
                    // onClick is the third parameter, not the last, so this
                    // cannot take a trailing lambda — that binds to `size`.
                    VaultIconButton(
                        Icons.Outlined.Search,
                        "Search this album",
                        {
                            searchOpen = !searchOpen
                            if (!searchOpen) vm.setQuery("")
                        },
                    )
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        VaultIconButton(Icons.Outlined.MoreVert, "More", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            order.label,
                                            color = if (order == sort) Brass else Ink,
                                        )
                                    },
                                    onClick = { menu = false; vm.setSort(order) },
                                )
                            }
                            HorizontalDivider(color = Hairline)
                            DropdownMenuItem(
                                text = { Text("Export album", color = Ink) },
                                onClick = { menu = false; onExport(media.map { it.media.id }.toSet()) },
                            )
                        }
                    }
                }
                if (searchOpen) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text("Search this album", color = Muted) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }

            VaultGridScreen(
                media = media,
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                onOpen = { id ->
                    ViewerSession.orderedIds = media.map { it.media.id }
                    onOpen(id)
                },
                onLongPress = vm::longPress,
                onToggleSelect = vm::toggleSelection,
                modifier = Modifier.weight(1f),
            )
        }

        if (working) {
            Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brass)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selectedIds.size} item(s)?") },
            text = { Text("They move to Recently deleted. Removing from the album instead keeps them in your library.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) {
                    Text("Delete", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
