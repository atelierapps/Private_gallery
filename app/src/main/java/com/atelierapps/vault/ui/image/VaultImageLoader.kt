package com.atelierapps.vault.ui.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy

/**
 * Builds the app-wide Coil [ImageLoader] (spec §8):
 *  - registers [VaultThumbFetcher] so `VaultMediaKey`s decrypt in memory,
 *  - **disk cache disabled** — it would write plaintext thumbnails,
 *  - memory cache enabled so scrolling and rebinds are instant.
 *
 * Installed via [com.atelierapps.vault.VaultApp] implementing
 * `SingletonImageLoader.Factory`. The memory cache is cleared on lock (§9).
 */
object VaultImageLoader {
    fun build(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VaultThumbFetcher.Factory(context), VaultMediaKey::class) }
            .memoryCachePolicy(CachePolicy.ENABLED) // enabled by default; explicit for intent
            .diskCachePolicy(CachePolicy.DISABLED)  // would write plaintext (§8)
            .build()
}
