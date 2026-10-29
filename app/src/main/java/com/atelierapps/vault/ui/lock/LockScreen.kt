package com.atelierapps.vault.ui.lock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.atelierapps.vault.session.AppDisguise
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted

/**
 * Shown while the app is locked (spec §9, §15.1). Full black — no blurred
 * grid — so a bystander gets no hint content exists. Biometrics auto-prompt on
 * open; this is the fallback surface if the prompt is dismissed.
 *
 * It shows the icon of whichever disguise is on the home screen, not a padlock.
 * A padlock is the one picture that says "something is hidden here"; the app's
 * own icon says only "this is the app you just opened", which is the point.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val disguise = remember { AppDisguise.current(context) }
    Box(
        modifier.fillMaxSize().background(Bg).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Drawn from the same foreground and colour the launcher uses, so it
            // is the real icon. Adaptive-icon XML can't go through
            // painterResource, which is why the parts are kept separately.
            Box(
                Modifier.padding(bottom = 10.dp).size(88.dp).clip(RoundedCornerShape(24.dp))
                    .background(colorResource(disguise.previewBackground)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(disguise.previewIcon),
                    contentDescription = null,
                    modifier = Modifier.size(132.dp),
                )
            }
            Text("Locked", color = Ink, style = MaterialTheme.typography.titleLarge)
            Text(
                "Unlock with your fingerprint or device PIN.",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onUnlock,
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BrassInk),
                modifier = Modifier.padding(top = 14.dp).height(52.dp),
            ) {
                Text("Unlock", modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
