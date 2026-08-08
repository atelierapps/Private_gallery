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
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}
