package com.atelierapps.vault.share

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.atelierapps.vault.data.entity.SourceType
import com.atelierapps.vault.media.SourceInfo

/**
 * Captures where a shared item came from (spec §6). Never typed, never guessed:
 *  - `referrer` (android-app://<pkg>) is the primary signal; its display label
 *    is resolved and **cached at save time** because the source app may be
 *    uninstalled later.
 *  - Browser shares put the page URL in EXTRA_TEXT → we keep the **host only**
 *    (spec §2.1), never the full URL.
 *  - No referrer and no URL → UNKNOWN. A wrong source is worse than none.
 */
object SourceAttribution {

    fun capture(referrer: Uri?, intent: Intent, packageManager: PackageManager): SourceInfo {
        val pkg = referrer
            ?.takeIf { it.scheme == "android-app" }
            ?.host
            ?.takeIf { it.isNotBlank() }

        val label = pkg?.let { resolveLabel(it, packageManager) }
        val domain = hostOf(intent.getStringExtra(Intent.EXTRA_TEXT))

        val type = if (pkg != null || domain != null) SourceType.SHARE else SourceType.UNKNOWN
        return SourceInfo(
            sourceType = type,
            sourcePackage = pkg,
            sourceLabel = label,
            sourceDomain = domain,
        )
    }

    private fun resolveLabel(pkg: String, pm: PackageManager): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /**
     * Extract a bare host from shared text, if it contains a URL. Pure and
     * unit-tested ([SourceAttributionTest]). Strips a leading `www.`.
     */
    fun hostOf(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val token = text.split(Regex("\\s+")).firstOrNull { it.contains("://") } ?: return null
        // java.net.URI (not android.net.Uri) so this stays unit-testable off-device.
        val host = runCatching { java.net.URI(token).host }.getOrNull()?.lowercase() ?: return null
        return host.removePrefix("www.").takeIf { it.isNotBlank() }
    }
}
