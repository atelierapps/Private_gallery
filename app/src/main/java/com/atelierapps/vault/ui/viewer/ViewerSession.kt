package com.atelierapps.vault.ui.viewer

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hands the viewer the exact ordered set the grid is currently showing (after
 * filter / search / tag / sort / pin), so paging and slideshow play *in that
 * context* — e.g. filter by a tag, open an item, and play only that tag's media.
 * Set by the grid just before launching the viewer; null falls back to all media.
 */
object ViewerSession {
    @Volatile
    var orderedIds: List<String>? = null

    /**
     * Last playback position per video id, so reopening a clip resumes where you
     * left off. In-memory only (cleared when the process dies) — deliberately not
     * persisted, so watch positions never touch disk in plaintext.
     */
    val positions = HashMap<String, Long>()

    /**
     * Playback speed, sticky for the whole viewer session: set it once and it
     * carries to every video you move to (including slideshow advances) instead
     * of snapping back to 1x. In-memory, so a fresh app launch starts at 1x.
     */
    @Volatile
    var playbackSpeed: Float = 1f

    /**
     * Set by Shuffle so the viewer opens already playing through the order it was
     * handed. Consumed once on open — a later manual visit shouldn't inherit it.
     */
    @Volatile
    var startPlaying: Boolean = false

    /**
     * The item the viewer is showing. The grid watches this and scrolls to match,
     * so paging from item 5 to item 300 and coming back doesn't drop you at item
     * 5 again. A flow rather than a plain field because the grid is still
     * composed underneath the viewer and can follow along live — by the time you
     * are back, it is already in the right place.
     */
    val lastViewedId = MutableStateFlow<String?>(null)

    fun consumeStartPlaying(): Boolean {
        val v = startPlaying
        startPlaying = false
        return v
    }

    /**
     * The item the viewer was showing when the vault locked under it, so
     * unlocking can put you back rather than dropping you on the grid. Consumed
     * once — coming back later by hand shouldn't reopen it.
     */
    @Volatile
    var resumeId: String? = null

    fun consumeResume(): String? {
        val v = resumeId
        resumeId = null
        return v
    }

    /**
     * Drop per-session playback state. Called when the process is done with a
     * session, **not** when the vault merely locks: locking used to wipe watch
     * positions and the current item, which is why locking mid-video and
     * unlocking landed you at the top of the grid with the position gone. None
     * of this is ever written to disk, so keeping it across a lock costs
     * nothing an attacker who can unlock doesn't already have.
     */
    fun clear() {
        lastViewedId.value = null
        resumeId = null
        positions.clear()
        playbackSpeed = 1f
        startPlaying = false
    }
}
