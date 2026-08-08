package com.atelierapps.vault.media

import com.atelierapps.vault.data.entity.SourceType

/** Everything the encrypt pipeline needs to turn a spooled plaintext file into a vault item. */
data class SaveRequest(
    val tempPath: String,
    val mimeType: String,
    val originalName: String,
    val dateTakenMillis: Long,
    val tagNames: List<String>,
    val source: SourceInfo,
)

/** Captured source attribution (spec §6). No full URL is retained (spec §2.1). */
data class SourceInfo(
    val sourceType: SourceType,
    val sourcePackage: String?,
    val sourceLabel: String?,
    val sourceDomain: String?,
) {
    companion object {
        val UNKNOWN = SourceInfo(SourceType.UNKNOWN, null, null, null)
    }
}
