package com.atelierapps.vault.ui.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.ui.image.VaultMediaKey
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.SurfaceHigh
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.atelierapps.vault.session.TileAnchor
import com.atelierapps.vault.session.AppDisguise
import androidx.compose.ui.platform.LocalContext

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
    initialColumns: Int = 3,
    onImport: (() -> Unit)? = null,
    onCamera: (() -> Unit)? = null,
) {
    if (media.isEmpty()) {
        EmptyState(modifier, onImport, onCamera)
        return
    }
    // Pinch to change column count (2–6). Two-finger only, so single-finger
    // scrolling is untouched.
    var columns by remember(initialColumns) { mutableIntStateOf(initialColumns) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize().background(Bg)
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
        color = Muted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 18.dp, bottom = 6.dp),
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

    // Held outside snapshot state on purpose: this is written on every layout
    // pass while the grid scrolls, and routing it through mutableStateOf would
    // recompose every visible tile for a value only the click handler reads.
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }

    // Thumbnails arrive as their decryption finishes, so they land at genuinely
    // different moments; fading each one in on arrival turns that into a stagger
    // instead of a grid of tiles popping.
    var decoded by remember(id) { mutableStateOf(false) }
    val thumbAlpha by animateFloatAsState(
        targetValue = if (decoded) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = LinearOutSlowInEasing),
        label = "thumbFade",
    )

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(SurfaceHigh)
            .onGloballyPositioned { coords[0] = it }
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onToggleSelect(id)
                    } else {
                        coords[0]?.takeIf { it.isAttached }?.let { TileAnchor.record(it.boundsInWindow()) }
                        onOpen(id)
                    }
                },
                onLongClick = { onLongPress(id) },
            )
            .then(if (selected) Modifier.border(2.5.dp, Brass, RoundedCornerShape(2.dp)) else Modifier),
    ) {
        AsyncImage(
            model = VaultMediaKey(id, full = false),
            contentDescription = item.media.originalName,
            contentScale = ContentScale.Crop,
            onSuccess = { decoded = true },
            onError = { decoded = true },
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = thumbAlpha },
        )
        item.media.durationMillis?.let { duration ->
            Text(
                text = formatDuration(duration),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xAA06080A))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        if (item.media.isPinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = "Pinned",
                tint = Ink,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(13.dp),
            )
        }
        if (selectionMode) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp).clip(CircleShape)
                    .background(if (selected) Brass else Color(0x8806080A)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = BrassInk,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

/**
 * First run. Rather than a dead-end sentence, this names the three ways media
 * gets in and makes two of them tappable right here — the third (sharing from
 * another app) can't be triggered from inside the vault, so it's explained.
 */
@Composable
private fun EmptyState(modifier: Modifier, onImport: (() -> Unit)?, onCamera: (() -> Unit)?) {
    Box(
        modifier.fillMaxSize().background(Bg).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Nothing here yet",
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Everything you add is encrypted on this device.",
                color = Muted, fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
            )
            if (onImport != null) {
                EmptyAction("Import from your gallery", "Pick photos and videos already on this phone", onImport)
            }
            if (onCamera != null) {
                EmptyAction("Take a photo or video", "Captured straight here — never touches the gallery", onCamera)
            }
            Text(
                "Or share media to ${AppDisguise.currentLabel(LocalContext.current)} from any other app.",
                color = Muted, fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun EmptyAction(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, color = Brass, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
