package com.atelierapps.vault.ui.duplicates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.ui.image.VaultMediaKey
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.ScreenCaption
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.Scrim
import com.atelierapps.vault.ui.theme.Surface
import com.atelierapps.vault.ui.theme.SurfaceHigh
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Byte-identical copies, grouped, with one already chosen to keep.
 *
 * The work is picking which copy survives, so that is the only thing this
 * screen asks: every group arrives with the oldest already marked Keep, and
 * doing nothing but pressing Delete is the right answer almost every time.
 * Tapping another copy moves the mark; a group you'd rather not touch can be
 * left alone entirely.
 */
@Composable
fun DuplicatesScreen(
    vm: DuplicatesViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups by vm.groups.collectAsState()
    val working by vm.working.collectAsState()
    var confirm by remember { mutableStateOf(false) }

    val extras = groups.filterNot { it.keepAll }.sumOf { it.extras.size }
    val reclaimable = groups.sumOf { it.reclaimable }

    Box(modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Duplicates", onClose)

            if (groups.isEmpty()) {
                ScreenCaption("Nothing here is stored twice.")
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No duplicates.",
                        color = Muted,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                ScreenCaption(
                    "${groups.size} set(s) of identical files. The oldest copy in each is " +
                        "kept — tap another to keep that one instead.",
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(groups, key = { it.hash }) { group ->
                        GroupRow(
                            group = group,
                            onKeep = { id -> vm.chooseKeeper(group.hash, id) },
                            onToggleKeepAll = { vm.toggleKeepAll(group.hash) },
                        )
                    }
                }

                Column(Modifier.fillMaxWidth().background(Surface).padding(14.dp)) {
                    Text(
                        if (extras == 0) "Nothing selected"
                        else "$extras extra copies · ${formatBytes(reclaimable)} to reclaim",
                        color = Ink,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "They go to Recently deleted, so you keep 30 days to change your mind.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                    )
                    Button(
                        onClick = { confirm = true },
                        enabled = extras > 0 && !working,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete the extras") }
                }
            }
        }

        if (working) {
            Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brass)
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete $extras extra copies?") },
            text = {
                Text(
                    "One copy of each is kept. The rest move to Recently deleted, where " +
                        "you can restore them for 30 days.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.deleteExtras() }) {
                    Text("Delete", color = Brass)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GroupRow(
    group: DuplicateGroup,
    onKeep: (String) -> Unit,
    onToggleKeepAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${group.copies.size} copies · ${formatBytes(group.copies.first().media.sizeBytes)} each",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleKeepAll) {
                Text(
                    if (group.keepAll) "Keeping all" else "Keep all",
                    color = if (group.keepAll) Brass else Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
        ) {
            group.copies.forEach { item ->
                Copy(
                    item = item,
                    keeper = item.media.id == group.keeperId,
                    dimmed = !group.keepAll && item.media.id != group.keeperId,
                    onClick = { onKeep(item.media.id) },
                )
            }
        }
    }
}

@Composable
private fun Copy(
    item: MediaWithTags,
    keeper: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)).background(SurfaceHigh)
                .clickable(onClick = onClick)
                .then(if (keeper) Modifier.border(2.5.dp, Brass, RoundedCornerShape(6.dp)) else Modifier),
        ) {
            AsyncImage(
                model = VaultMediaKey(item.media.id, full = false),
                contentDescription = item.media.originalName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // The ones on their way out are shaded, so which copies survive is
            // legible from across the room rather than needing a label read.
            if (dimmed) Box(Modifier.fillMaxSize().background(Scrim))
            if (keeper) {
                Text(
                    "KEEP",
                    color = BrassInk,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Brass)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            addedOn(item.media.importedAtMillis),
            color = if (keeper) Ink else Muted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** When it landed here — the only thing that actually tells two copies apart. */
private fun addedOn(millis: Long): String =
    SimpleDateFormat("d MMM yy", Locale.getDefault()).format(Date(millis))

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes < kb -> "$bytes B"
        bytes < kb * kb -> "%.0f KB".format(bytes / kb)
        bytes < kb * kb * kb -> "%.1f MB".format(bytes / (kb * kb))
        else -> "%.2f GB".format(bytes / (kb * kb * kb))
    }
}
