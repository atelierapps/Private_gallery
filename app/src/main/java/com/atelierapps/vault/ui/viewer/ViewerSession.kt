package com.atelierapps.vault.ui.viewer

/**
 * Hands the viewer the exact ordered set the grid is currently showing (after
 * filter / search / tag / sort / pin), so paging and slideshow play *in that
 * context* — e.g. filter by a tag, open an item, and play only that tag's media.
 * Set by the grid just before launching the viewer; null falls back to all media.
 */
object ViewerSession {
    @Volatile
    var orderedIds: List<String>? = null
}
