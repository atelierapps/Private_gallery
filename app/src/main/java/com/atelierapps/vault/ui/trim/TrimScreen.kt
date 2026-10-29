package com.atelierapps.vault.ui.trim

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.atelierapps.vault.ui.theme.VaultChip
import com.atelierapps.vault.ui.theme.VaultActionChip
import com.atelierapps.vault.ui.theme.Surface
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material3.Slider
import com.atelierapps.vault.media.ThumbnailSetter
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import android.view.TextureView
import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.atelierapps.vault.R
import com.atelierapps.vault.crypto.VaultCtrDataSource
import com.atelierapps.vault.media.VideoTrimmer
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Danger
import com.atelierapps.vault.ui.theme.Hairline
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.SurfaceHigh
import com.atelierapps.vault.ui.theme.VaultIconButton
import kotlinx.coroutines.delay

/**
 * Pick the stretch to keep, see what it saves, cut.
 *
 * The preview plays only the chosen stretch and stops at its end, so what you
 * watch is what you'll get. Moving a handle shows that frame. The size figure
 * is an estimate by proportion — exact for a steady bitrate, close otherwise.
 */
@UnstableApi
@Composable
fun TrimScreen(
    state: TrimState,
    onRange: (Long, Long) -> Unit,
    onReplace: (Boolean) -> Unit,
    onTrim: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The player's own reading of the length, for clips with none stored. */
    onDuration: (Long) -> Unit = {},
    /** Switch between choosing a thumbnail (true) and trimming (false). */
    onMode: (Boolean) -> Unit = {},
    /** Add the current range as a part of a split, or take one back out. */
    onAddPart: () -> Unit = {},
    onRemovePart: (Pair<Long, Long>) -> Unit = {},
) {
    val item = state.item
    // A trim can take a while on a long clip; don't let the screen sleep on it.
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier.fillMaxSize().background(Bg)) {
        ScreenHeader(if (state.thumbnailMode) "Set thumbnail" else "Trim", onClose)
        if (item != null) {
            TrimBody(item, state, onRange, onReplace, onTrim, onDuration, onMode, onAddPart, onRemovePart)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TrimBody(
    item: com.atelierapps.vault.data.entity.MediaWithTags,
    state: TrimState,
    onRange: (Long, Long) -> Unit,
    onReplace: (Boolean) -> Unit,
    onTrim: () -> Unit,
    onDuration: (Long) -> Unit,
    onMode: (Boolean) -> Unit,
    onAddPart: () -> Unit,
    onRemovePart: (Pair<Long, Long>) -> Unit,
) {
    val context = LocalContext.current
        val player = remember(item.media.id) {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(VaultCtrDataSource.Factory(context)))
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(VaultCtrDataSource.uriFor(item.media.id)))
                    prepare()
                }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        var playing by remember { mutableStateOf(false) }
        // Kept so the frame on screen can be captured as a thumbnail. The view
        // is a TextureView (see vault_player_view), which is what makes
        // getBitmap() possible: the frame comes off the GPU surface, never
        // through a decoded file.
        var playerView by remember { mutableStateOf<PlayerView?>(null) }
        var savingThumb by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) onDuration(player.duration)
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
        // Stop at the end handle and park back at the start, so a replay shows
        // the same stretch again.
        LaunchedEffect(player, state.startMs, state.endMs, state.thumbnailMode) {
            while (true) {
                // Only trimming confines playback to the kept stretch; choosing
                // a thumbnail can go anywhere in the clip.
                if (!state.thumbnailMode && player.isPlaying && player.currentPosition >= state.endMs) {
                    player.pause()
                    player.seekTo(state.startMs)
                }
                delay(100)
            }
        }
        LaunchedEffect(state.running) { if (state.running) player.pause() }

        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.vault_player_view, null) as PlayerView)
                        .apply { this.player = player }
                        .also { playerView = it }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Where the preview is, for the thumbnail slider. Polled rather than
        // pushed: ExoPlayer has no position callback.
        var positionMs by remember { mutableLongStateOf(0L) }
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(player) {
            while (true) {
                if (!dragging) positionMs = player.currentPosition.coerceAtLeast(0L)
                delay(150)
            }
        }
        val useFrame: () -> Unit = useFrame@{
            if (savingThumb || state.running) return@useFrame
            player.pause()
            val frame = (playerView?.videoSurfaceView as? TextureView)?.bitmap
            if (frame == null) {
                Toast.makeText(context, "No frame to use yet", Toast.LENGTH_SHORT).show()
                return@useFrame
            }
            savingThumb = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ThumbnailSetter.set(context, item.media.id, frame)
                    } finally {
                        frame.recycle()
                    }
                }
                savingThumb = false
                Toast.makeText(
                    context,
                    if (ok) "Thumbnail updated" else "Couldn't update the thumbnail",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            val duration = state.durationMs
            if (duration > 0L) {
                ModeSwitch(thumbnail = state.thumbnailMode, enabled = !state.running && !savingThumb, onMode = onMode)
            }
            if (duration > 0L && state.thumbnailMode) {
                // ---- thumbnail: one scrubber, one button, nothing about trimming ----
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    VaultIconButton(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "Pause" else "Play",
                        { if (playing) player.pause() else player.play() },
                    )
                    Text(
                        clock(positionMs) + " / " + clock(duration),
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Slider(
                    value = positionMs.coerceIn(0L, duration).toFloat(),
                    onValueChange = { v ->
                        dragging = true
                        positionMs = v.toLong()
                        player.seekTo(positionMs)
                    },
                    onValueChangeFinished = { dragging = false },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Brass,
                        activeTrackColor = Brass,
                        inactiveTrackColor = Hairline,
                    ),
                )
                Text(
                    "Find the frame with the slider, or play and pause where you want it. " +
                        "Only the tile changes — the video itself isn't touched.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = useFrame,
                    enabled = !savingThumb,
                    colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BrassInk),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
                ) {
                    Text(if (savingThumb) "Saving…" else "Use this frame")
                }
            } else if (duration > 0L) {
                // ---- trim ----
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    VaultIconButton(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "Pause preview" else "Play the kept part",
                        {
                            if (playing) {
                                player.pause()
                            } else {
                                val at = player.currentPosition
                                if (at < state.startMs || at >= state.endMs) player.seekTo(state.startMs)
                                player.play()
                            }
                        },
                        enabled = !state.running,
                    )
                    Text(
                        clock(state.startMs) + " – " + clock(state.endMs),
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                RangeSlider(
                    value = state.startMs.toFloat()..state.endMs.toFloat(),
                    onValueChange = { range ->
                        val start = range.start.toLong()
                        val end = range.endInclusive.toLong()
                        // Show the frame under whichever handle moved.
                        if (start != state.startMs) player.seekTo(start) else if (end != state.endMs) player.seekTo(end)
                        onRange(start, end)
                    },
                    valueRange = 0f..duration.toFloat(),
                    enabled = !state.running,
                    colors = SliderDefaults.colors(
                        thumbColor = Brass,
                        activeTrackColor = Brass,
                        inactiveTrackColor = Hairline,
                    ),
                )
                val kept = (state.endMs - state.startMs).coerceAtLeast(0L)
                val size = item.media.sizeBytes
                val keptBytes = if (duration > 0L) size * kept / duration else size
                Text(
                    "Keeps " + clock(kept) + " of " + clock(duration) +
                        " · about " + mb(keptBytes) + " of " + mb(size),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Cuts land on the nearest keyframe, so it may start a second or two earlier. " +
                        "Nothing is re-encoded: the kept part loses no quality.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )

                // Split: keep several stretches, each as its own item. Set the
                // handles, add it, set them again. With no parts added this is
                // a plain trim of the one range.
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.parts.isEmpty()) "Want more than one part? Add this range, then pick the next."
                        else "" + state.parts.size + (if (state.parts.size == 1) " part" else " parts") + " — each becomes its own video",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    VaultActionChip(
                        "Add as a part",
                        onClick = { if (!state.running && kept >= VideoTrimmer.MIN_KEEP_MILLIS) onAddPart() },
                    )
                }
                if (state.parts.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        state.parts.forEachIndexed { i, part ->
                            VaultChip(
                                label = "" + (i + 1) + ". " + clock(part.first) + "–" + clock(part.second),
                                selected = true,
                                showCheck = false,
                                onClick = { onRemovePart(part) },
                                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove part", modifier = Modifier.size(14.dp)) },
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp)
                        .background(SurfaceHigh, MaterialTheme.shapes.medium)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Replace the original", color = Ink, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (state.replace) {
                                if (state.parts.isEmpty()) "The original goes to Recently deleted, only once the trimmed copy is checked"
                                else "The original goes to Recently deleted, only once every part is checked"
                            } else {
                                "Both are kept"
                            },
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Switch(
                        checked = state.replace,
                        onCheckedChange = onReplace,
                        enabled = !state.running,
                        colors = SwitchDefaults.colors(checkedThumbColor = BrassInk, checkedTrackColor = Brass),
                    )
                }

                state.error?.let {
                    Text(it, color = Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                }

                if (state.running) {
                    Text(
                        (if (state.runningPart > 0) "Part " + state.runningPart + " of " + state.parts.size + " · " else "Trimming… ") +
                            state.progress + "%",
                        color = Ink,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        color = Brass,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    val splitting = state.parts.isNotEmpty()
                    val tooShort = !splitting && kept < VideoTrimmer.MIN_KEEP_MILLIS
                    val whole = !splitting && state.startMs <= 0L && state.endMs >= duration
                    Button(
                        onClick = onTrim,
                        enabled = !tooShort && !whole,
                        colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BrassInk),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
                    ) {
                        Text(
                            when {
                                splitting -> "Save " + state.parts.size + (if (state.parts.size == 1) " part" else " parts")
                                whole -> "Move a handle to choose what to keep"
                                tooShort -> "Keep at least a second"
                                else -> "Trim"
                            },
                        )
                    }
                }
            } else {
                Text("Reading the video…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
}

/** Thumbnail · Trim, as one segmented control, the same shape as the tag picker's order switch. */
@Composable
private fun ModeSwitch(thumbnail: Boolean, enabled: Boolean, onMode: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Surface).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(true to "Thumbnail", false to "Trim").forEach { (mode, label) ->
            val on = mode == thumbnail
            Text(
                label,
                color = if (on) Brass else Muted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Hairline else Color.Transparent)
                    .selectable(selected = on, enabled = enabled, role = Role.Tab, onClick = { onMode(mode) })
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun mb(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format((bytes / 1e6).coerceAtLeast(1.0))
