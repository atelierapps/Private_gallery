package com.atelierapps.vault.session

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.atelierapps.vault.R

/**
 * What the app calls itself on the home screen.
 *
 * Each entry is an `activity-alias` in the manifest pointing at MainActivity,
 * carrying its own label and icon. Exactly one is enabled at a time, so the
 * launcher shows exactly one entry. Nothing about the install changes: the
 * package id stays com.atelierapps.vault, which is what the Keystore wrap key
 * is bound to — so switching disguise can never cost you the library.
 *
 * `preview` fields are the same drawable and colour the launcher will use, so
 * the picker shows the real icon rather than an approximation of it.
 */
enum class Disguise(
    val alias: String,
    val label: String,
    @DrawableRes val previewIcon: Int,
    @ColorRes val previewBackground: Int,
    @DrawableRes val launcherIcon: Int,
) {
    LINK(
        "Launcher.Link", "Link",
        R.drawable.ic_launcher_foreground, R.color.ic_launcher_background, R.mipmap.ic_launcher,
    ),
    NOTES(
        "Launcher.Notes", "Notes",
        R.drawable.ic_disguise_notes, R.color.icon_bg_notes, R.mipmap.ic_alias_notes,
    ),
    CALCULATOR(
        "Launcher.Calculator", "Calculator",
        R.drawable.ic_disguise_calculator, R.color.icon_bg_calculator, R.mipmap.ic_alias_calculator,
    ),
    WEATHER(
        "Launcher.Weather", "Weather",
        R.drawable.ic_disguise_weather, R.color.icon_bg_weather, R.mipmap.ic_alias_weather,
    ),
    FILES(
        "Launcher.Files", "Files",
        R.drawable.ic_disguise_files, R.color.icon_bg_files, R.mipmap.ic_alias_files,
    ),
    CLOCK(
        "Launcher.Clock", "Clock",
        R.drawable.ic_disguise_clock, R.color.icon_bg_clock, R.mipmap.ic_alias_clock,
    ),
}

object AppDisguise {

    /** The disguise in force, i.e. whichever alias the launcher is showing. */
    fun current(context: Context): Disguise {
        val pm = context.packageManager
        return Disguise.entries.firstOrNull { enabled(pm, component(context, it), it) } ?: Disguise.LINK
    }

    /** The name the app is going by — used for in-app copy so it stays coherent. */
    fun currentLabel(context: Context): String = current(context).label

    /**
     * Switches the launcher entry.
     *
     * Order matters and is not cosmetic: the replacement is enabled *before*
     * anything is disabled, so there is never an instant with no launcher entry.
     * If a switch somehow leaves nothing enabled, the default is restored rather
     * than leaving an app that can't be opened.
     */
    fun apply(context: Context, target: Disguise) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component(context, target),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        Disguise.entries.filter { it != target }.forEach {
            pm.setComponentEnabledSetting(
                component(context, it),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        // The Direct Share row carries its own copy of the name and icon.
        com.atelierapps.vault.share.ShareShortcut.publish(context)
        if (Disguise.entries.none { enabled(pm, component(context, it), it) }) {
            pm.setComponentEnabledSetting(
                component(context, Disguise.LINK),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun component(context: Context, disguise: Disguise) =
        ComponentName(context.packageName, "${context.packageName}.${disguise.alias}")

    private fun enabled(pm: PackageManager, component: ComponentName, disguise: Disguise): Boolean =
        when (runCatching { pm.getComponentEnabledSetting(component) }.getOrNull()) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            // Never explicitly set, so the manifest's own value stands — and
            // only the default alias ships enabled.
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> disguise == Disguise.LINK
            else -> false
        }
}
