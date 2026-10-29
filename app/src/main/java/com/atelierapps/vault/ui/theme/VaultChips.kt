package com.atelierapps.vault.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * Every chip in the app goes through one of these two.
 *
 * Material's selected chip fills with `secondaryContainer`, which the theme
 * never mapped, so every selected chip — the filter toggles, the tag picker,
 * "Clear all" — drew in stock M3 purple-grey. Exactly the tell the theme exists
 * to prevent. The fix is a look, not just a colour, so it lives here once:
 *
 *  - at rest: a `SurfaceHigh` fill, no outline, `Ink` label;
 *  - selected: the same fill, a brass outline, a brass label and a check.
 *
 * Brass stays a line rather than a fill so a dozen picked tags don't turn the
 * screen brass — it only means something while it's rare.
 */

private val ChipBorder = 1.5.dp

/**
 * A chip that is on or off: filters, tags, single choices.
 *
 * [count] is drawn after the label in a quieter colour (the tag picker's live
 * counts). [showCheck] false is for a chip whose trailing icon already says
 * what tapping does — "Clear all ✕" doesn't also want a tick.
 */
@Composable
fun VaultChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    showCheck: Boolean = true,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                if (count != null) {
                    Text(
                        "" + count,
                        color = if (selected) Brass else Muted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
        leadingIcon = if (selected && showCheck) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else {
            null
        },
        trailingIcon = trailingIcon,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = SurfaceHigh,
            labelColor = Ink,
            iconColor = Muted,
            selectedContainerColor = SurfaceHigh,
            selectedLabelColor = Brass,
            selectedLeadingIconColor = Brass,
            selectedTrailingIconColor = Brass,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = SurfaceHigh,
            selectedBorderColor = Brass,
            borderWidth = ChipBorder,
            selectedBorderWidth = ChipBorder,
        ),
    )
}

/**
 * A chip that does something rather than holding a state: opens a menu
 * ([dropdown], with its ▾) or inserts a value. [active] outlines it in brass
 * when the menu behind it currently has a choice applied ("Last 7 days").
 */
@Composable
fun VaultActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dropdown: Boolean = false,
    active: Boolean = false,
) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text(label) },
        trailingIcon = if (dropdown) {
            { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = SurfaceHigh,
            labelColor = if (active) Brass else Ink,
            trailingIconContentColor = if (active) Brass else Muted,
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = if (active) Brass else SurfaceHigh,
            borderWidth = ChipBorder,
        ),
    )
}
