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
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.BrightnessLow
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

// Wide enough that a thumb reaching for one doesn't land on the other:
// the lock is the one button here you must never hit by accident.
private val GAP = 16.dp

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
 * @param lowLight the viewer's low-light stage (0 system, 1 low, 2 ultra
 *   low), or null to leave the button off. Docked above the lock the same way
 *   as the arrow, and above the arrow if both are ever shown.
 */
@Composable
fun FloatingLockButton(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onLocked: () -> Unit = {},
    showTop: Boolean = false,
    onTop: () -> Unit = {},
    lowLight: Int? = null,
    onLowLight: () -> Unit = {},
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
    if (!enabled && !topOut && topShown == 0f && lowLight == null) return

    // Read at tap time, not captured when the gesture was set up.
    val currentOnTop by rememberUpdatedState(onTop)
    val currentOnLowLight by rememberUpdatedState(onLowLight)
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

        // The companions stack above the lock (below it if the lock is parked
        // against the top edge), one slot each; with the lock switched off they
        // start where it would be.
        val arrowOn = topOut || topShown > 0f
        val slots = (if (arrowOn) 1 else 0) + (if (lowLight != null) 1 else 0)
        val above = y - slots * step >= 0f
        val slotY: (Int) -> Float = { i ->
            val n = if (enabled) i + 1 else i
            if (above) y - n * step else y + n * step
        }

        if (arrowOn) {
            val interactive =
                if (topOut) {
                    drag.pointerInput(Unit) { detectTapGestures { currentOnTop() } }
                } else {
                    Modifier
                }
            DockButton(x, slotY(0), topShown, interactive) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "Back to top",
                    tint = Ink,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        if (lowLight != null) {
            val interactive =
                if (visible) {
                    drag.pointerInput(Unit) { detectTapGestures { currentOnLowLight() } }
                } else {
                    Modifier
                }
            DockButton(x, slotY(if (arrowOn) 1 else 0), shown, interactive) {
                // Sun, dimmer sun, moon: what the screen is doing now. White
                // at the phone's own brightness, brass once it's dimmed.
                Icon(
                    when (lowLight) {
                        1 -> Icons.Outlined.BrightnessLow
                        2 -> Icons.Outlined.DarkMode
                        else -> Icons.Outlined.LightMode
                    },
                    contentDescription = when (lowLight) {
                        1 -> "Low light — tap for ultra low"
                        2 -> "Ultra low light — tap for normal"
                        else -> "Normal brightness — tap for low light"
                    },
                    tint = if (lowLight == 0) Ink else Brass,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** One round button in the dock, drawn like the lock. */
@Composable
private fun DockButton(
    x: Float,
    y: Float,
    alpha: Float,
    interactive: Modifier,
    icon: @Composable () -> Unit,
) {
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(BUTTON)
            .alpha(alpha)
            .clip(CircleShape)
            .background(Scrim)
            .then(interactive),
        contentAlignment = Alignment.Center,
    ) { icon() }
}
