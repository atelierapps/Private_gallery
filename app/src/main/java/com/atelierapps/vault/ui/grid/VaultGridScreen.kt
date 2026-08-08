package com.atelierapps.vault.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    onOpen: (id: String) -> Unit,
    modifier: Modifier = Modifier,
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
        items(media, key = { it.media.id }) { item ->
            MediaTile(item, onOpen)
        }
    }
}

@Composable
private fun MediaTile(item: MediaWithTags, onOpen: (String) -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF1B2126))
            .clickable { onOpen(item.media.id) },
    ) {
        AsyncImage(
            model = VaultMediaKey(item.media.id, full = false),
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
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(
        modifier.fillMaxSize().background(Color(0xFF0E1113)).padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Nothing saved yet.\nShare a photo or video to Vault to get started.",
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
