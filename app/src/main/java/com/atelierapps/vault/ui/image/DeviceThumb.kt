package com.atelierapps.vault.ui.image

import android.net.Uri

/**
 * Coil model for a **device** (MediaStore) media thumbnail shown in the importer
 * — distinct from [VaultMediaKey], which is for already-encrypted vault items.
 * Resolved by [DeviceThumbFetcher] via `ContentResolver.loadThumbnail`, which is
 * fast and — unlike Coil's default image path — produces frames for videos too.
 */
data class DeviceThumb(val uri: Uri)
