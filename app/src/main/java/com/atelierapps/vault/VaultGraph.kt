package com.atelierapps.vault

import android.content.Context
import com.atelierapps.vault.data.VaultRepository
import com.atelierapps.vault.data.db.VaultDatabase
import com.atelierapps.vault.storage.VaultStorage

/**
 * Minimal manual dependency graph (no DI framework in v1 — spec keeps the
 * surface small). Everything is process-scoped and cheap to hold.
 */
object VaultGraph {
    @Volatile private var repo: VaultRepository? = null

    fun repository(context: Context): VaultRepository =
        repo ?: synchronized(this) {
            repo ?: run {
                val db = VaultDatabase.get(context)
                VaultRepository(db.mediaDao(), db.tagDao()).also { repo = it }
            }
        }

    fun storage(context: Context): VaultStorage = VaultStorage(context)
}
