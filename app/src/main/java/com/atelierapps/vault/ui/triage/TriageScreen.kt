package com.atelierapps.vault.ui.triage

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.atelierapps.vault.R
import com.atelierapps.vault.crypto.VaultCtrDataSource
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.session.VideoPrefs
import com.atelierapps.vault.ui.image.VaultMediaKey
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Danger
import com.atelierapps.vault.ui.theme.Hairline
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.Surface
import com.atelierapps.vault.ui.theme.TagPicker

/**
 * One untagged item, its tags open beneath it, three ways on: Delete, Skip,
 * Tag & next. Videos play straight away, muted if that's the setting, and
 * loop — you're deciding what it is, not watching it.
 */
@UnstableApi
@Composable
fun TriageScreen(
    state: TriageState,
    allTags: List<TagEntity>,
    onTogglePick: (String) -> Unit,
    onText: (String) -> Unit,
    onSaveTags: () -> Unit,
    onSkip: () -> Unit,
    onDelete: () -> Unit,
    onUndoDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = state.queue.size
    val title = when {
        !state.loaded -> "Sort untagged"
        state.done || total == 0 -> "Sort untagged"
        else -> "Untagged · " + (state.index + 1) + " of " + total
    }
    Column(modifier.fillMaxSize().background(Bg)) {
        ScreenHeader(title, onClose)
        val item = state.current
        when {
            !state.loaded -> Unit
            item == null -> Finished(state, total, onClose)
            else -> {
                Box(
                    Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.media.mimeType.startsWith("video/")) {
                        TriageVideo(item.media.id)
                    } else {
                        AsyncImage(
                            model = VaultMediaKey(item.media.id, full = true),
                            contentDescription = item.media.originalName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().background(Surface).imePadding()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                ) {
                    Text(
                        item.media.originalName,
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TagPicker(
                        tags = allTags,
                        isPicked = { name -> state.picked.any { it.equals(name, ignoreCase = true) } },
                        onToggle = onTogglePick,
                        text = state.text,
                        onTextChange = onText,
                        compact = true,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (state.undoId != null) {
                        TextButton(onClick = onUndoDelete, modifier = Modifier.padding(top = 2.dp)) {
                            Text("Undo delete", color = Brass)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onDelete,
                            colors = ButtonDefaults.buttonColors(containerColor = Hairline, contentColor = Danger),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("Delete") }
                        Button(
                            onClick = onSkip,
                            colors = ButtonDefaults.buttonColors(containerColor = Hairline, contentColor = Ink),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("Skip") }
                        val anything = state.picked.isNotEmpty() || state.text.isNotBlank()
                        Button(
                            onClick = onSaveTags,
                            enabled = anything,
                            colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BrassInk),
                            modifier = Modifier.weight(1.4f).height(48.dp),
                        ) { Text("Tag & next") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Finished(state: TriageState, total: Int, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (total == 0) "Nothing untagged" else "All sorted",
            color = Ink,
            style = MaterialTheme.typography.headlineMedium,
        )
        val skipped = (total - state.tagged - state.binned).coerceAtLeast(0)
        Text(
            if (total == 0) {
                "Everything here already has at least one tag."
            } else {
                "" + state.tagged + " tagged · " + skipped + " skipped · " + state.binned + " deleted"
            },
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BrassInk),
            modifier = Modifier.padding(top = 20.dp).height(48.dp),
        ) { Text("Done") }
    }
}

/** Plays as soon as it's shown, loops, and honours the mute setting. */
@UnstableApi
@Composable
private fun TriageVideo(id: String) {
    val context = LocalContext.current
    val player = remember(id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(VaultCtrDataSource.Factory(context)))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(VaultCtrDataSource.uriFor(id)))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (VideoPrefs.muted(context)) 0f else 1f
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.vault_player_view, null) as PlayerView)
                .apply { this.player = player }
        },
        // A new item is a new player; point the reused view at it.
        update = { view -> view.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}
