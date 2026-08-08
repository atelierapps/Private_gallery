package com.atelierapps.vault.ui.viewer

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
 * CTR data source is seekable. The surface is black end-to-end and controls
 * auto-hide after 2.5 s.
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
    Box(modifier.background(androidx.compose.ui.graphics.Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 2500       // fade controls out after 2.5 s
                    controllerAutoShow = true
                    setShutterBackgroundColor(Color.BLACK)
                    setBackgroundColor(Color.BLACK)
                }
            },
            // Inset the player (and thus its controls) above the system nav bar,
            // so the buttons don't sit behind the home/gesture bar.
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        )
    }
}
