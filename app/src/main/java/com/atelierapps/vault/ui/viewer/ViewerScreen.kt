package com.atelierapps.vault.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.ui.image.VaultMediaKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFFE9EEF0)
private val Muted = Color(0xFF9AA6AD)
private val Brass = Color(0xFFD8B463)

/**
 * Full-screen viewer (spec §8, §15.4). Swipe between items; pinch to zoom images.
 * Chrome (top bar + metadata) is hidden until you tap — the photo gets the full
 * screen. Video items show their thumbnail with a play badge; playback is step 9.
 */
@Composable
fun ViewerScreen(
    media: List<MediaWithTags>,
    startIndex: Int,
    onBack: () -> Unit,
    onDelete: (id: String) -> Unit,
    onTogglePin: (id: String) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { media.size }
    var chromeVisible by remember { mutableStateOf(false) }
    var videoControls by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var playMode by remember { mutableStateOf(false) }
    var intervalSec by remember { mutableIntStateOf(5) }
    var loop by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val current = media.getOrNull(pagerState.currentPage)
    val currentIsVideo = current?.media?.mimeType?.startsWith("video/") == true

    val advance: () -> Unit = {
        scope.launch {
            val next = pagerState.currentPage + 1
            when {
                next < media.size -> pagerState.animateScrollToPage(next)
                loop && media.isNotEmpty() -> pagerState.animateScrollToPage(0)
                else -> playMode = false
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) { videoControls = false }
    // Slideshow: images advance after the interval; videos advance when they end.
    LaunchedEffect(pagerState.currentPage, playMode, intervalSec, currentIsVideo) {
        if (playMode && current != null && !currentIsVideo) {
            delay(intervalSec * 1000L)
            advance()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            ViewerPage(
                item = media[page],
                onTap = { chromeVisible = !chromeVisible },
                onVideoControls = { videoControls = it },
                autoPlay = playMode && page == pagerState.currentPage,
                onEnded = { if (playMode) advance() },
            )
        }

        if (current != null) {
            val isVideo = currentIsVideo
            if (if (isVideo) videoControls else chromeVisible) {
                TopBar(
                    onBack = onBack,
                    isPinned = current.media.isPinned,
                    playMode = playMode,
                    onTogglePlay = { playMode = !playMode },
                    onTogglePin = { onTogglePin(current.media.id) },
                    onDelete = { pendingDelete = current.media.id },
                )
            }
            if (chromeVisible && !isVideo) {
                MetadataPanel(current, Modifier.align(Alignment.BottomStart))
            }
        }

        if (playMode) {
            PlayControls(
                intervalSec = intervalSec,
                onInterval = { intervalSec = it },
                loop = loop,
                onToggleLoop = { loop = !loop },
                onStop = { playMode = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this item?") },
            text = { Text("It'll be permanently removed from the vault. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(id) }) {
                    Text("Delete", color = Color(0xFFE08A7A))
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ViewerPage(
    item: MediaWithTags,
    onTap: () -> Unit,
    onVideoControls: (Boolean) -> Unit,
    autoPlay: Boolean,
    onEnded: () -> Unit,
) {
    if (item.media.mimeType.startsWith("video/")) {
        VideoPlayer(
            id = item.media.id,
            modifier = Modifier.fillMaxSize(),
            autoPlay = autoPlay,
            onControlsVisible = onVideoControls,
            onEnded = onEnded,
        )
        return
    }

    // Image: double-tap toggles zoom; pinch/pan only while zoomed, so a
    // single-finger horizontal swipe at rest reaches the pager (fixes paging).
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        // Cached thumbnail shows instantly (no blank screen while the full-res
        // image decrypts + decodes); the full image draws over it when ready.
        AsyncImage(
            model = VaultMediaKey(item.media.id, full = false),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        AsyncImage(
            model = VaultMediaKey(item.media.id, full = true),
            contentDescription = item.media.originalName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transform, enabled = scale > 1f)
                .graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offset.x, translationY = offset.y,
                ),
        )
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    isPinned: Boolean,
    playMode: Boolean,
    onTogglePlay: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("‹ Back", color = Ink) }
        Box(Modifier.weight(1f))
        TextButton(onClick = onTogglePlay) {
            Text(if (playMode) "⏸" else "▶ Play", color = Brass)
        }
        TextButton(onClick = onTogglePin) {
            Text(if (isPinned) "📌" else "📌 Pin", color = if (isPinned) Brass else Ink)
        }
        TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFE08A7A)) }
    }
}

@Composable
private fun PlayControls(
    intervalSec: Int,
    onInterval: (Int) -> Unit,
    loop: Boolean,
    onToggleLoop: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(Color(0xCC06080A)).navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Every", color = Muted, fontSize = 12.sp)
        listOf(3, 5, 10, 15).forEach { sec ->
            val on = sec == intervalSec
            Text(
                "${sec}s",
                color = if (on) Color(0xFF1A1509) else Ink,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Brass else Color(0x22FFFFFF))
                    .clickable { onInterval(sec) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.weight(1f))
        Text(
            if (loop) "Loop ✓" else "Loop",
            color = if (loop) Brass else Ink, fontSize = 12.sp,
            modifier = Modifier.clickable { onToggleLoop() }.padding(6.dp),
        )
        Text(
            "Stop", color = Color(0xFFE08A7A), fontSize = 13.sp,
            modifier = Modifier.clickable { onStop() }.padding(6.dp),
        )
    }
}

@Composable
private fun MetadataPanel(item: MediaWithTags, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().background(Color(0xCC06080A)).navigationBarsPadding().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        source(item)?.let { Text(it, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        Text(dateOf(item.media.dateTakenMillis), color = Muted, fontSize = 12.sp)
        if (item.tags.isNotEmpty()) {
            Text(item.tags.joinToString(" ") { "#${it.name}" }, color = Brass, fontSize = 13.sp)
        }
    }
}

/** Label and/or host — never the full URL (spec §2.1/§15.4). */
private fun source(item: MediaWithTags): String? {
    val label = item.media.sourceLabel
    val domain = item.media.sourceDomain
    return when {
        label != null && domain != null -> "$label · $domain"
        label != null -> label
        domain != null -> domain
        else -> null
    }
}

private fun dateOf(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
