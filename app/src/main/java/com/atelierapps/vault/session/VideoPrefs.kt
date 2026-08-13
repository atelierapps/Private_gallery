package com.atelierapps.vault.session

import android.content.Context

/** Video playback preferences. Auto-play defaults OFF (open paused, tap to play). */
object VideoPrefs {

    private const val PREFS = "vault_prefs"
    private const val KEY_AUTOPLAY = "video_autoplay"

    fun autoplay(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOPLAY, false)

    fun setAutoplay(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOPLAY, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
