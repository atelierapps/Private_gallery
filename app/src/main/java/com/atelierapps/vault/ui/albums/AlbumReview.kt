package com.atelierapps.vault.ui.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.ui.grid.VaultGridScreen
import com.atelierapps.vault.ui.home.GridPreview
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Hairline
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.Surface

/**
 * New items carrying a tag-following album's tags, to take in or turn down.
 *
 * Everything starts ticked, because the tags already say these belong: the
 * work is unticking the few that don't. Tap toggles; long-press then slide
 * down previews, as on the grid. Unticked items aren't offered again —
 * leaving without adding records nothing, so the offer stays for next time.
 *
 * Drawn over the album in its own window, so it is under FLAG_SECURE with
 * everything else and the preview's gesture works the same.
 */
@UnstableApi
@Composable
fun AlbumReview(
    albumName: String,
    rule: AlbumTagRule,
    items: List<MediaWithTags>,
    onDone: (offered: List<String>, chosen: Set<String>) -> Unit,
    onClose: () -> Unit,
    /** Open one in the viewer, paging through just these; the review waits underneath. */
    onOpenItem: (id: String, order: List<String>) -> Unit,
) {
    // The offer as it stood when opened, so an import landing meanwhile
    // can't slip an unseen item into "turned down".
    val offered = remember { items.map { it.media.id } }
    val shown = remember { items }
    var chosen by remember { mutableStateOf(offered.toSet()) }
    var previewing by remember { mutableStateOf<MediaWithTags?>(null) }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("New for $albumName", onClose)
            Text(
                "Tagged " + rule.describe() + ". Untick what doesn't belong — it won't be offered again.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            VaultGridScreen(
                media = shown,
                selectionMode = true,
                selectedIds = chosen,
                onOpen = {},
                // Long-press does nothing here; carrying on into a flick
                // previews, and there's no selection to undo.
                onLongPress = {},
                onToggleSelect = { id -> chosen = if (id in chosen) chosen - id else chosen + id },
                modifier = Modifier.weight(1f),
                onPreview = { id -> previewing = shown.firstOrNull { it.media.id == id } },
            )
            Row(
                Modifier.fillMaxWidth().background(Surface).navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { chosen = if (chosen.size == offered.size) emptySet() else offered.toSet() },
                    modifier = Modifier.height(48.dp),
                ) { Text(if (chosen.size == offered.size) "None" else "All", color = Ink) }
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Hairline, contentColor = Ink),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Later") }
                Button(
                    onClick = { onDone(offered, chosen) },
                    colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BrassInk),
                    modifier = Modifier.weight(1.4f).height(48.dp),
                ) { Text(if (chosen.isEmpty()) "Skip all" else "Add " + chosen.size) }
            }
        }
        previewing?.let { item ->
            GridPreview(
                item = item,
                onOpen = {
                    previewing = null
                    onOpenItem(item.media.id, offered)
                },
                onClose = { previewing = null },
            )
        }
    }
}
