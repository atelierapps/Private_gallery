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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
import com.atelierapps.vault.ui.theme.Scrim
import kotlin.math.roundToInt

private val BUTTON = 46.dp

/**
 * A lock you can reach without going anywhere.
 *
 * "Lock now" lives at the bottom of an overflow menu, which is fine for
 * deliberate use and useless for the case that matters — someone is reading
 * over your shoulder right now. This sits on top of whatever you're looking at,
 * dimmed to whatever you can live with, and drags to wherever your thumb
 * actually is.
 *
 * @param visible drives the fade. Over media the caller drops this after a
 *   couple of seconds so the button isn't sitting on the photo the whole time;
 *   the tap target goes with it, so a faded button can't be hit by accident.
 */
@Composable
fun FloatingLockButton(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onLocked: () -> Unit = {},
) {
    val context = LocalContext.current
    val enabled by LockButtonPrefs.enabled.collectAsState()
    val opacity by LockButtonPrefs.opacity.collectAsState()
    if (!enabled) return

    val shown by animateFloatAsState(
        targetValue = if (visible) opacity else 0f,
        animationSpec = tween(durationMillis = if (visible) 160 else 450),
        label = "lockButtonFade",
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Kept in pixels while dragging and written back as a fraction, so the
        // button lands in the same place after a rotation or a window resize.
        val maxX = with(density) { (maxWidth - BUTTON).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - BUTTON).toPx() }.coerceAtLeast(0f)
        val startX by LockButtonPrefs.posX.collectAsState()
        val startY by LockButtonPrefs.posY.collectAsState()

        var x by remember(maxX) { mutableFloatStateOf(startX * maxX) }
        var y by remember(maxY) { mutableFloatStateOf(startY * maxY) }

        // Gestures come and go with visibility rather than being guarded inside
        // the handlers: a button faded to nothing must not still be a live
        // target sitting over the photo, for a tap or a drag.
        val interactive =
            if (visible) {
                Modifier
                    .pointerInput(maxX, maxY) {
                        detectDragGestures(
                            onDragEnd = {
                                LockButtonPrefs.setPosition(
                                    context,
                                    if (maxX > 0f) x / maxX else 0f,
                                    if (maxY > 0f) y / maxY else 0f,
                                )
                            },
                        ) { change, drag ->
                            change.consume()
                            x = (x + drag.x).coerceIn(0f, maxX)
                            y = (y + drag.y).coerceIn(0f, maxY)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures {
                            VaultSession.lock()
                            runCatching { SingletonImageLoader.get(context).memoryCache?.clear() }
                            onLocked()
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
}
