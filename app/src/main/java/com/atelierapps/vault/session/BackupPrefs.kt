package com.atelierapps.vault.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * When the vault was last fully backed up, and whether the user has been told
 * what that means.
 *
 * This matters more here than in most apps. Everything lives in
 * `filesDir` — app-private internal storage — and the key that decrypts it is
 * an Android Keystore entry bound to this install. Uninstalling deletes both,
 * and `allowBackup="false"` means no cloud or adb copy exists either. There is
 * no recovery path: not a support line, not a re-login, nothing. An export is
 * the only copy that survives, so the app owes the user a plain statement of
 * that rather than letting them find out afterwards.
 *
 * Only a *full* export counts. A scoped one is a share, not a backup, and
 * recording it would tell a comfortable lie.
 */
object BackupPrefs {

    private const val PREFS = "vault_prefs"
    private const val KEY_LAST = "backup_last_millis"
    private const val KEY_WARNED = "backup_warned"

    /** 0 means never. */
    val lastBackupAtMillis = MutableStateFlow(0L)

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        lastBackupAtMillis.value = prefs(context).getLong(KEY_LAST, 0L)
        loaded = true
    }

    fun recordFullBackup(context: Context) {
        val now = System.currentTimeMillis()
        lastBackupAtMillis.value = now
        prefs(context).edit().putLong(KEY_LAST, now).apply()
    }

    /** Has the one-time "here's what you stand to lose" notice been shown? */
    fun warned(context: Context): Boolean = prefs(context).getBoolean(KEY_WARNED, false)

    fun setWarned(context: Context) {
        prefs(context).edit().putBoolean(KEY_WARNED, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
