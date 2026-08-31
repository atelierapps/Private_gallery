package com.atelierapps.vault.ui.grid

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Scrim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_HEIGHT = 44.dp
private val THUMB_WIDTH = 6.dp
private val GRAB_WIDTH = 34.dp

/**
 * Drag-to-scrub for a long grid.
 *
 * A few hundred items is a lot of flicking, and flicking tells you nothing
 * about where you've landed. The thumb only appears once the list is long
 * enough to be worth it, shows itself while you scroll, and gets out of the way
 * a moment after you stop — so a short library never sees it at all.
 *
 * [labelFor] is asked for the date to show beside the thumb while dragging.
 * Scrubbing a photo library without knowing what month you're passing through
 * is just a faster way to be lost.
 */
@Composable
fun BoxScope.FastScroller(
    state: LazyGridState,
    labelFor: (index: Int) -> String?,
    modifier: Modifier = Modifier,
    minItems: Int = 60,
) {
    val total by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    if (total < minItems) return

    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(true) }

    // Shown while you're scrolling or holding it; hidden a beat after you stop.
    // The delay restarts on every scroll, so it can't vanish mid-flick.
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) {
            idle = false
        } else {
            delay(1200)
            idle = true
        }
    }
    val visible = !idle
    val shown by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 120 else 400),
        label = "fastScrollFade",
    )

    val first by remember { derivedStateOf { state.firstVisibleItemIndex } }
    val density = LocalDensity.current

    // Full width, not just the track: the date bubble sits beside the thumb and
    // would be squeezed into 34dp if the container stopped at the track. Nothing
    // here takes touches except the thumb itself.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val trackPx = with(density) { (maxHeight - THUMB_HEIGHT).toPx() }.coerceAtLeast(0f)
        // Where the thumb sits, unless you're the one holding it — during a drag
        // the finger is the source of truth, not the list.
        var dragY by remember { mutableFloatStateOf(0f) }
        val restingY = if (total > 1) first.toFloat() / (total - 1) * trackPx else 0f
        val thumbY = if (dragging) dragY else restingY

        val interactive =
            if (visible) {
                Modifier.pointerInput(total, trackPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragY = if (total > 1) {
                                state.firstVisibleItemIndex.toFloat() / (total - 1) * trackPx
                            } else {
                                0f
                            }
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, delta ->
                        change.consume()
                        dragY = (dragY + delta).coerceIn(0f, trackPx)
                        val fraction = if (trackPx > 0f) dragY / trackPx else 0f
                        val target = (fraction * (total - 1)).roundToInt().coerceIn(0, total - 1)
                        scope.launch { state.scrollToItem(target) }
                    }
                }
            } else {
                Modifier
            }

        Box(
            Modifier
                .offset { IntOffset(0, thumbY.roundToInt()) }
                .align(Alignment.TopEnd)
                .width(GRAB_WIDTH)
                .height(THUMB_HEIGHT)
                .then(interactive),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier.width(THUMB_WIDTH).height(THUMB_HEIGHT).padding(vertical = 6.dp)
                    .alpha(shown)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (dragging) Brass else Ink),
            )
        }

        // The bubble rides with the thumb rather than sitting in a fixed corner,
        // so your eye doesn't have to leave your thumb to read it.
        val label = if (dragging) labelFor(first) else null
        if (label != null) {
            Box(
                Modifier
                    .offset { IntOffset(0, thumbY.roundToInt()) }
                    .align(Alignment.TopEnd)
                    .height(THUMB_HEIGHT)
                    .padding(end = GRAB_WIDTH + 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Brass)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(label, color = BrassInk, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
