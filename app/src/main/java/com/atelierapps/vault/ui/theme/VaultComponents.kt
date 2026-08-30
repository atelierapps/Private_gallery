package com.atelierapps.vault.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The app's icon button. One shape, one touch target, one press behaviour, so
 * every bar in the app feels like the same hand made it — and a real vector
 * instead of an emoji glyph, which renders differently on every device and is
 * the quickest way for an interface to look improvised.
 */
@Composable
fun VaultIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Ink,
    enabled: Boolean = true,
    size: Int = 40,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // A small give on press: cheap, but it's most of what makes taps feel physical.
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, tween(90), label = "press")

    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .selectable(
                selected = false,
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Faint,
            modifier = Modifier.size(21.dp).scale(scale),
        )
    }
}

/** Small tracked-out caps label that opens a group of settings or a menu section. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Brass,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}

/** A hairline separator at the app's standard weight and colour. */
@Composable
fun Separator(modifier: Modifier = Modifier) {
    Box(modifier.background(Hairline))
}
