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

@Composable
fun TrashScreen(vm: TrashViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val items by vm.items.collectAsState()
    val working by vm.working.collectAsState()

    var selected by remember { mutableStateOf<MediaWithTags?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Color(0xFF0E1113))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VaultIconButton(Icons.Outlined.Close, "Close", onClose)
                Text(
                    "Recently deleted",
                    color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                if (items.isNotEmpty()) {
                    TextButton(onClick = { confirmEmpty = true }) { Text("Empty", color = Danger) }
                }
            }
            Text(
                "Items are kept for 30 days, then deleted forever.",
                color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "The bin is empty.",
                        color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
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
                        TrashTile(item, onClick = { selected = item })
                    }
                }
            }
        }

        if (working) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC06080A)),
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

@Composable
private fun TrashTile(item: MediaWithTags, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF1B2126)).clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = VaultMediaKey(item.media.id, full = false),
            contentDescription = item.media.originalName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val days = daysLeft(item.media.deletedAtMillis)
        Text(
            if (days <= 0) "Today" else "${days}d",
            color = Color.White, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0x9906080A))
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
        title = { Text(item.media.originalName, fontSize = 15.sp) },
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
