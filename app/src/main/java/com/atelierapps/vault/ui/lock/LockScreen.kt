package com.atelierapps.vault.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.SurfaceHigh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Ink
import androidx.compose.material.icons.outlined.Lock

/**
 * Shown while the vault is locked (spec §9, §15.1). Full black — no blurred
 * grid — so a bystander gets no hint content exists. Biometrics auto-prompt on
 * open; this is the fallback surface if the prompt is dismissed.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(Bg).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Brass,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text("Locked", color = Ink, style = MaterialTheme.typography.titleLarge)
            Text(
                "Unlock with your fingerprint or device PIN.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onUnlock) { Text("Unlock") }
        }
    }
}
