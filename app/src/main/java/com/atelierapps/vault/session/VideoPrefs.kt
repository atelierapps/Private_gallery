package com.atelierapps.vault.session

import android.content.Context

/** Video playback preferences. Auto-play defaults OFF (open paused, tap to play). */
object VideoPrefs {

    private const val PREFS = "vault_prefs"
    private const val KEY_AUTOPLAY = "video_autoplay"
    private const val KEY_MUTED = "video_muted"
    private const val KEY_LOOP_SHORT = "video_loop_short"

    /** What counts as short enough to loop on its own. */
    const val LOOP_SHORT_MILLIS = 30_000L

    fun autoplay(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOPLAY, false)

    fun setAutoplay(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOPLAY, value).apply()
    }

    /**
     * Global mute. Persisted, because "keep it muted" is a standing intent — it
     * should survive closing the app, not just the current video. While set, the
     * swipe volume gesture is disabled too, so the mute can't be undone by a
     * stray drag.
     */
    fun muted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MUTED, false)

    fun setMuted(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUTED, value).apply()
    }

    /**
     * Loop clips under [LOOP_SHORT_MILLIS] without being asked. On by default:
     * a six-second clip that plays once and stops is the wrong behaviour for
     * most of what lives here, and it was a tap on every single one.
     */
    fun loopShort(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOOP_SHORT, true)

    fun setLoopShort(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOOP_SHORT, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
