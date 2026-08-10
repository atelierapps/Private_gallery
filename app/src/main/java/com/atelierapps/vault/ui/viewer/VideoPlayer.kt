package com.atelierapps.vault.ui.viewer

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.atelierapps.vault.R
import com.atelierapps.vault.crypto.VaultCtrDataSource
import kotlinx.coroutines.delay

/**
 * Plays an encrypted vault video (spec §9) through ExoPlayer, decrypting via
 * [VaultCtrDataSource] so no plaintext touches disk.
 *
 * Uses a TextureView-backed [PlayerView] with the built-in controller off, and
 * draws its own Compose controls: a big centre play/pause, a full-width
 * drag-anywhere scrubber, and a loop toggle. The surface can be pinch-zoomed and
 * panned (two-finger, focal-anchored) while single-finger swipes still reach the
 * pager. The hosting activity keeps itself alive across rotation
 * (`configChanges`), so turning the phone no longer restarts the video.
 */
@UnstableApi
@Composable
fun VideoPlayer(
    id: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    onControlsVisible: (Boolean) -> Unit = {},
    onEnded: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnEnded by rememberUpdatedState(onEnded)
    val player = remember(id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(VaultCtrDataSource.Factory(context)))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(VaultCtrDataSource.uriFor(id)))
                prepare()
                playWhenReady = autoPlay
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) currentOnEnded()
                    }
                })
            }
    }

    var loop by remember(id) { mutableStateOf(true) }
    var isPlaying by remember(id) { mutableStateOf(autoPlay) }
    var controlsVisible by remember(id) { mutableStateOf(true) }
    var positionMs by remember(id) { mutableLongStateOf(0L) }
    var durationMs by remember(id) { mutableLongStateOf(0L) }
    var scrubbing by remember(id) { mutableStateOf(false) }
    var scrubMs by remember(id) { mutableLongStateOf(0L) }

    // Zoom / pan of the video surface.
    var scale by remember(id) { mutableFloatStateOf(1f) }
    var offset by remember(id) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(autoPlay) { player.playWhenReady = autoPlay }
    LaunchedEffect(loop) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(controlsVisible) { onControlsVisible(controlsVisible) }

    // Mirror play/pause state.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(id) { onDispose { player.release() } }

    // Poll position/duration for the scrubber.
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
            }
            delay(250)
        }
    }
    // Auto-hide controls a few seconds after they appear while playing.
    LaunchedEffect(controlsVisible, isPlaying, scrubbing) {
        if (controlsVisible && isPlaying && !scrubbing) {
            delay(3500)
            controlsVisible = false
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.vault_player_view, null) as PlayerView)
                    .apply { this.player = player }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale, scaleY = scale,
                translationX = offset.x, translationY = offset.y,
            ),
        )

        // Two-finger pinch-zoom + pan, focal-anchored. Single-finger gestures are
        // left unconsumed so the pager can still swipe between items at 1x.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                val old = scale
                                val next = (old * zoom).coerceIn(1f, 5f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val focus = centroid - center
                                offset = (offset - focus) * (next / old) + focus + pan
                                scale = next
                                if (scale <= 1.01f) { scale = 1f; offset = Offset.Zero }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(id) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2f
                        },
                    )
                },
        )

        if (controlsVisible) {
            // Centre play/pause.
            Box(
                Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape)
                    .background(Color(0x66000000))
                    .pointerInput(id) {
                        detectTapGestures(onTap = { if (isPlaying) player.pause() else player.play() })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (isPlaying) "⏸" else "▶", color = Color.White, fontSize = 28.sp)
            }

            // Bottom: scrubber + times + loop.
            val shown = if (scrubbing) scrubMs else positionMs
            val dur = durationMs.coerceAtLeast(1L)
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xCC06080A)).navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(fmt(shown), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = (shown.toFloat() / dur).coerceIn(0f, 1f),
                    onValueChange = { frac ->
                        scrubbing = true
                        scrubMs = (frac * dur).toLong()
                    },
                    onValueChangeFinished = {
                        player.seekTo(scrubMs)
                        scrubbing = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Brass,
                        activeTrackColor = Brass,
                        inactiveTrackColor = Color(0x55FFFFFF),
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(fmt(durationMs), color = Color.White, fontSize = 12.sp)
                Text(
                    "🔁", fontSize = 18.sp,
                    color = if (loop) Brass else Color(0x66FFFFFF),
                    modifier = Modifier.clip(CircleShape)
                        .pointerInput(id) { detectTapGestures(onTap = { loop = !loop }) }
                        .padding(6.dp),
                )
            }
        }
    }
}

private val Brass = Color(0xFFD8B463)

private fun fmt(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
