package com.atelierapps.vault.ui.viewer

import com.atelierapps.vault.ui.theme.Hairline
import com.atelierapps.vault.ui.theme.SurfaceHigh
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import com.atelierapps.vault.ui.theme.ScrimSoft
import androidx.compose.material.icons.outlined.Shuffle
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
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.atelierapps.vault.ui.theme.TagPicker
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.LocalOffer
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
import androidx.activity.compose.BackHandler
import com.atelierapps.vault.ui.lock.FloatingLockButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.alpha

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
    onResumePosition: (id: String, millis: Long?) -> Unit = { _, _ -> },
    onVideoRotation: (id: String, degrees: Int?) -> Unit = { _, _ -> },
    onRename: (id: String, name: String) -> Unit = { _, _ -> },
    onEdit: (id: String) -> Unit = {},
    /** Open the trim screen for a video. */
    onTrim: (id: String) -> Unit = {},
    /** Open the same screen on choosing a video's thumbnail. */
    onThumbnail: (id: String) -> Unit = {},
    onAddToAlbum: (id: String, albumId: String) -> Unit = { _, _ -> },
    onAddToNewAlbum: (id: String, name: String) -> Unit = { _, _ -> },
    /** Save (or, with null, clear) the item's note. */
    onSetNote: (id: String, note: String?) -> Unit = { _, _ -> },
    /** The item has been on screen long enough to count as seen. */
    onViewed: (id: String) -> Unit = {},
    /** Whether the order is currently shuffled: drives the button's brass. */
    shuffleActive: Boolean = false,
    /** Shuffle on or off around this item. Turning it on also starts the slideshow. */
    onShuffle: (id: String) -> Unit = {},
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
    var notingItem by remember { mutableStateOf<MediaWithTags?>(null) }
    // Shuffle hands us an order and asks for playback to start straight away.
    var playMode by remember { mutableStateOf(ViewerSession.consumeStartPlaying()) }
    var intervalSec by remember { mutableIntStateOf(5) }
    // A two-second confirmation for the switches whose state you can't see
    // in the picture itself — shuffle, slideshow, loop. Without it, a tap on
    // shuffle changed nothing visible until the clip ended.
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeSeq by remember { mutableIntStateOf(0) }
    val announce: (String) -> Unit = { notice = it; noticeSeq++ }
    LaunchedEffect(noticeSeq) {
        if (notice != null) { delay(2000); notice = null }
    }
    val toggleSlideshow: () -> Unit = {
        playMode = !playMode
        announce(if (playMode) "Slideshow on" else "Slideshow off")
    }
    val toggleShuffle: (String) -> Unit = { id ->
        // One item has no order to shuffle; saying "Shuffle on" would be a lie.
        if (media.size > 1 || shuffleActive) {
            val turningOn = !shuffleActive
            onShuffle(id)
            if (turningOn) playMode = true
            announce(if (turningOn) "Shuffle on" else "Shuffle off")
        }
    }
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

    // Over media the lock button shows itself and then gets out of the way, so
    // it isn't sitting on the photo for as long as you look at it. Every tap
    // brings it back, along with moving to another item — the moments you might
    // want it. Two seconds is enough to find with a thumb and short enough not
    // to be part of the picture.
    // Where you are in the run. It matters most right after a swipe and not at
    // all a moment later, so it behaves like the swipe itself: appears with the
    // move, gone before it's furniture. Shuffle is where this earns its keep —
    // there is otherwise no way to tell a long random run from an endless one.
    var indexVisible by remember { mutableStateOf(true) }
    LaunchedEffect(pagerState.currentPage) {
        indexVisible = true
        delay(1400)
        indexVisible = false
    }

    var lockVisible by remember { mutableStateOf(true) }
    LaunchedEffect(pagerState.currentPage, chromeVisible, videoControls) {
        lockVisible = true
        delay(2000)
        lockVisible = false
    }
    LaunchedEffect(current?.media?.id) {
        current?.media?.id?.let { ViewerSession.lastViewedId.value = it }
    }
    // Counts as seen once it has been on screen for two seconds. Swiping past
    // doesn't count, and a looping clip counts once per visit, not per loop:
    // "Unwatched" and "Not seen lately" are about whether you looked at it.
    LaunchedEffect(current?.media?.id) {
        val id = current?.media?.id ?: return@LaunchedEffect
        delay(2000)
        onViewed(id)
    }
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
                onResumePosition = onResumePosition,
                onVideoRotation = onVideoRotation,
                onDismissDrag = onDismissDrag,
                onDismissEnd = onDismissEnd,
                onTap = { chromeVisible = !chromeVisible },
                onVideoControls = { videoControls = it },
                autoPlay = (playMode || autoplayPref) && page == pagerState.currentPage,
                slideshowActive = playMode,
                onEnded = { if (playMode) advance() },
                onToggleSlideshow = toggleSlideshow,
                onTag = { taggingItem = media[page] },
                onShuffle = { toggleShuffle(media[page].media.id) },
                shuffleActive = shuffleActive,
                onNotice = announce,
            )
        }

        // Whether the top bar is on screen, and what the counter would say.
        // Both are needed before either block below: an overlay centred over a
        // row of buttons will always land on one of them, so when the bar is up
        // the counter is laid out *inside* it instead of floating over it.
        val isVideo = currentIsVideo
        val barVisible = current != null && (if (isVideo) videoControls else chromeVisible)
        val indexLabel =
            if (media.size > 1) "" + (pagerState.currentPage + 1) + " / " + media.size else null

        if (current != null) {
            if (barVisible) {
                TopBar(
                    onBack = onBack,
                    isPinned = current.media.isPinned,
                    indexLabel = indexLabel,
                    onTogglePin = { onTogglePin(current.media.id) },
                    onShare = { onShare(current.media.id) },
                    onRename = { renaming = current },
                    // Videos have no editor yet, so the entry only shows where
                    // it would actually do something.
                    onEdit = if (!isVideo) {
                        fun() { onEdit(current.media.id) }
                    } else {
                        null
                    },
                    onTrim = if (isVideo) {
                        fun() { onTrim(current.media.id) }
                    } else {
                        null
                    },
                    onThumbnail = if (isVideo) {
                        fun() { onThumbnail(current.media.id) }
                    } else {
                        null
                    },
                    onTag = { taggingItem = current },
                    onNote = { notingItem = current },
                    onAddToAlbum = { albumFor = current.media.id },
                    onDelete = { pendingDelete = current.media.id },
                )
            }
            if (chromeVisible && !isVideo) {
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding(),
                ) {
                    // A photo has no control bar of its own, so the two actions
                    // the video bar now carries get a strip here — same buttons,
                    // same size, same corner of the screen either way.
                    Row(
                        Modifier.fillMaxWidth().background(ScrimSoft).padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Same rule as the video bar: white when off, brass when on.
                        CtlIcon(
                            if (playMode) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            if (playMode) "Pause slideshow" else "Play all",
                            tint = if (playMode) Brass else Color.White,
                            onClick = toggleSlideshow,
                        )
                        CtlIcon(
                            Icons.Outlined.Shuffle,
                            if (shuffleActive) "Shuffle off" else "Shuffle play",
                            tint = if (shuffleActive) Brass else Color.White,
                            onClick = { toggleShuffle(current.media.id) },
                        )
                        CtlIcon(
                            Icons.Outlined.LocalOffer,
                            "Tags",
                            tint = if (current.tags.isNotEmpty()) Brass else Color.White,
                            onClick = { taggingItem = current },
                        )
                    }
                    MetadataPanel(current, Modifier)
                }
            }
        }

        if (indexLabel != null && !barVisible) {
            val indexAlpha by animateFloatAsState(
                targetValue = if (indexVisible) 1f else 0f,
                animationSpec = tween(if (indexVisible) 120 else 400),
                label = "indexFade",
            )
            Text(
                indexLabel,
                color = Ink,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // The viewer is immersive, so the status bar is hidden and
                    // statusBarsPadding() collapses to nothing — which put this
                    // at the very top of the screen, under the punch-hole
                    // camera, with the digits behind the lens. The cutout inset
                    // is the one that survives immersive mode; the top bar
                    // already pairs the two the same way, and on a phone whose
                    // bars are hidden the status-bar half contributes zero.
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(top = 10.dp)
                    .alpha(indexAlpha)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Scrim)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        notice?.let { text ->
            Text(
                text,
                color = Ink,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Below the top bar's height rather than over it, and clear
                    // of the camera cutout the same way the counter is.
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(top = 72.dp)
                    .clip(RoundedCornerShape(9999.dp))
                    .background(Scrim)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        FloatingLockButton(visible = lockVisible, onLocked = onBack)

        // Slideshow controls are image-only; videos have their own control bar,
        // so don't double up the bottom of the screen for them.
        if (playMode && !currentIsVideo) {
            PlayControls(
                intervalSec = intervalSec,
                onInterval = { intervalSec = it },
                repeatAll = repeatAll,
                onToggleRepeatAll = { repeatAll = !repeatAll },
                onStop = { playMode = false; announce("Slideshow off") },
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

    notingItem?.let { item ->
        NoteDialog(
            initial = item.media.note.orEmpty(),
            onSave = { text -> onSetNote(item.media.id, text); notingItem = null },
            onDismiss = { notingItem = null },
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
 *
 * A bottom sheet rather than a centred dialog: it opens under the thumb that
 * tapped the tag button, and it leaves the clip visible above it. That is the
 * point of tagging from here — the picture is the reminder of what to tag it
 * with — so the shade behind is light (30%) and the sheet stops short of the
 * frame. The sheet itself stays opaque: grey text on a see-through sheet fails
 * the moment a bright frame sits behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTagDialog(
    allTags: List<com.atelierapps.vault.data.entity.TagEntity>,
    applied: List<String>,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val picked = remember(applied) { mutableStateListOf<String>().apply { addAll(applied) } }
    var newTag by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = SurfaceHigh,
        contentColor = Ink,
        scrimColor = Color(0x4D06080A),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tags", color = Ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (applied.isNotEmpty()) {
                    Text(
                        "" + applied.size + " on this item",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (allTags.isEmpty()) {
                Text(
                    "No tags yet — name one below.",
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            TagPicker(
                tags = allTags,
                isPicked = { name -> picked.any { it.equals(name, ignoreCase = true) } },
                onToggle = { name ->
                    if (picked.any { it.equals(name, ignoreCase = true) }) {
                        picked.removeAll { it.equals(name, ignoreCase = true) }
                    } else {
                        picked.add(name)
                    }
                },
                text = newTag,
                onTextChange = { newTag = it },
                modifier = Modifier.padding(top = 10.dp),
                maxHeight = 260.dp,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Hairline, contentColor = Ink),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        val all = (picked + newTag.split(",")).map { it.trim() }.filter { it.isNotEmpty() }
                        onApply(all.distinctBy { it.lowercase() })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BrassInk),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Save") }
            }
        }
    }
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
                    // Bounded and scrollable: AlertDialog gives its body
                    // `weight(1f, fill = false)` and no scroll of its own, so a
                    // long list is simply cut off at the dialog's edge — taking
                    // the field below it with it.
                    Column(
                        Modifier.heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
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
    onResumePosition: (id: String, millis: Long?) -> Unit,
    onVideoRotation: (id: String, degrees: Int?) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit,
    onTap: () -> Unit,
    onVideoControls: (Boolean) -> Unit,
    autoPlay: Boolean,
    slideshowActive: Boolean,
    onEnded: () -> Unit,
    onToggleSlideshow: () -> Unit,
    onTag: () -> Unit,
    onShuffle: () -> Unit,
    shuffleActive: Boolean,
    onNotice: (String) -> Unit,
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
            onToggleSlideshow = onToggleSlideshow,
            onTag = onTag,
            onShuffle = onShuffle,
            shuffleActive = shuffleActive,
            hasTags = item.tags.isNotEmpty(),
            note = item.media.note,
            onNotice = onNotice,
            onDismissDrag = onDismissDrag,
            onDismissEnd = onDismissEnd,
            resumeFrom = item.media.resumePositionMillis ?: 0L,
            onResumePosition = { onResumePosition(item.media.id, it) },
            initialRotation = item.media.videoRotationDegrees ?: 0,
            onRotationChanged = { onVideoRotation(item.media.id, it) },
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
    // Zoomed in, back should get you out of the zoom, not out of the photo.
    // Only the page you're looking at may claim it — every other page sits at
    // 1x, so their handlers are disabled and can't compete for the press.
    BackHandler(enabled = active && scale > 1f) {
        scale = 1f
        offset = Offset.Zero
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
    indexLabel: String?,
    onTogglePin: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onEdit: (() -> Unit)?,
    onTrim: (() -> Unit)?,
    onThumbnail: (() -> Unit)?,
    onTag: () -> Unit,
    onNote: () -> Unit,
    onAddToAlbum: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(ScrimSoft)
            .statusBarsPadding().displayCutoutPadding()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VaultIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Back", onBack, tint = Ink, size = 44)
        // The counter is a child of this row, not an overlay on top of it.
        // Floated over the row it sat on whichever button happened to be
        // leftmost of the group on the right — first the slideshow toggle, then
        // share once that moved. Laid out here it cannot reach them at all.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (indexLabel != null) {
                Text(
                    indexLabel,
                    color = Ink,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
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
                DropdownMenuItem(text = { Text("Note…") }, onClick = { menu = false; onNote() })
                if (onThumbnail != null) {
                    DropdownMenuItem(text = { Text("Set thumbnail…") }, onClick = { menu = false; onThumbnail() })
                }
                if (onTrim != null) {
                    DropdownMenuItem(text = { Text("Trim…") }, onClick = { menu = false; onTrim() })
                }
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                if (onEdit != null) {
                    DropdownMenuItem(
                        text = { Text("Rotate & crop…") },
                        onClick = { menu = false; onEdit() },
                    )
                }
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
        modifier.fillMaxWidth().background(ScrimSoft).navigationBarsPadding()
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
        modifier.fillMaxWidth().background(Scrim).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(item.media.originalName, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        source(item)?.let { Text(it, color = Muted, fontSize = 12.sp) }
        // Dimensions and size were in the row all along and shown nowhere.
        Text(specs(item), color = Muted, fontSize = 12.sp)
        Text(dateOf(item.media.dateTakenMillis), color = Muted, fontSize = 12.sp)
        if (item.tags.isNotEmpty()) {
            Text(item.tags.joinToString(" ") { "#${it.name}" }, color = Brass, fontSize = 13.sp)
        }
        item.media.note?.let { Text(it, color = Ink, fontSize = 13.sp) }
        Text(seenLine(item), color = Muted, fontSize = 12.sp)
    }
}

/** "Seen 3 times · last 12 Oct". The count includes this visit once it's counted. */
private fun seenLine(item: MediaWithTags): String {
    val n = item.media.viewCount
    val last = item.media.lastViewedAtMillis
    return when {
        n == 0 || last == null -> "Not seen before"
        n == 1 -> "Seen once · " + dateOf(last)
        else -> "Seen " + n + " times · last " + dateOf(last)
    }
}

/**
 * A few lines of your own about this item. Searchable from the grid. Saving
 * an empty box clears the note rather than storing a blank one.
 */
@Composable
private fun NoteDialog(initial: String, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            // The field scrolls itself past six lines, so the dialog's
            // non-scrolling body can't clip it.
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Anything worth remembering about this one") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim().takeIf { it.isNotEmpty() }) }) {
                Text(if (text.isBlank() && initial.isNotBlank()) "Remove" else "Save", color = Brass)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

/** "4032 × 3024 · 3.1 MB", with a video's running time in front of it. */
private fun specs(item: MediaWithTags): String {
    val m = item.media
    val parts = ArrayList<String>(3)
    m.durationMillis?.let { parts.add(runtime(it)) }
    if (m.widthPx > 0 && m.heightPx > 0) parts.add("${m.widthPx} × ${m.heightPx}")
    parts.add(fileSize(m.sizeBytes))
    return parts.joinToString(" · ")
}

private fun runtime(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun fileSize(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes < kb -> "$bytes B"
        bytes < kb * kb -> "%.0f KB".format(bytes / kb)
        bytes < kb * kb * kb -> "%.1f MB".format(bytes / (kb * kb))
        else -> "%.2f GB".format(bytes / (kb * kb * kb))
    }
}

private fun dateOf(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
