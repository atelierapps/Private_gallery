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

/**
 * Shown while the vault is locked (spec §9, §15.1). Full black — no blurred
 * grid — so a bystander gets no hint content exists. Biometrics auto-prompt on
 * open; this is the fallback surface if the prompt is dismissed.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(Color(0xFF0E1113)).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF171C20)),
                contentAlignment = Alignment.Center,
            ) {
                Text("🔒", fontSize = 34.sp)
            }
            Text("Vault is locked", color = Color(0xFFE9EEF0), fontSize = 20.sp)
            Text(
                "Unlock with your fingerprint or device PIN.",
                color = Color(0xFF8A969E),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onUnlock) { Text("Unlock") }
        }
    }
}
