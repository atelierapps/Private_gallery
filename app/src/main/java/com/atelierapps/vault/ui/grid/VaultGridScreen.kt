package com.atelierapps.vault.ui.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.ui.image.VaultMediaKey

/**
 * The vault grid — home base (spec §8, §15.2). Three columns, decrypting Coil
 * thumbnails, video badge + duration. The filter bar (§7) and viewer (§8) are
 * later steps; tiles report taps via [onOpen] for the future viewer.
 */
@Composable
fun VaultGridScreen(
    media: List<MediaWithTags>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onOpen: (id: String) -> Unit,
    onLongPress: (id: String) -> Unit,
    onToggleSelect: (id: String) -> Unit,
    modifier: Modifier = Modifier,
    showSectionHeaders: Boolean = false,
) {
    if (media.isEmpty()) {
        EmptyState(modifier)
        return
    }
    // Pinch to change column count (2–6). Two-finger only, so single-finger
    // scrolling is untouched.
    var columns by remember { mutableIntStateOf(3) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize().background(Color(0xFF0E1113))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var lastDistance = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size < 2) {
                            if (pressed.isEmpty()) break else { lastDistance = 0f; continue }
                        }
                        val distance = (pressed[0].position - pressed[1].position).getDistance()
                        if (lastDistance != 0f) {
                            val delta = distance - lastDistance
                            if (delta > 80f) { columns = (columns - 1).coerceAtLeast(2); lastDistance = distance }
                            else if (delta < -80f) { columns = (columns + 1).coerceAtMost(6); lastDistance = distance }
                        } else {
                            lastDistance = distance
                        }
                        pressed.forEach { it.consume() } // claim the pinch so the grid doesn't scroll
                    }
                }
            },
        contentPadding = PaddingValues(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val tile: @Composable (MediaWithTags) -> Unit = { item ->
            MediaTile(
                item = item,
                selectionMode = selectionMode,
                selected = item.media.id in selectedIds,
                onOpen = onOpen,
                onLongPress = onLongPress,
                onToggleSelect = onToggleSelect,
            )
        }
        if (showSectionHeaders) {
            val now = System.currentTimeMillis()
            sectionize(media, now).forEachIndexed { idx, section ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "hdr:$idx:${section.title}") {
                    SectionHeader(section.title)
                }
                items(section.items, key = { it.media.id }) { tile(it) }
            }
        } else {
            items(media, key = { it.media.id }) { tile(it) }
        }
    }
}

private data class GridSection(val title: String, val items: List<MediaWithTags>)

/** Group the already-sorted list into a Pinned run + rolling date buckets. */
private fun sectionize(media: List<MediaWithTags>, now: Long): List<GridSection> {
    val out = ArrayList<GridSection>()
    var key: String? = null
    var cur = ArrayList<MediaWithTags>()
    for (m in media) {
        val k = if (m.media.isPinned) "Pinned" else dateBucket(m.media.dateTakenMillis, now)
        if (k != key) {
            if (cur.isNotEmpty()) out.add(GridSection(key!!, cur))
            cur = ArrayList()
            key = k
        }
        cur.add(m)
    }
    if (cur.isNotEmpty()) out.add(GridSection(key!!, cur))
    return out
}

private fun dateBucket(millis: Long, now: Long): String {
    val age = now - millis
    val day = 24L * 60 * 60 * 1000
    return when {
        age < day -> "Today"
        age < 2 * day -> "Yesterday"
        age < 7 * day -> "This week"
        age < 30 * day -> "This month"
        age < 365 * day -> "This year"
        else -> "Older"
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        color = Color(0xFFBFC8CE),
        fontSize = 13.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: MediaWithTags,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
) {
    val id = item.media.id
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF1B2126))
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect(id) else onOpen(id) },
                onLongClick = { onLongPress(id) },
            )
            .then(if (selected) Modifier.border(2.5.dp, Color(0xFFD8B463), RoundedCornerShape(2.dp)) else Modifier),
    ) {
        AsyncImage(
            model = VaultMediaKey(id, full = false),
            contentDescription = item.media.originalName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        item.media.durationMillis?.let { duration ->
            Text(
                text = formatDuration(duration),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x9906080A))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (item.media.isPinned) {
            Text(
                "📌", fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
            )
        }
        if (selectionMode) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp).clip(CircleShape)
                    .background(if (selected) Color(0xFFD8B463) else Color(0x8806080A)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Text("✓", color = Color(0xFF1A1509), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(
        modifier.fillMaxSize().background(Color(0xFF0E1113)).padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Nothing saved yet.\nShare a photo or video to Link to get started.",
            color = Color(0xFF8A969E),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
