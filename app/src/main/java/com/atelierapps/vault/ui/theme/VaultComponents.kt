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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding

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

/**
 * Every secondary screen's title bar. Before this, seven screens each declared
 * their own header and had already drifted to two different title sizes and two
 * different back affordances (one was a bare `‹` character). One header, one
 * height, one type style — the app now opens the same way every time.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Close,
    backDescription: String = "Close",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = Space.xs, end = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VaultIconButton(icon, backDescription, onBack)
        Text(
            title,
            color = Ink,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = Space.xs),
        )
        actions()
    }
}

/** The supporting line that sits under a [ScreenHeader] and explains the screen. */
@Composable
fun ScreenCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.sm),
    )
}

/** A text action for a header or bar — brass, label weight, one size everywhere. */
@Composable
fun HeaderAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text, color = if (enabled) Brass else Faint, style = MaterialTheme.typography.labelLarge)
    }
}
