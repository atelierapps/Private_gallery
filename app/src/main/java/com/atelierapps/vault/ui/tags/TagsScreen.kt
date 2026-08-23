package com.atelierapps.vault.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

private val Bg = Color(0xFF0E1113)
private val Ink = Color(0xFFE9EEF0)
private val Muted = Color(0xFF8A969E)
private val Brass = Color(0xFFD8B463)
private val Danger = Color(0xFFE08A7A)

@Composable
fun TagsScreen(vm: TagsViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val tags by vm.tags.collectAsState()
    val error by vm.error.collectAsState()

    var renaming by remember { mutableStateOf<TagUsage?>(null) }
    var deleting by remember { mutableStateOf<TagUsage?>(null) }
    var merging by remember { mutableStateOf<TagUsage?>(null) }

    Column(modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("✕", color = Ink, fontSize = 16.sp) }
            Text(
                "Tags",
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
        Text(
            "Counts are live items. Deleting a tag keeps the items — it only removes the label.",
            color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (tags.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No tags yet.", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
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
                    HorizontalDivider(color = Color(0xFF20272C))
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
            Text("#${tag.name}", color = Ink, fontSize = 15.sp)
            Text("${tag.liveCount} item(s)", color = Muted, fontSize = 12.sp)
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", color = Ink, fontSize = 18.sp) }
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
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    candidates.forEach { t ->
                        Text(
                            "#${t.name}",
                            color = Ink, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 10.dp),
                        )
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
