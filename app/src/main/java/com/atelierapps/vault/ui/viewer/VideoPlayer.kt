package com.atelierapps.vault.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.atelierapps.vault.crypto.VaultCtrDataSource

/**
 * Plays an encrypted vault video (spec §9) through ExoPlayer, decrypting via
 * [VaultCtrDataSource] so no plaintext touches disk. Standard Media3 controls
 * (play/pause, scrub) are provided by [PlayerView]; scrubbing works because the
 * CTR data source is seekable.
 */
@UnstableApi
@Composable
fun VideoPlayer(id: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(VaultCtrDataSource.Factory(context)))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(VaultCtrDataSource.uriFor(id)))
                prepare()
                playWhenReady = false
            }
    }
    DisposableEffect(id) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = modifier,
    )
}
