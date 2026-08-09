package com.atelierapps.vault.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.atelierapps.vault.ui.image.VaultMediaKey

private val Bg = Color(0xFF0E1113)
private val Ink = Color(0xFFE9EEF0)
private val Muted = Color(0xFF8A969E)
private val Brass = Color(0xFFD8B463)
private val Danger = Color(0xFFE08A7A)

@Composable
fun AlbumsScreen(
    vm: AlbumsViewModel,
    onOpen: (id: String, name: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val albums by vm.albums.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AlbumCard?>(null) }
    var deleting by remember { mutableStateOf<AlbumCard?>(null) }

    Box(modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("✕", color = Ink, fontSize = 16.sp) }
                Text(
                    "Albums",
                    color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            if (albums.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No albums yet.\nTap + to make one, then add items from the grid.",
                        color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(albums, key = { it.album.id }) { card ->
                        AlbumTile(
                            card = card,
                            onOpen = { onOpen(card.album.id, card.album.name) },
                            onRename = { renaming = card },
                            onDelete = { deleting = card },
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreate = true },
            containerColor = Brass,
            contentColor = Color(0xFF1A1509),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Text("+", fontSize = 26.sp) }
    }

    if (showCreate) {
        NameDialog(
            title = "New album",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { vm.create(it); showCreate = false },
            onDismiss = { showCreate = false },
        )
    }
    renaming?.let { card ->
        NameDialog(
            title = "Rename album",
            initial = card.album.name,
            confirmLabel = "Save",
            onConfirm = { vm.rename(card.album.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${card.album.name}”?") },
            text = { Text("The album is removed. Its ${card.count} item(s) stay in your library.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(card.album.id); deleting = null }) {
                    Text("Delete", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AlbumTile(
    card: AlbumCard,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clickable(onClick = onOpen)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1B2126)),
        ) {
            if (card.coverId != null) {
                AsyncImage(
                    model = VaultMediaKey(card.coverId, full = false),
                    contentDescription = card.album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty", color = Muted, fontSize = 13.sp)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(card.album.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("${card.count} item(s)", color = Muted, fontSize = 12.sp)
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", color = Ink, fontSize = 18.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Album name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirmLabel, color = Brass) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
