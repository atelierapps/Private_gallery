package com.atelierapps.vault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.filter.DateBucket
import com.atelierapps.vault.filter.MediaFilter
import com.atelierapps.vault.filter.MediaTypeFilter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.TagPicker
import androidx.compose.material3.MaterialTheme

/**
 * Compact filter bar (spec §7). Three one-tap toggles — Favourites · Untagged ·
 * Short — then category buttons — Type · Tag · Date · Sort — that open pickers,
 * plus a removable chip for each other *active* selection. No endless inline
 * list of every tag/source; the tag picker is searchable so it scales.
 *
 * The toggles were a "Show" menu for one build. A menu is two taps for a thing
 * used constantly, and combining two meant opening it twice; as chips they are
 * one tap each and show their own state.
 *
 * There is no Source button: almost everything here arrives through the
 * gallery's Download folder, so its list was one entry long and filtered
 * nothing. The sources live at the bottom of the Type menu instead.
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
        items(filter.sources.toList(), key = { "src:$it" }) { pkg ->
            ActiveChip(sources.firstOrNull { it.pkg == pkg }?.label ?: pkg) { onToggleSource(pkg) }
        }
        items(filter.tagNames.toList(), key = { "tag:$it" }) { name ->
            ActiveChip("#$name") { onToggleTag(name) }
        }
        if (filter.date != DateBucket.ANY) {
            item { ActiveChip(filter.date.label) { onSetDate(DateBucket.ANY) } }
        }

        // ---- one-tap toggles: they show their own on/off, so no active chip ----
        item { ToggleChip("Favourites", filter.favourites, onToggleFavourites) }
        item { ToggleChip("Untagged", filter.untagged, onToggleUntagged) }
        item { ToggleChip("Short (≤15s)", filter.shortClips, onToggleShortClips) }

        // ---- category buttons (always the same small set) ----
        item { TypeMenu(filter.type, sources, filter.sources, onSetType, onToggleSource) }
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
private fun ToggleChip(label: String, on: Boolean, onToggle: () -> Unit) {
    FilterChip(selected = on, onClick = onToggle, label = { Text(label) })
}

@Composable
private fun TypeMenu(
    current: MediaTypeFilter,
    sources: List<SourceChip>,
    selectedSources: Set<String>,
    onSet: (MediaTypeFilter) -> Unit,
    onToggleSource: (String) -> Unit,
) {
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
            // Where things came from. Stays open on tap so several can be picked.
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
                        Text((if (s.pkg in selectedSources) "✓  " else "     ") + "${s.label} (${s.count})")
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by tag") },
        text = {
            TagPicker(
                tags = all,
                isPicked = { it in selected },
                onToggle = onToggle,
                text = query,
                onTextChange = { query = it },
                maxHeight = 320.dp,
                allowCreate = false,
                pickedLabel = "Filtering by",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
