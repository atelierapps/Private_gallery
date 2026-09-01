package com.atelierapps.vault.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelierapps.vault.data.db.TagUsage
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Danger
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import com.atelierapps.vault.ui.theme.VaultIconButton
import androidx.compose.material.icons.outlined.MoreVert
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.ScreenCaption
import com.atelierapps.vault.ui.theme.Hairline
import androidx.compose.material3.MaterialTheme

@Composable
fun TagsScreen(vm: TagsViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val tags by vm.tags.collectAsState()
    val error by vm.error.collectAsState()

    var renaming by remember { mutableStateOf<TagUsage?>(null) }
    var deleting by remember { mutableStateOf<TagUsage?>(null) }
    var merging by remember { mutableStateOf<TagUsage?>(null) }

    Column(modifier.fillMaxSize().background(Bg)) {
        ScreenHeader("Tags", onClose)
        ScreenCaption("Counts are live items. Deleting a tag keeps the items — it only removes the label.")

        if (tags.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No tags yet.", color = Muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(tags, key = { it.id }) { tag ->
                    TagRow(
                        tag = tag,
                        onRename = { renaming = tag },
                        onMerge = { merging = tag },
                        onDelete = { deleting = tag },
                    )
                    HorizontalDivider(color = Hairline)
                }
            }
        }
    }

    renaming?.let { tag ->
        RenameDialog(
            initial = tag.name,
            onConfirm = { newName -> vm.rename(tag.id, newName); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    merging?.let { tag ->
        MergeDialog(
            source = tag,
            candidates = tags.filter { it.id != tag.id },
            onPick = { target -> vm.merge(tag.id, target.id); merging = null },
            onDismiss = { merging = null },
        )
    }

    deleting?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${tag.name}”?") },
            text = { Text("The label is removed from ${tag.liveCount} item(s). The items stay in your library.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(tag.id); deleting = null }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (error != null) {
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Name already taken") },
            text = { Text(error!!) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK", color = Brass) } },
        )
    }
}

@Composable
private fun TagRow(tag: TagUsage, onRename: () -> Unit, onMerge: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(12.dp).clip(CircleShape)
                .background(runCatching { Color(android.graphics.Color.parseColor(tag.colorHex)) }.getOrDefault(Brass)),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("#${tag.name}", color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text("${tag.liveCount} item(s)", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Box {
            VaultIconButton(Icons.Outlined.MoreVert, "More", { menu = true }, size = 36)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Merge into…") }, onClick = { menu = false; onMerge() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun MergeDialog(
    source: TagUsage,
    candidates: List<TagUsage>,
    onPick: (TagUsage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge “${source.name}” into…") },
        text = {
            if (candidates.isEmpty()) {
                Text("There's no other tag to merge into.", color = Muted)
            } else {
                Column {
                    Text(
                        "Its ${source.liveCount} item(s) get the tag you pick, and “${source.name}” is removed.",
                        color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    // Every other tag you have, so this is the list most likely
                    // to outgrow the dialog — and AlertDialog clips rather than
                    // scrolls, which would silently hide half your tags from a
                    // merge that is meant to tidy them up.
                    Column(
                        Modifier.heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        candidates.forEach { t ->
                            Text(
                                "#${t.name}",
                                color = Ink, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename tag") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name.trim() != initial,
            ) { Text("Save", color = Brass) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
