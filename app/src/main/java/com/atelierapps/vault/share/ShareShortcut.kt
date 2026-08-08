package com.atelierapps.vault.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.atelierapps.vault.R

/**
 * Publishes a long-lived dynamic sharing shortcut so Vault surfaces in the top
 * row of the share sheet (Direct Share), not the alphabetical list (spec §5).
 * The category must match the `<share-target>` in `res/xml/shortcuts.xml`.
 */
object ShareShortcut {
    private const val ID = "save_to_vault"
    private const val CATEGORY = "com.atelierapps.vault.category.SAVE_TO_VAULT"

    fun publish(context: Context) {
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(context.getString(R.string.app_name))
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(Intent.ACTION_VIEW))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }
}
