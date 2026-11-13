package com.atelierapps.vault.ui.home

import android.os.Looper
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.atelierapps.vault.R
import com.atelierapps.vault.crypto.VaultCtrDataSource
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.session.VideoPrefs
import com.atelierapps.vault.ui.image.VaultMediaKey
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Hairline
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.Scrim
import com.atelierapps.vault.ui.theme.SurfaceHigh
import kotlinx.coroutines.delay

/** How long a video preview plays before it stops itself. */
private const val PREVIEW_MILLIS = 20_000L

/**
 * A quick look at one item without leaving the grid: long-press a tile, then
 * carry on downward without lifting.
 *
 * Drawn inside the home screen's own window, not as a Dialog: a Dialog is a
 * separate window, which doesn't inherit the activity's FLAG_SECURE, and a
 * playing video is exactly what must stay out of screenshots and the
 * recent-apps thumbnail.
 *
 * Battery is the whole design constraint. One preview at a time (this is the
 * only player the grid ever makes), at most [PREVIEW_MILLIS] of playback, and
 * then the player is stopped, which releases its decoder; leaving the preview
 * releases the player itself. Nothing keeps running once you've looked.
 * Sound follows the app's mute setting.
 */
@UnstableApi
@Composable
fun GridPreview(item: MediaWithTags, onOpen: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Scrim)
            // Tap outside the card to close.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceHigh)
                // Taps on the card itself stay on the card.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(previewAspect(item)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (item.media.mimeType.startsWith("video/")) {
                    PreviewVideo(item.media.id)
                } else {
                    AsyncImage(
                        model = VaultMediaKey(item.media.id, full = true),
                        contentDescription = item.media.originalName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    item.media.originalName,
                    color = Ink,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.tags.isNotEmpty()) {
                    Text(
                        item.tags.joinToString("  ") { "#" + it.name },
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Hairline, contentColor = Ink),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) { Text("Close") }
                    Button(
                        onClick = onOpen,
                        colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BrassInk),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) { Text("Open") }
                }
            }
        }
    }
}

/** The card takes the item's shape, within reason: a tall clip shouldn't fill the screen. */
private fun previewAspect(item: MediaWithTags): Float {
    val w = item.media.widthPx
    val h = item.media.heightPx
    val raw = if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f
    return raw.coerceIn(0.75f, 1.9f)
}

@UnstableApi
@Composable
private fun PreviewVideo(id: String) {
    val context = LocalContext.current
    var over by remember(id) { mutableStateOf(false) }
    val player = remember(id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(VaultCtrDataSource.Factory(context)))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(VaultCtrDataSource.uriFor(id)))
                repeatMode = Player.REPEAT_MODE_OFF
                volume = if (VideoPrefs.muted(context)) 0f else 1f
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(player) {
        // The player stops itself at the limit (see CLAUDE.md: act at a
        // position, don't poll for it). stop() releases the decoder; the
        // poster below covers the black it leaves.
        val message = player.createMessage { _, _ -> over = true }
            .setLooper(Looper.getMainLooper())
            .setPosition(PREVIEW_MILLIS)
            .send()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) over = true
            }
        }
        player.addListener(listener)
        onDispose {
            message.cancel()
            player.removeListener(listener)
            player.release()
        }
    }
    // Backstop, by the clock, in case the clip stalls before reaching the mark.
    LaunchedEffect(player) {
        delay(PREVIEW_MILLIS + 5_000L)
        over = true
    }
    LaunchedEffect(over) { if (over) player.stop() }

    if (!over) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.vault_player_view, null) as PlayerView)
                    .apply { this.player = player }
            },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = VaultMediaKey(id, full = false),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "Preview over — Open to keep watching",
                color = Ink,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(9999.dp)).background(Scrim)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
