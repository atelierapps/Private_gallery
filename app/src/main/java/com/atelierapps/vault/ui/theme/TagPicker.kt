package com.atelierapps.vault.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.session.TagPrefs

/**
 * Every tag you have, findable — in a box that cannot run out of room.
 *
 * With a few dozen tags a plain chip cloud worked. With hundreds it stopped
 * working, and the failure was quiet: you could only apply a tag you could
 * remember well enough to scroll to, so in practice one or two got used and the
 * rest were created once and never seen again. So:
 *
 *  - **One field that both finds and adds.** Typing narrows the chips to
 *    matches (prefix matches first); anything typed that isn't an existing tag
 *    is created on save, and the field says so. It used to be a "new tag" box
 *    that searched nothing, sitting under a list it couldn't filter.
 *  - **An order you can see and choose:** Recent (what you applied lately,
 *    then the rest by use), Most used, A–Z. Remembered between uses.
 *  - **Counts on every chip**, so "most used" is visible rather than implied.
 *  - **What the item already has, first**, when tagging one item. Captured
 *    once when the picker opens, so chips don't jump under your finger.
 *
 * No cap, still: a cap is invisible, and with two dozen tags you simply don't
 * find the one you want. The chips scroll inside a bounded height because
 * Material's `AlertDialog` gives its body `weight(1f, fill = false)` and no
 * scroll of its own, so a tall chip area is quietly cut off at the dialog edge.
 *
 * [maxHeight] `null` means the caller is already inside a vertical scroll and
 * will do the scrolling itself; nesting two would fight for the same drag.
 * [compact] is a single sideways row for tight spots (the import bar).
 * [allowCreate] false makes the field search-only (the filter dialog).
 *
 * [text] may hold several comma-separated names; only the part after the last
 * comma is used as the search, and picking a chip clears that part.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(
    tags: List<TagEntity>,
    isPicked: (String) -> Boolean,
    onToggle: (String) -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = 240.dp,
    compact: Boolean = false,
    allowCreate: Boolean = true,
    /** Heading for what was already picked when the picker opened. */
    pickedLabel: String = "On this item",
) {
    val context = LocalContext.current
    var order by remember { mutableStateOf(TagPrefs.order(context)) }
    val recentIds = remember { TagPrefs.recentIds(context) }
    // Keyed on emptiness so it is taken once the tags have actually loaded,
    // and never again after that.
    val initiallyPicked = remember(tags.isEmpty()) {
        tags.filter { isPicked(it.name) }.mapTo(HashSet()) { it.id }
    }
    // Remembered unconditionally: a remember inside a branch is only safe while
    // the branch never flips, and that is not a property worth relying on.
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    val token = text.substringAfterLast(',').trim()
    val searching = token.isNotEmpty()
    val exact = searching && tags.any { it.name.equals(token, ignoreCase = true) }

    // Picking a chip while searching consumes what was typed, so the field is
    // ready for the next one instead of holding a now-redundant search.
    val toggle: (String) -> Unit = { name ->
        onToggle(name)
        if (searching) {
            val head = text.substringBeforeLast(',', missingDelimiterValue = "")
            onTextChange(if (head.isBlank()) "" else "$head, ")
        }
    }

    val groups: List<Pair<String?, List<TagEntity>>> = remember(tags, order, token, initiallyPicked, pickedLabel) {
        if (searching) {
            val matches = arrangeTags(tags, order, recentIds)
                .filter { it.name.contains(token, ignoreCase = true) }
            val (prefix, rest) = matches.partition { it.name.startsWith(token, ignoreCase = true) }
            listOf(null to (prefix + rest))
        } else {
            val first = tags.filter { it.id in initiallyPicked }
            val others = tags.filter { it.id !in initiallyPicked }
            val out = mutableListOf<Pair<String?, List<TagEntity>>>()
            if (first.isNotEmpty()) out += pickedLabel to arrangeTags(first, order, recentIds)
            if (order == TagPrefs.Order.RECENT) {
                val recentSet = recentIds.toSet()
                val recent = arrangeTags(others.filter { it.id in recentSet }, order, recentIds)
                val rest = arrangeTags(others.filter { it.id !in recentSet }, TagPrefs.Order.MOST_USED, recentIds)
                if (recent.isNotEmpty()) out += "Recently used" to recent
                if (rest.isNotEmpty()) out += (if (out.isEmpty()) null else "All tags") to rest
            } else if (others.isNotEmpty()) {
                out += (if (out.isEmpty()) null else "All tags") to arrangeTags(others, order, recentIds)
            }
            out
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (tags.isNotEmpty() && !compact) {
            OrderRow(order) { order = it; TagPrefs.setOrder(context, it) }
        }

        if (compact) {
            val flat = groups.flatMap { it.second }
            if (flat.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(hScroll),
                ) {
                    flat.forEach { tag -> TagChip(tag, isPicked(tag.name)) { toggle(tag.name) } }
                }
            }
        } else if (tags.isNotEmpty()) {
            val bounds =
                if (maxHeight == null) Modifier
                else Modifier.heightIn(max = maxHeight).verticalScroll(vScroll)
            Column(Modifier.fillMaxWidth().then(bounds)) {
                groups.forEach { (label, list) ->
                    if (label != null) {
                        Text(
                            label.uppercase(),
                            color = Muted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        list.forEach { tag -> TagChip(tag, isPicked(tag.name)) { toggle(tag.name) } }
                    }
                }
                if (searching && groups.all { it.second.isEmpty() } && !allowCreate) {
                    Text("No tag matches “$token”", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                    when {
                        !allowCreate -> "Search tags"
                        tags.isEmpty() -> "New tag(s), comma-separated"
                        else -> "Find or add tags"
                    },
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (allowCreate && searching && !exact) {
            Text(
                "“$token” is new — it will be created when you save.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun OrderRow(current: TagPrefs.Order, onPick: (TagPrefs.Order) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Text("Order", color = Muted, style = MaterialTheme.typography.labelMedium)
        TagPrefs.Order.entries.forEach { o ->
            val on = o == current
            Text(
                o.label,
                color = if (on) Brass else Muted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(o) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun TagChip(tag: TagEntity, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#" + tag.name)
                Text(
                    " " + tag.useCount,
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
}

/**
 * The one ordering rule, shared with the share sheet's quick tags so the two
 * never disagree. `useCount` is the live member count when the list came from
 * [com.atelierapps.vault.data.db.TagDao.observeAll].
 */
fun arrangeTags(tags: List<TagEntity>, order: TagPrefs.Order, recentIds: List<String>): List<TagEntity> {
    val byUse = compareByDescending<TagEntity> { it.useCount }.thenBy { it.name.lowercase() }
    return when (order) {
        TagPrefs.Order.MOST_USED -> tags.sortedWith(byUse)
        TagPrefs.Order.NAME -> tags.sortedBy { it.name.lowercase() }
        TagPrefs.Order.RECENT -> {
            val rank = recentIds.withIndex().associate { (i, id) -> id to i }
            tags.sortedWith(compareBy<TagEntity> { rank[it.id] ?: Int.MAX_VALUE }.then(byUse))
        }
    }
}
