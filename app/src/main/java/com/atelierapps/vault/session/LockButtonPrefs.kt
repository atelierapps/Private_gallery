package com.atelierapps.vault.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The floating lock button: whether it's shown, how visible it is, and where
 * you put it.
 *
 * Held as flows rather than read from SharedPreferences at each composition, so
 * changing a setting takes effect on the screen behind it — Settings is its own
 * activity, and the grid underneath won't recompose for a value it never
 * observed. Prefs stay the durable copy; these are the live one.
 */
object LockButtonPrefs {

    private const val PREFS = "vault_prefs"
    private const val KEY_ENABLED = "lockbtn_enabled"
    private const val KEY_OPACITY = "lockbtn_opacity"
    private const val KEY_X = "lockbtn_x"
    private const val KEY_Y = "lockbtn_y"

    const val MIN_OPACITY = 0.1f
    const val MAX_OPACITY = 1f

    val enabled = MutableStateFlow(true)
    val opacity = MutableStateFlow(0.45f)

    /**
     * Position as a fraction of the screen, not pixels — so the button stays
     * where you put it when the phone rotates or the window changes size.
     */
    val posX = MutableStateFlow(0.05f)
    val posY = MutableStateFlow(0.78f)

    private var loaded = false

    /** Called once at startup, before anything reads the flows. */
    fun load(context: Context) {
        if (loaded) return
        val p = prefs(context)
        enabled.value = p.getBoolean(KEY_ENABLED, true)
        opacity.value = p.getFloat(KEY_OPACITY, 0.45f).coerceIn(MIN_OPACITY, MAX_OPACITY)
        posX.value = p.getFloat(KEY_X, 0.05f).coerceIn(0f, 1f)
        posY.value = p.getFloat(KEY_Y, 0.78f).coerceIn(0f, 1f)
        loaded = true
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled.value = value
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun setOpacity(context: Context, value: Float) {
        val v = value.coerceIn(MIN_OPACITY, MAX_OPACITY)
        opacity.value = v
        prefs(context).edit().putFloat(KEY_OPACITY, v).apply()
    }

    fun setPosition(context: Context, x: Float, y: Float) {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        posX.value = cx
        posY.value = cy
        prefs(context).edit().putFloat(KEY_X, cx).putFloat(KEY_Y, cy).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
