package com.atelierapps.vault.share

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import coil3.compose.AsyncImage
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.media.SourceInfo
import com.atelierapps.vault.ui.theme.VaultTheme
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.SurfaceHigh
import androidx.compose.material3.MaterialTheme
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.session.AppDisguise
import androidx.compose.ui.platform.LocalContext

/**
 * The save bottom sheet (spec §5). Renders inside the transparent share Activity.
 * Reflects the locked v1 UI decisions (spec §15.3): quiet source line, six
 * quick-tag chips, "Save to Vault", confirm-to-save (not auto-save).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveSheet(
    previewUri: Uri,
    itemCount: Int,
    source: SourceInfo,
    loadTopTags: suspend () -> List<TagEntity>,
    onSave: (selectedTags: List<String>, onEnqueued: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    VaultTheme {
        var topTags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
        val selected = remember { mutableStateListOf<String>() }
        var saving by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { topTags = runCatching { loadTopTags() }.getOrDefault(emptyList()) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x9E06080A))
                .clickable(enabled = !saving) { onDismiss() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    // Consume taps so touching the sheet doesn't reach the scrim's dismiss.
                    .pointerInput(Unit) { detectTapGestures { } },
                color = SurfaceHigh,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            ) {
                Column(Modifier.navigationBarsPadding().padding(20.dp)) {
                    Text(
                        "Save to ${AppDisguise.currentLabel(LocalContext.current)}",
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Encrypts on save · no unlock needed",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = previewUri,
                            contentDescription = null,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Column(Modifier.padding(start = 13.dp)) {
                            Text(
                                if (itemCount > 1) "$itemCount items" else "1 item",
                                color = Ink,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            sourceLabel(source)?.let {
                                Text("From $it", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (topTags.isNotEmpty()) {
                        Text(
                            "QUICK TAGS",
                            color = Muted,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            topTags.forEach { tag ->
                                val on = selected.contains(tag.name)
                                FilterChip(
                                    selected = on,
                                    onClick = { if (on) selected.remove(tag.name) else selected.add(tag.name) },
                                    label = { Text(tag.name) },
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (saving) return@Button
                            saving = true
                            onSave(selected.toList()) {}
                        },
                        enabled = !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = BrassInk,
                            )
                            Text("  Saving…")
                        } else {
                            Text("Save to ${AppDisguise.currentLabel(LocalContext.current)}")
                        }
                    }
                }
            }
        }
    }
}

private fun sourceLabel(source: SourceInfo): String? =
    source.sourceLabel ?: source.sourceDomain
