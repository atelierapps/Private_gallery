package com.atelierapps.vault.session

import android.app.ActivityOptions
import android.os.Bundle
import android.view.View
import androidx.compose.ui.geometry.Rect

/**
 * Where the viewer should look like it came from.
 *
 * Tapping a thumbnail records that tile's on-screen rectangle here, and the
 * activity launching the viewer turns it into a window animation — so the photo
 * grows out of the tile you touched rather than the whole screen cross-fading.
 * It is the single cheapest thing that makes opening an image feel like direct
 * manipulation instead of navigation.
 *
 * Deliberately a global, matching [ViewerSession]: the alternative is threading
 * a Rect through five composable signatures that have no other interest in it.
 * Always consumed on read, so a stale anchor can never animate the wrong launch.
 */
object TileAnchor {

    private var bounds: Rect? = null

    fun record(rect: Rect) {
        bounds = rect
    }

    /** Forget any pending anchor — for launches that didn't start at a tile. */
    fun clear() {
        bounds = null
    }

    /**
     * Consumes the pending anchor as `startActivity` options, or null when there
     * isn't one, which leaves the theme's normal screen transition in charge.
     */
    fun consumeOptions(host: View): Bundle? {
        val rect = bounds ?: return null
        bounds = null
        if (rect.width < 1f || rect.height < 1f) return null
        return runCatching {
            ActivityOptions.makeScaleUpAnimation(
                host,
                rect.left.toInt(),
                rect.top.toInt(),
                rect.width.toInt(),
                rect.height.toInt(),
            ).toBundle()
        }.getOrNull()
    }
}
