package com.atelierapps.vault.data.entity

/** Where a media item came from (spec §6). */
enum class SourceType {
    SHARE,          // arrived via the share sheet (§5)
    LOCAL_IMPORT,   // pulled from the device gallery (§4)
    FOLDER_IMPORT,  // imported from a picked folder (§4.1)
    UNKNOWN,        // referrer was null / unattributable — never guess (§6)
}
