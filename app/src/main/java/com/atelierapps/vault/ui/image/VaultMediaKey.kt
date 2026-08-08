package com.atelierapps.vault.ui.image

/**
 * Coil model pointing at an encrypted vault image. [full] = false reads the
 * `thumbs/<id>` blob (grid); true reads the full `vault/<id>` image (viewer,
 * step 8). Both are GCM and decrypt through [com.atelierapps.vault.crypto.MediaCrypto].
 */
data class VaultMediaKey(
    val id: String,
    val full: Boolean = false,
)
