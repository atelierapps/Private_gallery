package com.atelierapps.vault.ui.lock

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.atelierapps.vault.session.LockButtonPrefs
import com.atelierapps.vault.session.VaultSession
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Scrim
import kotlin.math.roundToInt

private val BUTTON = 46.dp
private val GAP = 10.dp

/**
 * A lock you can reach without going anywhere.
 *
 * "Lock now" lives at the bottom of an overflow menu, which is fine for
 * deliberate use and useless for the case that matters — someone is reading
 * over your shoulder right now. This sits on top of whatever you're looking at,
 * dimmed to whatever you can live with, and drags to wherever your thumb
 * actually is.
 *
 * It can carry a second button, "back to top", for a long grid. The two are
 * one piece rather than two floating things: the arrow docks directly above
 * the lock (below it, when the lock is parked against the top edge), dragging
 * either moves both, and so they can never land on each other and there is
 * only one place to remember. With the lock switched off in Settings the
 * arrow still appears, on its own, where the lock would be.
 *
 * @param visible drives the fade. Over media the caller drops this after a
 *   couple of seconds so the button isn't sitting on the photo the whole time;
 *   the tap target goes with it, so a faded button can't be hit by accident.
 * @param showTop whether the "back to top" arrow is out. It fades with
 *   [visible] too.
 */
@Composable
fun FloatingLockButton(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onLocked: () -> Unit = {},
    showTop: Boolean = false,
    onTop: () -> Unit = {},
) {
    val context = LocalContext.current
    val enabled by LockButtonPrefs.enabled.collectAsState()
    val opacity by LockButtonPrefs.opacity.collectAsState()
    val topOut = visible && showTop

    val shown by animateFloatAsState(
        targetValue = if (visible) opacity else 0f,
        animationSpec = tween(durationMillis = if (visible) 160 else 450),
        label = "lockButtonFade",
    )
    val topShown by animateFloatAsState(
        targetValue = if (topOut) opacity else 0f,
        animationSpec = tween(durationMillis = if (topOut) 200 else 300),
        label = "topButtonFade",
    )
    if (!enabled && !topOut && topShown == 0f) return

    // Read at tap time, not captured when the gesture was set up.
    val currentOnTop by rememberUpdatedState(onTop)
    val currentOnLocked by rememberUpdatedState(onLocked)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Kept in pixels while dragging and written back as a fraction, so the
        // button lands in the same place after a rotation or a window resize.
        val maxX = with(density) { (maxWidth - BUTTON).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - BUTTON).toPx() }.coerceAtLeast(0f)
        val step = with(density) { (BUTTON + GAP).toPx() }
        val startX by LockButtonPrefs.posX.collectAsState()
        val startY by LockButtonPrefs.posY.collectAsState()

        var x by remember(maxX) { mutableFloatStateOf(startX * maxX) }
        var y by remember(maxY) { mutableFloatStateOf(startY * maxY) }

        // One drag for the pair, whichever button it starts on.
        val drag = Modifier.pointerInput(maxX, maxY) {
            detectDragGestures(
                onDragEnd = {
                    LockButtonPrefs.setPosition(
                        context,
                        if (maxX > 0f) x / maxX else 0f,
                        if (maxY > 0f) y / maxY else 0f,
                    )
                },
            ) { change, d ->
                change.consume()
                x = (x + d.x).coerceIn(0f, maxX)
                y = (y + d.y).coerceIn(0f, maxY)
            }
        }

        if (enabled) {
            // Gestures come and go with visibility rather than being guarded
            // inside the handlers: a button faded to nothing must not still be
            // a live target sitting over the photo, for a tap or a drag.
            val interactive =
                if (visible) {
                    drag.pointerInput(Unit) {
                        detectTapGestures {
                            VaultSession.lock()
                            runCatching { SingletonImageLoader.get(context).memoryCache?.clear() }
                            currentOnLocked()
                        }
                    }
                } else {
                    Modifier
                }
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(BUTTON)
                    .alpha(shown)
                    .clip(CircleShape)
                    .background(Scrim)
                    .then(interactive),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = "Lock now",
                    tint = Brass,
                    modifier = Modifier.size(21.dp),
                )
            }
        }

        if (topOut || topShown > 0f) {
            val topY = when {
                !enabled -> y
                y - step >= 0f -> y - step
                else -> y + step
            }
            val interactive =
                if (topOut) {
                    drag.pointerInput(Unit) { detectTapGestures { currentOnTop() } }
                } else {
                    Modifier
                }
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), topY.roundToInt()) }
                    .size(BUTTON)
                    .alpha(topShown)
                    .clip(CircleShape)
                    .background(Scrim)
                    .then(interactive),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "Back to top",
                    tint = Ink,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}
