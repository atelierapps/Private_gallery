package com.atelierapps.vault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Danger
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.VaultActionChip
import com.atelierapps.vault.ui.theme.VaultChip

/**
 * Views: the home screen's shortcuts to a question you ask often.
 *
 * Its own row above the filters rather than more chips in theirs, because the
 * two do different things — a filter narrows what's on screen, a view swaps
 * the whole set of filters and the order in one tap — and the filter row was
 * already wider than the screen.
 *
 * "Continue watching" only appears once something is partway through, so the
 * row is empty on a fresh install instead of offering a view of nothing. Your
 * own views always show, with their counts, even at zero: one you made and
 * can't find is worse than one that says it's empty.
 */
@Composable
fun ViewsRow(
    views: List<SavedView>,
    counts: Map<String, Int>,
    activeId: String?,
    canSave: Boolean,
    onApply: (SavedView) -> Unit,
    onSave: () -> Unit,
    onEdit: (SavedView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = views.filter { !it.builtIn || it.id == activeId || (counts[it.id] ?: 0) > 0 }
    val active = shown.firstOrNull { it.id == activeId }
    if (shown.isEmpty() && !canSave) return
    LazyRow(
        modifier = modifier.background(Bg),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The two actions lead the row. At the end they sat past the right
        // edge, behind three views, where nobody ever scrolled to find them.
        if (canSave) {
            item(key = "view:save") { VaultActionChip("+ Save view", onClick = onSave) }
        }
        if (active != null && !active.builtIn) {
            item(key = "view:edit") { VaultActionChip("Edit view", onClick = { onEdit(active) }) }
        }
        items(shown, key = { "view:" + it.id }) { view ->
            VaultChip(
                label = view.name,
                selected = view.id == activeId,
                onClick = { onApply(view) },
                count = counts[view.id],
            )
        }
    }
}

/** Name the current filters; shows what will be kept so there are no surprises. */
@Composable
fun SaveViewDialog(summary: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save view") },
        text = {
            Column {
                Text(summary, color = Muted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("View name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text(
                    "It updates itself: anything added later that fits shows up in it.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save", color = Brass) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Rename or delete a view of your own. To change what it shows, change the
 * filters and save it again: the dialog says so rather than hiding a second
 * editor in here.
 */
@Composable
fun EditViewDialog(
    view: SavedView,
    summary: String,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(view.id) { mutableStateOf(view.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmDelete) "Delete “" + view.name + "”?" else "Edit view") },
        text = {
            if (confirmDelete) {
                Text(
                    "Only the view goes. Nothing in it is deleted, tagged or moved.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column {
                    Text(summary, color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Text(
                        "To change what it shows, adjust the filters and save a new view.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Delete view", color = Danger)
                    }
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                TextButton(onClick = onDelete) { Text("Delete", color = Danger) }
            } else {
                TextButton(
                    onClick = { onRename(name) },
                    enabled = name.isNotBlank() && name.trim() != view.name,
                ) { Text("Rename", color = Brass) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) confirmDelete = false else onDismiss() }) {
                Text(if (confirmDelete) "Back" else "Close")
            }
        },
    )
}
