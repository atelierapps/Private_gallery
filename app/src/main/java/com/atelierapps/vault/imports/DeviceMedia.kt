package com.atelierapps.vault.imports

import android.net.Uri
import com.atelierapps.vault.data.entity.SourceType

/**
 * One selectable item in the importer — a device-gallery entry (MediaStore) or
 * a file inside a picked folder (SAF). [origin] decides the resulting
 * [SourceType] and how originals are deleted (spec §4 vs §4.1).
 */
data class DeviceMedia(
    val uri: Uri,
    val mimeType: String,
    val displayName: String,
    val dateTakenMillis: Long,
    val durationMillis: Long?,
    val origin: SourceType, // LOCAL_IMPORT or FOLDER_IMPORT
    // The MediaStore bucket (folder) this item lives in, e.g. "Instagram",
    // "Download", "Screenshots" — the best available provenance for imports,
    // captured as the source so it's visible and matchable by auto-tag rules.
    val bucketName: String? = null,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}
