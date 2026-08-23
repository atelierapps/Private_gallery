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

private val Ink = Color(0xFFE9EEF0)
private val Brass = Color(0xFFD8B463)
private val Danger = Color(0xFFE08A7A)

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
    var confirmDelete by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Color(0xFF0E1113))) {
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF171C20)).padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = vm::clearSelection) { Text("✕", color = Ink, fontSize = 16.sp) }
                    Text(
                        "${selectedIds.size}",
                        color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
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
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text("‹", color = Ink, fontSize = 20.sp) }
                    Text(
                        albumName,
                        color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    TextButton(
                        onClick = { onExport(media.map { it.media.id }.toSet()) },
                        enabled = media.isNotEmpty(),
                    ) { Text("Export", color = Brass, fontSize = 13.sp) }
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
                onLongPress = vm::startSelection,
                onToggleSelect = vm::toggleSelection,
                modifier = Modifier.weight(1f),
            )
        }

        if (working) {
            Box(Modifier.fillMaxSize().background(Color(0xCC06080A)), contentAlignment = Alignment.Center) {
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
