package com.atelierapps.vault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.filter.DateBucket
import com.atelierapps.vault.filter.MediaFilter
import com.atelierapps.vault.filter.MediaTypeFilter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Muted
import androidx.compose.material3.MaterialTheme

/**
 * Compact filter bar (spec §7). Fixed category buttons — Type · Show · Tag ·
 * Date · Sort — that open pickers, plus a removable chip for each *active*
 * selection only. No endless inline list of every tag/source; the tag picker is
 * searchable so it scales to hundreds of tags.
 *
 * "Show" took over the slot that used to be "Source". Almost everything here
 * arrives through the gallery's Download folder, so the source list was one
 * entry long and filtered nothing. The sources are still there, at the bottom
 * of the same menu, for the clips that did come from a share.
 */
@Composable
fun FilterBar(
    filter: MediaFilter,
    sources: List<SourceChip>,
    tags: List<TagEntity>,
    sort: SortOrder,
    onSetType: (MediaTypeFilter) -> Unit,
    onToggleSource: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onSetDate: (DateBucket) -> Unit,
    onToggleFavourites: () -> Unit,
    onToggleUntagged: () -> Unit,
    onToggleShortClips: () -> Unit,
    onSetSort: (SortOrder) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tagPicker by remember { mutableStateOf(false) }

    LazyRow(
        modifier = modifier.background(Bg),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- active selections (only what's chosen) ----
        if (!filter.isEmpty) {
            item { ActiveChip("Clear all") { onClearAll() } }
        }
        if (filter.type != MediaTypeFilter.ALL) {
            item { ActiveChip(if (filter.type == MediaTypeFilter.IMAGE) "Photos" else "Videos") { onSetType(filter.type) } }
        }
        if (filter.favourites) item { ActiveChip("Favourites") { onToggleFavourites() } }
        if (filter.untagged) item { ActiveChip("Untagged") { onToggleUntagged() } }
        if (filter.shortClips) item { ActiveChip("Short clips") { onToggleShortClips() } }
        items(filter.sources.toList(), key = { "src:$it" }) { pkg ->
            ActiveChip(sources.firstOrNull { it.pkg == pkg }?.label ?: pkg) { onToggleSource(pkg) }
        }
        items(filter.tagNames.toList(), key = { "tag:$it" }) { name ->
            ActiveChip("#$name") { onToggleTag(name) }
        }
        if (filter.date != DateBucket.ANY) {
            item { ActiveChip(filter.date.label) { onSetDate(DateBucket.ANY) } }
        }

        // ---- category buttons (always the same small set) ----
        item { TypeMenu(filter.type, onSetType) }
        item {
            ShowMenu(
                filter = filter,
                sources = sources,
                onToggleFavourites = onToggleFavourites,
                onToggleUntagged = onToggleUntagged,
                onToggleShortClips = onToggleShortClips,
                onToggleSource = onToggleSource,
            )
        }
        item { AssistChip(onClick = { tagPicker = true }, label = { Text("Tag ▾") }) }
        item { DateMenu(filter.date, onSetDate) }
        item { SortMenu(sort, onSetSort) }
    }

    if (tagPicker) {
        TagPickerDialog(
            all = tags,
            selected = filter.tagNames,
            onToggle = onToggleTag,
            onDismiss = { tagPicker = false },
        )
    }
}

@Composable
private fun ActiveChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(14.dp)) },
    )
}

@Composable
private fun TypeMenu(current: MediaTypeFilter, onSet: (MediaTypeFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("Type ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                MediaTypeFilter.ALL to "All",
                MediaTypeFilter.IMAGE to "Photos",
                MediaTypeFilter.VIDEO to "Videos",
            ).forEach { (type, label) ->
                DropdownMenuItem(
                    text = { Text((if (type == current) "✓  " else "     ") + label) },
                    onClick = { onSet(type); open = false },
                )
            }
        }
    }
}

/**
 * Toggles stay open on tap, like the source rows always did, so two can be
 * combined ("short clips" AND "untagged") without reopening the menu.
 */
@Composable
private fun ShowMenu(
    filter: MediaFilter,
    sources: List<SourceChip>,
    onToggleFavourites: () -> Unit,
    onToggleUntagged: () -> Unit,
    onToggleShortClips: () -> Unit,
    onToggleSource: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = filter.sources
    Box {
        AssistChip(onClick = { open = true }, label = { Text("Show ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                Triple("Favourites", filter.favourites, onToggleFavourites),
                Triple("Untagged", filter.untagged, onToggleUntagged),
                Triple("Short clips (15s or less)", filter.shortClips, onToggleShortClips),
            ).forEach { (label, on, toggle) ->
                DropdownMenuItem(
                    text = { Text((if (on) "✓  " else "     ") + label) },
                    onClick = toggle,
                )
            }
            if (sources.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "From",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            sources.forEach { s ->
                DropdownMenuItem(
                    onClick = { onToggleSource(s.pkg) },
                    leadingIcon = {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(s.colorArgb)))
                    },
                    text = {
                        Text((if (s.pkg in selected) "✓  " else "     ") + "${s.label} (${s.count})")
                    },
                )
            }
        }
    }
}

@Composable
private fun DateMenu(current: DateBucket, onSet: (DateBucket) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(if (current == DateBucket.ANY) "Date ▾" else current.label) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DateBucket.entries.forEach { bucket ->
                DropdownMenuItem(text = { Text(bucket.label) }, onClick = { onSet(bucket); open = false })
            }
        }
    }
}

@Composable
private fun SortMenu(current: SortOrder, onSet: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("Sort: ${current.label}") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(text = { Text(order.label) }, onClick = { onSet(order); open = false })
            }
        }
    }
}

@Composable
private fun TagPickerDialog(
    all: List<TagEntity>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) all else all.filter { it.name.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by tag") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search tags") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { tag ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(tag.name) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = tag.name in selected, onCheckedChange = { onToggle(tag.name) })
                            Text("#${tag.name}", modifier = Modifier.weight(1f))
                            Text("${tag.useCount}", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
