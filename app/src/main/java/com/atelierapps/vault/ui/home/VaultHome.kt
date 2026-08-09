package com.atelierapps.vault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
    modifier: Modifier = Modifier,
    vm: GridViewModel = viewModel(),
) {
    val filter by vm.filter.collectAsState()
    val sources by vm.sourceChips.collectAsState()
    val tags by vm.tagChips.collectAsState()
    val sort by vm.sort.collectAsState()
    val media by vm.media.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val working by vm.working.collectAsState()

    var confirmDelete by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Color(0xFF0E1113))) {
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                SelectionBar(
                    count = selectedIds.size,
                    onClose = vm::clearSelection,
                    onMove = vm::moveSelectedToGallery,
                    onDelete = { confirmDelete = true },
                )
            } else {
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
                onOpen = onOpen,
                onLongPress = vm::startSelection,
                onToggleSelect = vm::toggleSelection,
                modifier = Modifier.weight(1f),
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
            text = { Text("They'll be permanently removed from the vault. This can't be undone.") },
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
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF171C20)).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClose) { Text("✕", color = Ink, fontSize = 16.sp) }
        Text(
            "$count selected",
            color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        TextButton(onClick = onMove, enabled = count > 0) { Text("Move to gallery", color = Brass) }
        TextButton(onClick = onDelete, enabled = count > 0) { Text("Delete", color = Color(0xFFE08A7A)) }
    }
}
