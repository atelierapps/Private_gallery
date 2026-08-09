package com.atelierapps.vault.session

import android.content.Context

/**
 * Auto-lock delay preference (spec §9, §15.1). Default 15s — survives quick
 * app-switches (e.g. sharing from another app and back) while still tight.
 */
object LockPrefs {

    enum class Delay(val ms: Long, val label: String) {
        IMMEDIATE(0L, "Immediately"),
        S15(15_000L, "After 15 seconds"),
        S60(60_000L, "After 60 seconds"),
    }

    private const val PREFS = "vault_prefs"
    private const val KEY_DELAY = "lock_delay_ms"

    fun delayMs(context: Context): Long =
        prefs(context).getLong(KEY_DELAY, Delay.S15.ms)

    fun current(context: Context): Delay =
        Delay.entries.firstOrNull { it.ms == delayMs(context) } ?: Delay.S15

    fun set(context: Context, delay: Delay) {
        prefs(context).edit().putLong(KEY_DELAY, delay.ms).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
