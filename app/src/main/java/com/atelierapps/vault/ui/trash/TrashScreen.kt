package com.atelierapps.vault.ui.trash

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.ui.image.VaultMediaKey
import kotlin.math.max
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Danger
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import com.atelierapps.vault.ui.theme.VaultIconButton
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.ScreenCaption
import androidx.compose.material3.MaterialTheme
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Scrim
import com.atelierapps.vault.ui.theme.ScrimSoft
import com.atelierapps.vault.ui.theme.SurfaceHigh
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Surface

@Composable
fun TrashScreen(vm: TrashViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val items by vm.items.collectAsState()
    val working by vm.working.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()

    var selected by remember { mutableStateOf<MediaWithTags?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf(false) }

    // Same rule as everywhere else: back leaves the selection before the screen.
    BackHandler(enabled = selectionMode) { vm.clearSelection() }

    Box(modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                Row(
                    Modifier.fillMaxWidth().background(Surface)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VaultIconButton(Icons.Outlined.Close, "Clear selection", vm::clearSelection)
                    Text(
                        "${selectedIds.size} selected",
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    VaultIconButton(Icons.Outlined.SelectAll, "Select all", vm::selectAll)
                    VaultIconButton(
                        Icons.Outlined.Restore,
                        "Restore",
                        { vm.restoreSelected() },
                        enabled = selectedIds.isNotEmpty(),
                    )
                    VaultIconButton(
                        Icons.Outlined.DeleteForever,
                        "Delete now",
                        { confirmPurge = true },
                        tint = Danger,
                        enabled = selectedIds.isNotEmpty(),
                    )
                }
            } else {
                ScreenHeader("Recently deleted", onClose) {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) {
                            Text("Empty", color = Danger, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                ScreenCaption(
                    "Items are kept for 30 days, then deleted forever. " +
                        "Long-press to pick several at once.",
                )
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "The bin is empty.",
                        color = Muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(items, key = { it.media.id }) { item ->
                        TrashTile(
                            item = item,
                            selected = item.media.id in selectedIds,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) vm.toggleSelection(item.media.id)
                                else selected = item
                            },
                            onLongClick = { vm.longPress(item.media.id) },
                        )
                    }
                }
            }
        }

        if (working) {
            Box(
                Modifier.fillMaxSize().background(Scrim),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Brass) }
        }
    }

    selected?.let { item ->
        ItemActionDialog(
            item = item,
            onRestore = { vm.restore(item.media.id); selected = null },
            onPurge = { vm.purge(item.media.id); selected = null },
            onDismiss = { selected = null },
        )
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("Delete ${selectedIds.size} item(s) forever?") },
            text = { Text("These leave the disk for good. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmPurge = false; vm.purgeSelected() }) {
                    Text("Delete forever", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the bin?") },
            text = { Text("All ${items.size} item(s) will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; vm.purgeAll() }) {
                    Text("Delete all", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashTile(
    item: MediaWithTags,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp))
            .background(SurfaceHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.border(2.5.dp, Brass, RoundedCornerShape(2.dp)) else Modifier),
    ) {
        AsyncImage(
            model = VaultMediaKey(item.media.id, full = false),
            contentDescription = item.media.originalName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                rotationZ = (item.media.videoRotationDegrees ?: 0).toFloat()
            },
        )
        if (selectionMode) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp).clip(CircleShape)
                    .background(if (selected) Brass else ScrimSoft),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Filled.Check, null, tint = BrassInk, modifier = Modifier.size(13.dp))
                }
            }
        }
        val days = daysLeft(item.media.deletedAtMillis)
        Text(
            if (days <= 0) "Today" else "${days}d",
            color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                .clip(RoundedCornerShape(4.dp)).background(ScrimSoft)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun ItemActionDialog(
    item: MediaWithTags,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onDismiss: () -> Unit,
) {
    val days = daysLeft(item.media.deletedAtMillis)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.media.originalName, style = MaterialTheme.typography.bodyLarge) },
        text = {
            Text(
                if (days <= 0) "Deleted forever soon." else "Deleted forever in $days day(s).",
                color = Muted,
            )
        },
        confirmButton = { TextButton(onClick = onRestore) { Text("Restore", color = Brass) } },
        dismissButton = { TextButton(onClick = onPurge) { Text("Delete now", color = Danger) } },
    )
}

private fun daysLeft(deletedAtMillis: Long?): Int {
    deletedAtMillis ?: return TrashViewModel.RETENTION_MS.toInt()
    val elapsed = System.currentTimeMillis() - deletedAtMillis
    val remaining = TrashViewModel.RETENTION_MS - elapsed
    return max(0, (remaining / (24L * 60 * 60 * 1000)).toInt())
}
