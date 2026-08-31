package com.atelierapps.vault.ui.viewer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.displayCutoutPadding
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import coil3.compose.AsyncImage
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.ui.image.VaultMediaKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import com.atelierapps.vault.ui.theme.Danger
import com.atelierapps.vault.ui.theme.VaultIconButton
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Scrim
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.platform.LocalDensity

/**
 * Programmatic page changes (slideshow advance, prev/next) use an eased glide
 * rather than the pager's default spring: a spring overshoots and settles, which
 * on a run of short clips reads as a jolt. Manual swipes still fling normally.
 */
private val PageGlide = tween<Float>(durationMillis = 550, easing = FastOutSlowInEasing)

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
    onShare: (id: String) -> Unit,
    albums: List<com.atelierapps.vault.data.entity.AlbumEntity> = emptyList(),
    allTags: List<com.atelierapps.vault.data.entity.TagEntity> = emptyList(),
    onSetTags: (id: String, names: List<String>) -> Unit = { _, _ -> },
    onRename: (id: String, name: String) -> Unit = { _, _ -> },
    onAddToAlbum: (id: String, albumId: String) -> Unit = { _, _ -> },
    onAddToNewAlbum: (id: String, name: String) -> Unit = { _, _ -> },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val autoplayPref = remember { com.atelierapps.vault.session.VideoPrefs.autoplay(context) }
    val pagerState = rememberPagerState(initialPage = startIndex) { media.size }
    var chromeVisible by remember { mutableStateOf(false) }
    var videoControls by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<MediaWithTags?>(null) }
    var albumFor by remember { mutableStateOf<String?>(null) }
    var taggingItem by remember { mutableStateOf<MediaWithTags?>(null) }
    // Shuffle hands us an order and asks for playback to start straight away.
    var playMode by remember { mutableStateOf(ViewerSession.consumeStartPlaying()) }
    var intervalSec by remember { mutableIntStateOf(5) }
    // Playlist-level: wrap back to the first item after the last. Distinct from
    // the per-video loop in the player controls.
    var repeatAll by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Pull down to go back. Reaching the top-left corner one-handed on a tall
    // phone is the single most-repeated annoyance in the viewer, and this is the
    // gesture people already expect from every other photo app.
    val density = LocalDensity.current
    val dismissTravel = with(density) { 240.dp.toPx() }
    val dismissThreshold = with(density) { 110.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }
    val dismissProgress = (dragY / dismissTravel).coerceIn(0f, 1f)
    val onDismissDrag: (Float) -> Unit = { dy -> dragY = dy.coerceAtLeast(0f) }
    val onDismissEnd: () -> Unit = {
        if (dragY > dismissThreshold) {
            onBack()
        } else {
            val from = dragY
            scope.launch {
                animate(from, 0f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { v, _ ->
                    dragY = v
                }
            }
        }
    }

    val current = media.getOrNull(pagerState.currentPage)
    val currentIsVideo = current?.media?.mimeType?.startsWith("video/") == true

    val advance: () -> Unit = {
        scope.launch {
            val next = pagerState.currentPage + 1
            when {
                next < media.size -> pagerState.animateScrollToPage(next, animationSpec = PageGlide)
                repeatAll && media.isNotEmpty() -> pagerState.animateScrollToPage(0, animationSpec = PageGlide)
                else -> playMode = false
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) { videoControls = false; dragY = 0f }
    // Slideshow: images advance after the interval; videos advance when they end.
    LaunchedEffect(pagerState.currentPage, playMode, intervalSec, currentIsVideo) {
        if (playMode && current != null && !currentIsVideo) {
            delay(intervalSec * 1000L)
            advance()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationY = dragY
                val shrink = 1f - 0.18f * dismissProgress
                scaleX = shrink
                scaleY = shrink
                alpha = 1f - 0.4f * dismissProgress
            },
        ) { page ->
            ViewerPage(
                item = media[page],
                active = page == pagerState.currentPage,
                onDismissDrag = onDismissDrag,
                onDismissEnd = onDismissEnd,
                onTap = { chromeVisible = !chromeVisible },
                onVideoControls = { videoControls = it },
                autoPlay = (playMode || autoplayPref) && page == pagerState.currentPage,
                slideshowActive = playMode,
                onEnded = { if (playMode) advance() },
                onPrev = if (page > 0) {
                    fun() { scope.launch { pagerState.animateScrollToPage(page - 1, animationSpec = PageGlide) } }
                } else null,
                onNext = if (page < media.size - 1) {
                    fun() { scope.launch { pagerState.animateScrollToPage(page + 1, animationSpec = PageGlide) } }
                } else null,
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
                    onShare = { onShare(current.media.id) },
                    onRename = { renaming = current },
                    onTag = { taggingItem = current },
                    onAddToAlbum = { albumFor = current.media.id },
                    onDelete = { pendingDelete = current.media.id },
                )
            }
            if (chromeVisible && !isVideo) {
                MetadataPanel(current, Modifier.align(Alignment.BottomStart))
            }
        }

        // Slideshow controls are image-only; videos have their own control bar,
        // so don't double up the bottom of the screen for them.
        if (playMode && !currentIsVideo) {
            PlayControls(
                intervalSec = intervalSec,
                onInterval = { intervalSec = it },
                repeatAll = repeatAll,
                onToggleRepeatAll = { repeatAll = !repeatAll },
                onStop = { playMode = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    taggingItem?.let { item ->
        ViewerTagDialog(
            allTags = allTags,
            applied = item.tags.map { it.name },
            onApply = { names -> onSetTags(item.media.id, names); taggingItem = null },
            onDismiss = { taggingItem = null },
        )
    }

    renaming?.let { item ->
        RenameItemDialog(
            initial = item.media.originalName,
            onConfirm = { newName -> onRename(item.media.id, newName); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    albumFor?.let { id ->
        ViewerAlbumDialog(
            albums = albums,
            onPick = { albumId -> onAddToAlbum(id, albumId); albumFor = null },
            onCreate = { name -> onAddToNewAlbum(id, name); albumFor = null },
            onDismiss = { albumFor = null },
        )
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this item?") },
            text = { Text("It moves to Recently deleted, where you can restore it for 30 days.") },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(id) }) {
                    Text("Delete", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Tag the item you're watching, without leaving it. Chips show the tag's current
 * state, so this both adds and removes — a mis-tap mid-playback is undoable
 * here rather than only from the grid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewerTagDialog(
    allTags: List<com.atelierapps.vault.data.entity.TagEntity>,
    applied: List<String>,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val picked = remember(applied) { mutableStateListOf<String>().apply { addAll(applied) } }
    var newTag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column {
                if (allTags.isEmpty()) {
                    Text("No tags yet — name one below.", color = Muted, fontSize = 13.sp)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        allTags.forEach { tag ->
                            val on = picked.any { it.equals(tag.name, ignoreCase = true) }
                            FilterChip(
                                selected = on,
                                onClick = {
                                    if (on) picked.removeAll { it.equals(tag.name, ignoreCase = true) }
                                    else picked.add(tag.name)
                                },
                                label = { Text("#" + tag.name) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    placeholder = { Text("New tag(s), comma-separated") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val all = (picked + newTag.split(",")).map { it.trim() }.filter { it.isNotEmpty() }
                onApply(all.distinctBy { it.lowercase() })
            }) { Text("Save", color = Brass) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameItemDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name.trim() != initial,
            ) { Text("Save", color = Brass) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ViewerAlbumDialog(
    albums: List<com.atelierapps.vault.data.entity.AlbumEntity>,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newAlbum by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album") },
        text = {
            Column {
                if (albums.isEmpty()) {
                    Text("No albums yet — name one below.", color = Muted, fontSize = 13.sp)
                } else {
                    albums.forEach { album ->
                        Text(
                            album.name,
                            color = Ink, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPick(album.id) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = newAlbum,
                    onValueChange = { newAlbum = it },
                    placeholder = { Text("New album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(newAlbum) }, enabled = newAlbum.isNotBlank()) {
                Text("Create & add", color = Brass)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ViewerPage(
    item: MediaWithTags,
    active: Boolean,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit,
    onTap: () -> Unit,
    onVideoControls: (Boolean) -> Unit,
    autoPlay: Boolean,
    slideshowActive: Boolean,
    onEnded: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    if (item.media.mimeType.startsWith("video/")) {
        VideoPlayer(
            id = item.media.id,
            active = active,
            modifier = Modifier.fillMaxSize(),
            autoPlay = autoPlay,
            slideshowActive = slideshowActive,
            onControlsVisible = onVideoControls,
            onEnded = onEnded,
            onPrev = onPrev,
            onNext = onNext,
            onDismissDrag = onDismissDrag,
            onDismissEnd = onDismissEnd,
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
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    },
                )
            }
            // Zoomed in, a vertical drag is a pan, so this stands down. At rest
            // nothing else wants a vertical drag — the pager only takes
            // horizontal — so one thumb is enough to close.
            .pointerInput(scale > 1f) {
                if (scale > 1f) return@pointerInput
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = { onDismissEnd() },
                    onDragCancel = { onDismissEnd() },
                    onVerticalDrag = { change, delta ->
                        travelled += delta
                        onDismissDrag(travelled)
                        change.consume()
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
    onShare: () -> Unit,
    onRename: () -> Unit,
    onTag: () -> Unit,
    onAddToAlbum: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Color(0x99000000))
            .statusBarsPadding().displayCutoutPadding()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VaultIconButton(Icons.Outlined.KeyboardArrowLeft, "Back", onBack, tint = Ink, size = 44)
        Box(Modifier.weight(1f))
        VaultIconButton(
            if (playMode) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            if (playMode) "Pause slideshow" else "Play all",
            onTogglePlay,
            tint = Brass,
            size = 44,
        )
        VaultIconButton(Icons.Outlined.IosShare, "Share", onShare, size = 44)
        VaultIconButton(
            Icons.Filled.PushPin,
            if (isPinned) "Unpin" else "Pin",
            onTogglePin,
            tint = if (isPinned) Brass else Ink,
            size = 44,
        )
        VaultIconButton(Icons.Outlined.DeleteOutline, "Delete", onDelete, tint = Danger, size = 44)
        Box {
            VaultIconButton(Icons.Outlined.MoreVert, "More", { menu = true }, size = 44)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Tags…") }, onClick = { menu = false; onTag() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Add to album…") }, onClick = { menu = false; onAddToAlbum() })
            }
        }
    }
}

@Composable
private fun PlayControls(
    intervalSec: Int,
    onInterval: (Int) -> Unit,
    repeatAll: Boolean,
    onToggleRepeatAll: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(Scrim).navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Every", color = Muted, fontSize = 12.sp)
        listOf(3, 5, 10, 15).forEach { sec ->
            val on = sec == intervalSec
            Text(
                "${sec}s",
                color = if (on) BrassInk else Ink,
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
            if (repeatAll) "Repeat all ✓" else "Repeat all",
            color = if (repeatAll) Brass else Ink, fontSize = 12.sp,
            modifier = Modifier.clickable { onToggleRepeatAll() }.padding(6.dp),
        )
        Text(
            "Stop", color = Danger, fontSize = 13.sp,
            modifier = Modifier.clickable { onStop() }.padding(6.dp),
        )
    }
}

@Composable
private fun MetadataPanel(item: MediaWithTags, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().background(Scrim).navigationBarsPadding().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(item.media.originalName, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        source(item)?.let { Text(it, color = Muted, fontSize = 12.sp) }
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
