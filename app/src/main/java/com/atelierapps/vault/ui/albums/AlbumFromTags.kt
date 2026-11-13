package com.atelierapps.vault.ui.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.TagPicker
import com.atelierapps.vault.ui.theme.VaultChip

/**
 * What a set of tags would bring into an album. An item belongs to at most
 * one album, so the ones already in another album are counted apart: moving
 * them would quietly empty that album, which is the user's call, not ours.
 */
data class TagMatch(
    /** In no album yet: these go in. */
    val free: List<String>,
    /** In some other album: only moved if asked. */
    val elsewhere: List<String>,
    /** Already in this album. */
    val already: Int,
)

fun matchTags(
    library: List<MediaWithTags>,
    tagNames: Collection<String>,
    requireAll: Boolean,
    albumId: String?,
): TagMatch {
    if (tagNames.isEmpty()) return TagMatch(emptyList(), emptyList(), 0)
    val wanted = tagNames.map { it.lowercase() }.toSet()
    val hits = library.filter { item ->
        val have = item.tags.mapTo(HashSet()) { it.name.lowercase() }
        if (requireAll) have.containsAll(wanted) else have.any { it in wanted }
    }
    return TagMatch(
        free = hits.filter { it.media.albumId == null }.map { it.media.id },
        elsewhere = hits.filter { it.media.albumId != null && it.media.albumId != albumId }.map { it.media.id },
        already = hits.count { albumId != null && it.media.albumId == albumId },
    )
}

/**
 * Fill an album from tags: pick a few, see how many items that brings, and
 * create (or add). Weeding out the ones that don't belong is then the album
 * screen's usual select → Remove, which only takes them out of the album.
 *
 * Also the plain "New album" dialog: with no tags picked it just makes an
 * empty album, as it always did.
 *
 * @param askName true for a new album; false when adding to [albumId].
 */
@Composable
fun AlbumFromTagsDialog(
    title: String,
    askName: Boolean,
    albumId: String?,
    tags: List<TagEntity>,
    library: List<MediaWithTags>,
    onConfirm: (name: String, ids: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var requireAll by remember { mutableStateOf(false) }
    var moveOthers by remember { mutableStateOf(false) }
    val match = remember(library, picked.toList(), requireAll, albumId) {
        matchTags(library, picked, requireAll, albumId)
    }
    val ids = match.free + if (moveOthers) match.elsewhere else emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Scrolls itself: AlertDialog clips a tall body rather than
            // scrolling it (CLAUDE.md), and the tag list can be long.
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (askName) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Album name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    if (askName) "Fill it from tags (optional)" else "Add everything with",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = if (askName) 14.dp else 0.dp, bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VaultChip(label = "Any of these tags", selected = !requireAll, onClick = { requireAll = false })
                    VaultChip(label = "All of them", selected = requireAll, onClick = { requireAll = true })
                }
                TagPicker(
                    tags = tags,
                    isPicked = { n -> picked.any { it.equals(n, ignoreCase = true) } },
                    onToggle = { n ->
                        if (picked.any { it.equals(n, ignoreCase = true) }) {
                            picked.removeAll { it.equals(n, ignoreCase = true) }
                        } else {
                            picked.add(n)
                        }
                    },
                    text = query,
                    onTextChange = { query = it },
                    modifier = Modifier.padding(top = 8.dp),
                    // The column above scrolls; the picker mustn't as well.
                    maxHeight = null,
                    allowCreate = false,
                    pickedLabel = "Using",
                )
                if (picked.isNotEmpty()) {
                    Text(
                        when (match.free.size) {
                            0 -> "Nothing new to add"
                            1 -> "1 item will be added"
                            else -> "${match.free.size} items will be added"
                        } + if (match.already > 0) " · ${match.already} already here" else "",
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (match.elsewhere.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        ) {
                            Text(
                                "Also move ${match.elsewhere.size} from other albums",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = moveOthers,
                                onCheckedChange = { moveOthers = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = BrassInk, checkedTrackColor = Brass),
                            )
                        }
                    }
                    Text(
                        "Anything that doesn't belong: select it in the album and Remove — it stays in your library.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), ids) },
                enabled = if (askName) name.isNotBlank() else ids.isNotEmpty(),
            ) {
                Text(
                    when {
                        !askName -> "Add " + ids.size
                        ids.isEmpty() -> "Create"
                        else -> "Create with " + ids.size
                    },
                    color = Brass,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
