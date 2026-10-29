package com.atelierapps.vault.storage

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * App-private storage layout (spec §2). Everything lives under `filesDir` —
 * never scanned by MediaStore, invisible to other apps.
 *
 *   vault/<uuid>   encrypted media blob
 *   thumbs/<uuid>  encrypted 512px thumbnail
 *   tmp/<uuid>     plaintext spool during share/import — swept on launch (§5.1)
 *   tmp/trim-<uuid>.mp4  plaintext trim output, alive only while a trim runs
 */
class VaultStorage(context: Context) {

    private val root: File = context.filesDir
    val vaultDir: File = File(root, "vault").apply { mkdirs() }
    val thumbsDir: File = File(root, "thumbs").apply { mkdirs() }
    val tmpDir: File = File(root, "tmp").apply { mkdirs() }

    fun blob(id: String): File = File(vaultDir, id)
    fun thumb(id: String): File = File(thumbsDir, id)

    /** Remove a media item's encrypted blob and thumbnail from disk. */
    fun delete(id: String) {
        blob(id).delete()
        thumb(id).delete()
    }

    fun newTempFile(): File = File(tmpDir, UUID.randomUUID().toString())

    /** Where a trim writes its (plaintext) output; see [sweepTemp]. */
    fun newTrimFile(): File = File(tmpDir, TRIM_PREFIX + UUID.randomUUID().toString() + ".mp4")

    companion object {
        private const val TRIM_PREFIX = "trim-"
    }

    /**
     * Delete orphaned plaintext spools left by a crash mid-encrypt (spec §5.1).
     * Only touches files older than [olderThanMillis] so an in-flight spool from
     * a just-restarted worker is never nuked. Call on app launch.
     */
    fun sweepTemp(olderThanMillis: Long = 6 * 60 * 60 * 1000L, now: Long) {
        val cutoff = now - olderThanMillis
        tmpDir.listFiles()?.forEach { f ->
            // A trim's output only exists while the trim runs, inside this
            // process — so at launch any such file is from a run that died
            // partway, and goes regardless of age. Unlike an import spool, no
            // worker can be about to resume it.
            val orphanedTrim = f.name.startsWith(TRIM_PREFIX)
            if (f.isFile && (orphanedTrim || f.lastModified() < cutoff)) f.delete()
        }
    }
}
