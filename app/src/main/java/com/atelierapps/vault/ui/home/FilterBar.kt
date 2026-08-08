package com.atelierapps.vault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
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

/**
 * The filter bar (spec §7, §15.2): a horizontal chip row above the grid.
 * All · source chips (dot + label + count) · tag chips · Date. Multi-select;
 * see [MediaFilter] for the AND/OR semantics.
 */
@Composable
fun FilterBar(
    filter: MediaFilter,
    sources: List<SourceChip>,
    tags: List<TagEntity>,
    onToggleSource: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onSetDate: (DateBucket) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.background(Color(0xFF0E1113)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item {
            FilterChip(
                selected = filter.isEmpty,
                onClick = onClearAll,
                label = { Text("All") },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        items(sources, key = { "src:${it.pkg}" }) { s ->
            FilterChip(
                selected = s.pkg in filter.sources,
                onClick = { onToggleSource(s.pkg) },
                leadingIcon = {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(s.colorArgb)))
                },
                label = { Text("${s.label} · ${s.count}") },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        items(tags, key = { "tag:${it.id}" }) { t ->
            FilterChip(
                selected = t.name in filter.tagNames,
                onClick = { onToggleTag(t.name) },
                label = { Text("#${t.name}") },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        item { DateChip(filter.date, onSetDate) }
    }
}



@Composable
private fun DateChip(current: DateBucket, onSetDate: (DateBucket) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != DateBucket.ANY,
            onClick = { expanded = true },
            label = { Text(if (current == DateBucket.ANY) "Date" else current.label) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DateBucket.entries.forEach { bucket ->
                DropdownMenuItem(
                    text = { Text(bucket.label) },
                    onClick = { onSetDate(bucket); expanded = false },
                )
            }
        }
    }
}
