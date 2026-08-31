package com.atelierapps.vault.ui.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.atelierapps.vault.media.NormalisedRect
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.Surface
import com.atelierapps.vault.ui.theme.VaultIconButton
import kotlin.math.abs
import kotlin.math.min
import androidx.compose.runtime.rememberUpdatedState

/**
 * Crop presets. Free insets the box so there is something to drag; the rest
 * reshape it to that ratio, centred and as large as fits.
 */
private val RATIOS = listOf(
    "Free" to null,
    "1:1" to 1f,
    "4:3" to 4f / 3f,
    "3:4" to 3f / 4f,
    "16:9" to 16f / 9f,
)

/**
 * Rotate and crop.
 *
 * Deliberately not a colour editor: this media arrived finished rather than
 * being shot here, so the useful operations are the structural ones — a
 * download that came in sideways, or a watermark and a banner to cut off.
 * Brightness and saturation would mostly produce a second, larger copy of
 * something you already have.
 */
@Composable
fun ImageEditScreen(
    state: EditState,
    onRotate: (Int) -> Unit,
    onCrop: (NormalisedRect) -> Unit,
    onSave: (replace: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Bg)) {
        ScreenHeader("Edit", onClose)

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val preview = state.preview
            when {
                state.saving -> CircularProgressIndicator(color = Brass)
                preview == null -> CircularProgressIndicator(color = Brass)
                else -> CropCanvas(
                    // Remembered against the source Bitmap: asImageBitmap()
                    // hands back a fresh wrapper each call, and this one is used
                    // as a gesture key downstream.
                    bitmap = remember(preview) { preview.asImageBitmap() },
                    crop = state.crop,
                    onCrop = onCrop,
                )
            }
        }

        Column(Modifier.fillMaxWidth().background(Surface).padding(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                RATIOS.forEach { (label, ratio) ->
                    FilterChip(
                        selected = false,
                        onClick = { onCrop(cropFor(ratio, state.previewAspect)) },
                        label = { Text(label) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VaultIconButton(Icons.Outlined.RotateLeft, "Rotate left", { onRotate(-90) })
                VaultIconButton(Icons.Outlined.RotateRight, "Rotate right", { onRotate(90) })
                TextButton(onClick = {
                    onRotate(-state.rotation)
                    onCrop(NormalisedRect.WHOLE)
                }) { Text("Reset", color = Muted) }
                Box(Modifier.weight(1f))
                TextButton(
                    onClick = { onSave(false) },
                    enabled = state.canSave,
                ) { Text("Save copy", color = if (state.canSave) Ink else Muted) }
                TextButton(
                    onClick = { onSave(true) },
                    enabled = state.canSave,
                ) { Text("Replace", color = if (state.canSave) Brass else Muted) }
            }

            Text(
                "Replace keeps the original in Recently deleted for 30 days — a crop " +
                    "can't be undone from the pixels, so that window is the only way back.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * The image with a draggable crop box over it.
 *
 * The box is held in 0..1 coordinates of the image, not pixels of the screen,
 * so rotating or resizing the preview doesn't move the frame the user chose.
 */
@Composable
private fun CropCanvas(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    crop: NormalisedRect,
    onCrop: (NormalisedRect) -> Unit,
) {
    // Padding so the image never reaches the edge of the touch area: a box at
    // full extent puts its handles exactly on the image border, and on the
    // border of the screen they cannot be hit.
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val canvasW = with(density) { maxWidth.toPx() }
        val canvasH = with(density) { maxHeight.toPx() }
        val scale = min(canvasW / bitmap.width, canvasH / bitmap.height)
        val drawnW = bitmap.width * scale
        val drawnH = bitmap.height * scale
        val originX = (canvasW - drawnW) / 2f
        val originY = (canvasH - drawnH) / 2f

        // Which corner a drag grabbed, or null for "move the whole box".
        var grabbed by remember { mutableStateOf<Int?>(null) }
        var moving by remember { mutableStateOf(false) }
        // The gesture handler outlives the value it was created with: without
        // this it would keep computing from the crop as it stood when the drag
        // started, so the box would jump back on every delta instead of
        // following the finger.
        val liveCrop by rememberUpdatedState(crop)

        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(
            // Keyed on the drawn size only. Keying on the bitmap restarted the
            // gesture detector on every recomposition — and every drag delta
            // causes one — so a drag was cancelled the instant it began, which
            // is why the box could not be moved or resized.
            Modifier.fillMaxSize().pointerInput(drawnW, drawnH) {
                val touchSlop = 48f
                detectDragGestures(
                    onDragStart = { start ->
                        val corners = cornerPoints(liveCrop, originX, originY, drawnW, drawnH)
                        val nearest = corners.withIndex().minByOrNull { (_, p) ->
                            abs(p.x - start.x) + abs(p.y - start.y)
                        }
                        val near = nearest != null &&
                            abs(nearest.value.x - start.x) < touchSlop &&
                            abs(nearest.value.y - start.y) < touchSlop
                        grabbed = if (near) nearest?.index else null
                        moving = !near && inside(liveCrop, start, originX, originY, drawnW, drawnH)
                    },
                    onDragEnd = { grabbed = null; moving = false },
                    onDragCancel = { grabbed = null; moving = false },
                ) { change, drag ->
                    change.consume()
                    val dx = if (drawnW > 0f) drag.x / drawnW else 0f
                    val dy = if (drawnH > 0f) drag.y / drawnH else 0f
                    val corner = grabbed
                    onCrop(
                        when {
                            corner != null -> resize(liveCrop, corner, dx, dy)
                            moving -> move(liveCrop, dx, dy)
                            else -> liveCrop
                        },
                    )
                }
            },
        ) {
            val l = originX + crop.left * drawnW
            val t = originY + crop.top * drawnH
            val r = originX + crop.right * drawnW
            val b = originY + crop.bottom * drawnH

            // Dim everything outside the frame, in four bands rather than a
            // clipped path — cheaper, and exact at the edges.
            val shade = Color(0x99000000)
            drawRect(shade, Offset(originX, originY), Size(drawnW, t - originY))
            drawRect(shade, Offset(originX, b), Size(drawnW, originY + drawnH - b))
            drawRect(shade, Offset(originX, t), Size(l - originX, b - t))
            drawRect(shade, Offset(r, t), Size(originX + drawnW - r, b - t))

            drawRect(
                color = Brass,
                topLeft = Offset(l, t),
                size = Size(r - l, b - t),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
            )
            val handle = 22f
            listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { p ->
                drawRect(
                    color = Brass,
                    topLeft = Offset(p.x - handle / 2, p.y - handle / 2),
                    size = Size(handle, handle),
                )
            }
        }
    }
}

// Corner order is fixed: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right.
private fun cornerPoints(
    crop: NormalisedRect,
    originX: Float,
    originY: Float,
    w: Float,
    h: Float,
): List<Offset> = listOf(
    Offset(originX + crop.left * w, originY + crop.top * h),
    Offset(originX + crop.right * w, originY + crop.top * h),
    Offset(originX + crop.left * w, originY + crop.bottom * h),
    Offset(originX + crop.right * w, originY + crop.bottom * h),
)

private fun inside(
    crop: NormalisedRect,
    p: Offset,
    originX: Float,
    originY: Float,
    w: Float,
    h: Float,
): Boolean {
    val x = (p.x - originX) / w
    val y = (p.y - originY) / h
    return x in crop.left..crop.right && y in crop.top..crop.bottom
}

/** Never let a frame collapse to nothing — 5% is the floor on both axes. */
private const val MIN_SIDE = 0.05f

/** How far Free pulls the box in from each edge, so it can be grabbed. */
private const val FREE_INSET = 0.1f

private fun resize(crop: NormalisedRect, corner: Int, dx: Float, dy: Float): NormalisedRect {
    var l = crop.left
    var t = crop.top
    var r = crop.right
    var b = crop.bottom
    when (corner) {
        0 -> { l += dx; t += dy }
        1 -> { r += dx; t += dy }
        2 -> { l += dx; b += dy }
        else -> { r += dx; b += dy }
    }
    l = l.coerceIn(0f, r - MIN_SIDE)
    t = t.coerceIn(0f, b - MIN_SIDE)
    r = r.coerceIn(l + MIN_SIDE, 1f)
    b = b.coerceIn(t + MIN_SIDE, 1f)
    return NormalisedRect(l, t, r, b)
}

private fun move(crop: NormalisedRect, dx: Float, dy: Float): NormalisedRect {
    val ddx = dx.coerceIn(-crop.left, 1f - crop.right)
    val ddy = dy.coerceIn(-crop.top, 1f - crop.bottom)
    return NormalisedRect(crop.left + ddx, crop.top + ddy, crop.right + ddx, crop.bottom + ddy)
}

/**
 * The largest centred box of [ratio] that fits the image, in normalised
 * coordinates. [imageAspect] is width/height of what's on screen, since a 1:1
 * box is only square in pixels if the normalised space is corrected for it.
 */
private fun cropFor(ratio: Float?, imageAspect: Float): NormalisedRect {
    // Free means "no fixed ratio", not "no crop". Returning the whole image
    // looked like the button did nothing, and left the handles pinned to the
    // image edge with nothing to take hold of.
    if (ratio == null) return NormalisedRect(FREE_INSET, FREE_INSET, 1f - FREE_INSET, 1f - FREE_INSET)
    if (imageAspect <= 0f) return NormalisedRect.WHOLE
    // Normalised width w and height h give a pixel ratio of (w * imageAspect) / h.
    // Solve for the largest w, h <= 1 with that ratio equal to `ratio`.
    var w = 1f
    var h = w * imageAspect / ratio
    if (h > 1f) {
        h = 1f
        w = h * ratio / imageAspect
    }
    val l = (1f - w) / 2f
    val t = (1f - h) / 2f
    return NormalisedRect(l, t, l + w, t + h)
}

/** Everything the edit screen renders from, so the activity holds no UI logic. */
data class EditState(
    val preview: android.graphics.Bitmap? = null,
    val rotation: Int = 0,
    val crop: NormalisedRect = NormalisedRect.WHOLE,
    val saving: Boolean = false,
) {
    val canSave: Boolean get() = preview != null && !saving
    val previewAspect: Float
        get() = preview?.let { if (it.height > 0) it.width.toFloat() / it.height else 1f } ?: 1f
}
