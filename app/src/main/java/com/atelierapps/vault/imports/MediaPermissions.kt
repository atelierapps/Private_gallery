package com.atelierapps.vault.imports

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Read-media permissions for the importer (spec §4). Split media permissions on
 * API 33+, legacy storage on ≤32, and the API 34 partial-grant
 * (`READ_MEDIA_VISUAL_USER_SELECTED`) — a partial grant still lets the query
 * return the user-selected subset, which is fine.
 */
object MediaPermissions {

    fun required(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True if we can query at least some media (full or partial grant). */
    fun canQuery(context: Context): Boolean {
        fun has(p: String) =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                has(Manifest.permission.READ_MEDIA_IMAGES) ||
                    has(Manifest.permission.READ_MEDIA_VIDEO) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
            else -> has(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
