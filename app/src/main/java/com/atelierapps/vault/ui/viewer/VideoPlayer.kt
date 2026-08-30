package com.atelierapps.vault.ui.viewer

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.LayoutInflater
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import com.atelierapps.vault.R
import com.atelierapps.vault.crypto.VaultCtrDataSource
import com.atelierapps.vault.session.VideoPrefs
import com.atelierapps.vault.ui.image.VaultMediaKey
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Muted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.atelierapps.vault.ui.theme.Scrim

private val SPEEDS = floatArrayOf(0.25f, 0.5f, 1f, 1.5f, 2f)
private const val SPEED_DEFAULT = 2 // index of 1.0×

/**
 * Plays an encrypted vault video (spec §9) through ExoPlayer, decrypting via
 * [VaultCtrDataSource] so no plaintext touches disk.
 *
 * Only the **active** (currently-visible) page holds an ExoPlayer; every other
 * page renders a lightweight thumbnail poster. Hardware video decoders are a
 * scarce, pool-limited resource — holding one per composed pager page exhausted
 * them after a handful of videos and left later ones on a black screen. Gating
 * to a single live player fixes that.
 *
 * TextureView-backed so the surface can be pinch-zoomed / panned. Draws its own
 * Compose controls: play/pause, drag-anywhere scrubber, prev/skip/next, speed,
 * mute, loop; plus VLC-style vertical drags (right = volume, left = brightness).
 *
 * Mute is global and persisted, and while it's on the volume drag is swallowed
 * rather than applied — a mute you have to keep re-applying because a stray
 * swipe undid it isn't a mute. It attenuates the player, never the system
 * stream, so silencing playback leaves the device's media volume alone.
 *
 * The surface fades in over the clip's own thumbnail once the decoder renders a
 * first frame, so an autoplay advance never cuts through bare black. The fade is
 * deliberately unhurried — a quick dissolve still reads as a cut.
 *
 * Loop starts OFF for every clip and is disabled outright while a slideshow is
 * running: a repeat-one player never emits STATE_ENDED, which is the very signal
 * the slideshow advances on, so leaving them both on stalls the show forever.
 * Speed, by contrast, is deliberately sticky across clips ([ViewerSession]).
 */
@UnstableApi
@Composable
fun VideoPlayer(
    id: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    slideshowActive: Boolean = false,
    onControlsVisible: (Boolean) -> Unit = {},
    onEnded: () -> Unit = {},
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
) {
    // Inactive pages: poster only, no decoder held.
    if (!active) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = VaultMediaKey(id, full = false),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        return
    }

    val context = LocalContext.current
    val activity = context as? Activity
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val currentOnEnded by rememberUpdatedState(onEnded)
    val player = remember(id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(VaultCtrDataSource.Factory(context)))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(VaultCtrDataSource.uriFor(id)))
                prepare()
                // Resume where the clip was left off (in-memory only).
                (ViewerSession.positions[id] ?: 0L).let { if (it > 0) seekTo(it) }
                playWhenReady = autoPlay
                // Never start in repeat-one: a repeating player never emits
                // STATE_ENDED, which is what slideshow advance listens for.
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) currentOnEnded()
                    }
                })
            }
    }

    // Loop is per-video and starts OFF every time (spec: it must never silently
    // trap a slideshow on one clip).
    var loop by remember(id) { mutableStateOf(false) }
    var isPlaying by remember(id) { mutableStateOf(autoPlay) }
    // Start hidden when the clip plays itself: with a run of short videos,
    // showing the full control bar on every advance is just flicker. A clip that
    // opens paused still shows them, since you need the play button.
    var controlsVisible by remember(id) { mutableStateOf(!autoPlay) }
    var positionMs by remember(id) { mutableLongStateOf(0L) }
    var durationMs by remember(id) { mutableLongStateOf(0L) }
    var scrubbing by remember(id) { mutableStateOf(false) }
    var scrubMs by remember(id) { mutableLongStateOf(0L) }
    // Speed is sticky across videos for the session (seeded from ViewerSession).
    var speedIdx by remember(id) {
        mutableStateOf(SPEEDS.indexOfFirst { it == ViewerSession.playbackSpeed }.takeIf { it >= 0 } ?: SPEED_DEFAULT)
    }
    var speedMenu by remember(id) { mutableStateOf(false) }
    // Global mute: persisted, so it holds across videos and app launches.
    var muted by remember(id) { mutableStateOf(VideoPrefs.muted(context)) }
    var scale by remember(id) { mutableFloatStateOf(1f) }
    var offset by remember(id) { mutableStateOf(Offset.Zero) }
    var hud by remember(id) { mutableStateOf<String?>(null) }
    // The surface is black until the decoder produces a frame. On an autoplay
    // advance that black gap is what reads as a jolt, so hold the thumbnail
    // underneath and dissolve the video in over it once it's actually showing.
    var firstFrame by remember(id) { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(
        targetValue = if (firstFrame) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "videoFadeIn",
    )

    LaunchedEffect(autoPlay) { player.playWhenReady = autoPlay }
    // Slideshow wins over loop: while it's running the clip must be allowed to
    // end so the viewer can advance, no matter what the loop toggle says.
    LaunchedEffect(loop, slideshowActive) {
        player.repeatMode =
            if (loop && !slideshowActive) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(speedIdx) {
        player.setPlaybackSpeed(SPEEDS[speedIdx])
        ViewerSession.playbackSpeed = SPEEDS[speedIdx]
    }
    // Mute the player rather than the system stream, so silencing playback never
    // disturbs the device's media volume.
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(controlsVisible) { onControlsVisible(controlsVisible) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onRenderedFirstFrame() { firstFrame = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(id) {
        onDispose {
            // Remember the spot unless we're basically at the end.
            val pos = player.currentPosition
            val dur = player.duration
            if (dur > 0 && pos in 1 until (dur - 1500)) ViewerSession.positions[id] = pos
            else ViewerSession.positions.remove(id)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
            }
            delay(250)
        }
    }
    LaunchedEffect(controlsVisible, isPlaying, scrubbing, speedMenu) {
        if (controlsVisible && isPlaying && !scrubbing && !speedMenu) {
            delay(3500)
            controlsVisible = false
        }
    }
    // Safety net: if onRenderedFirstFrame never arrives (some decoders stay quiet
    // when paused), reveal the surface anyway rather than stranding the poster.
    LaunchedEffect(id) {
        delay(700)
        firstFrame = true
    }
    // Clear the transient volume/brightness HUD shortly after the drag ends.
    LaunchedEffect(hud) {
        if (hud != null) { delay(900); hud = null }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (videoAlpha < 1f) {
            AsyncImage(
                model = VaultMediaKey(id, full = false),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.vault_player_view, null) as PlayerView)
                    .apply { this.player = player }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale, scaleY = scale,
                translationX = offset.x, translationY = offset.y,
                alpha = videoAlpha,
            ),
        )

        // Unified gesture surface: two-finger pinch/pan (focal-anchored), and
        // single-finger vertical drags for volume (right half) / brightness
        // (left half). Horizontal single-finger drags are left for the pager.
        Box(
            Modifier.fillMaxSize().pointerInput(id) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = 0 // 0 undecided, 1 pinch, 2 brightness, 3 volume, 4 pager(bail)
                    val startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    var startBright = activity?.window?.attributes?.screenBrightness ?: 0.5f
                    if (startBright < 0f) startBright = 0.5f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            mode = 1
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
                        } else if (mode != 1) {
                            val c = pressed.first()
                            val dx = c.position.x - down.position.x
                            val dy = c.position.y - down.position.y
                            if (mode == 0) {
                                if (abs(dy) > slop && abs(dy) > abs(dx)) {
                                    val leftHalf = down.position.x < size.width / 2f
                                    mode = when {
                                        leftHalf -> 2
                                        // Muted: swallow the volume drag instead of
                                        // acting on it, so it can't be undone by accident.
                                        muted -> 5
                                        else -> 3
                                    }
                                    if (mode == 5) hud = "Muted"
                                } else if (abs(dx) > slop) {
                                    mode = 4
                                    break
                                }
                            }
                            if (mode == 2 || mode == 3) {
                                val frac = (-dy / size.height) // up = increase
                                if (mode == 3) {
                                    val target = (startVol + frac * maxVol).roundToInt().coerceIn(0, maxVol)
                                    // Can throw under Do-Not-Disturb; never let a drag crash playback.
                                    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                                    hud = "Volume ${target * 100 / maxVol}%"
                                } else if (activity != null) {
                                    val b = (startBright + frac).coerceIn(0.02f, 1f)
                                    val lp = activity.window.attributes
                                    lp.screenBrightness = b
                                    activity.window.attributes = lp
                                    hud = "Brightness ${(b * 100).toInt()}%"
                                }
                                c.consume()
                            } else if (mode == 5) {
                                // Swallowed volume drag while muted.
                                c.consume()
                            }
                        }
                    }
                }
            }.pointerInput(id) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { pos ->
                        val w = size.width
                        when {
                            // Double-tap the left/right third to skip 10s; middle toggles zoom.
                            pos.x < w / 3f -> {
                                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L))
                                hud = "− 10s"
                            }
                            pos.x > w * 2f / 3f -> {
                                player.seekTo(player.currentPosition + 10_000)
                                hud = "+ 10s"
                            }
                            else -> if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2f
                        }
                    },
                )
            },
        )

        hud?.let {
            Box(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xAA000000)).padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text(it, color = Color.White, fontSize = 15.sp) }
        }

        if (controlsVisible) {
            Box(
                Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape)
                    .background(Color(0x66000000))
                    .pointerInput(id) {
                        detectTapGestures(onTap = { if (isPlaying) player.pause() else player.play() })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }

            val shown = if (scrubbing) scrubMs else positionMs
            val dur = durationMs.coerceAtLeast(1L)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Scrim).navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                // Row 1 — scrubber gets the full width, so dragging is easy.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(shown), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = (shown.toFloat() / dur).coerceIn(0f, 1f),
                        onValueChange = { frac -> scrubbing = true; scrubMs = (frac * dur).toLong() },
                        onValueChangeFinished = { player.seekTo(scrubMs); scrubbing = false },
                        colors = SliderDefaults.colors(
                            thumbColor = Brass,
                            activeTrackColor = Brass,
                            inactiveTrackColor = Color(0x55FFFFFF),
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(fmt(durationMs), color = Color.White, fontSize = 12.sp)
                }

                // Row 2 — evenly spaced, comfortably sized actions.
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CtlIcon(Icons.Outlined.SkipPrevious, "Previous", Modifier.weight(1f), enabled = onPrev != null) {
                        onPrev?.invoke()
                    }
                    CtlIcon(Icons.Outlined.Replay10, "Back 10 seconds", Modifier.weight(1f)) {
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L))
                    }
                    CtlIcon(Icons.Outlined.Forward10, "Forward 10 seconds", Modifier.weight(1f)) {
                        player.seekTo(player.currentPosition + 10_000)
                    }
                    CtlIcon(Icons.Outlined.SkipNext, "Next", Modifier.weight(1f), enabled = onNext != null) {
                        onNext?.invoke()
                    }

                    // Mute is a standing setting, not a per-clip one: it persists
                    // and also switches off the swipe volume gesture.
                    CtlIcon(
                        if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                        if (muted) "Unmute" else "Mute",
                        Modifier.weight(1f),
                        tint = if (muted) Brass else Color(0x99FFFFFF),
                    ) {
                        muted = !muted
                        VideoPrefs.setMuted(context, muted)
                        hud = if (muted) "Muted" else "Sound on"
                    }

                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CtlText(speedLabel(SPEEDS[speedIdx]), tint = Brass) { speedMenu = true }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            SPEEDS.forEachIndexed { i, sp ->
                                DropdownMenuItem(
                                    text = { Text((if (i == speedIdx) "✓  " else "     ") + speedLabel(sp)) },
                                    onClick = { speedIdx = i; speedMenu = false },
                                )
                            }
                        }
                    }
                    // Loop is unavailable during a slideshow — it would stop the
                    // clip from ever ending, which is what advances the show.
                    CtlIcon(
                        Icons.Outlined.Repeat,
                        "Loop this clip",
                        Modifier.weight(1f),
                        tint = when {
                            slideshowActive -> Color(0x33FFFFFF)
                            loop -> Brass
                            else -> Color(0x99FFFFFF)
                        },
                        enabled = !slideshowActive,
                    ) { loop = !loop }
                }
            }
        }
    }
}

/** A roomy, evenly-spaced control-bar button (comfortable touch target). */
@Composable
private fun CtlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Color(0x33FFFFFF),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The speed control shows a value, so it stays text where an icon would lose meaning. */
@Composable
private fun CtlText(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}

private fun speedLabel(s: Float): String =
    if (s == s.toLong().toFloat()) "${s.toLong()}×" else "$s×"

private fun fmt(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
