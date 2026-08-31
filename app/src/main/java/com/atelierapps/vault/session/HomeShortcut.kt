package com.atelierapps.vault.session

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.atelierapps.vault.ui.MainActivity

/**
 * A home-screen icon named whatever you want.
 *
 * The launcher's own entry can only carry a label declared in the manifest, so
 * [Disguise] is limited to a fixed set. A pinned shortcut isn't: its label is
 * yours to type. It points straight at MainActivity, so it keeps working
 * whichever alias is currently enabled.
 *
 * This adds an icon; it doesn't replace the drawer entry, which still shows the
 * disguise you picked.
 */
object HomeShortcut {

    fun supported(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /**
     * Asks the launcher to place the shortcut. Returns false if it declined
     * outright — some launchers require a separate permission and will refuse
     * quietly, so a true here means "asked", not "placed".
     */
    fun pin(context: Context, label: String, disguise: Disguise): Boolean {
        val clean = label.trim()
        if (clean.isEmpty()) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        // A fresh id per pin, so you can place more than one.
        val shortcut = ShortcutInfoCompat.Builder(context, "home_${System.currentTimeMillis()}")
            .setShortLabel(clean)
            .setLongLabel(clean)
            .setIcon(IconCompat.createWithResource(context, disguise.launcherIcon))
            .setIntent(intent)
            .build()
        return runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrDefault(false)
    }
}
