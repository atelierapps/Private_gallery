package com.atelierapps.vault.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.atelierapps.vault.data.entity.TagEntity

/**
 * Every tag you have, pickable, in a box that cannot run out of room.
 *
 * Four dialogs had each grown their own chip row, and two of them capped the
 * list at twelve. A cap is invisible: with two dozen tags you simply don't find
 * the one you want, and nothing on screen says the rest exist — so the honest
 * reading is that the tag was lost. The clip on top of that was Material's
 * doing: `AlertDialog` gives its body `weight(1f, fill = false)` and no scroll
 * of its own, so a tall chip row is quietly cut off at the dialog's edge.
 *
 * Hence: no cap, and the row scrolls inside a bounded height that the dialog can
 * always afford. Ordering comes from the query — most-used first — so the tags
 * you reach for are the ones you don't have to scroll to.
 *
 * [maxHeight] `null` means the caller is already inside a vertical scroll and
 * will do the scrolling itself; nesting two would fight for the same drag.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(
    tags: List<TagEntity>,
    isPicked: (String) -> Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = 240.dp,
) {
    if (tags.isEmpty()) return
    // Remembered unconditionally: a remember inside a branch is only safe while
    // the branch never flips, and that is not a property worth relying on.
    val scroll = rememberScrollState()
    val bounds =
        if (maxHeight == null) Modifier
        else Modifier.heightIn(max = maxHeight).verticalScroll(scroll)
    FlowRow(
        modifier = modifier.fillMaxWidth().then(bounds),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tags.forEach { tag ->
            val on = isPicked(tag.name)
            FilterChip(
                selected = on,
                onClick = { onToggle(tag.name) },
                label = { Text("#" + tag.name) },
            )
        }
    }
}
