package com.atelierapps.vault.media

import android.content.Context
import android.util.Log
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.DekCache
import com.atelierapps.vault.crypto.EnvelopeCodec
import com.atelierapps.vault.crypto.EnvelopeFormat
import com.atelierapps.vault.crypto.KeyWrapper
import com.atelierapps.vault.crypto.VaultKeys
import com.atelierapps.vault.data.entity.MediaItemEntity
import com.atelierapps.vault.session.VaultSession
import com.atelierapps.vault.storage.VaultStorage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * The encrypt-and-persist pipeline (spec §3–§5). Turns one plaintext spool file
 * into a verified encrypted blob + thumbnail + Room row, then deletes the spool.
 *
 * Hard rule (spec §4): the blob is fsync'd and length-verified before any row is
 * written or any original is considered safe to delete. On failure, partial
 * outputs are removed and the spool is left for retry/sweep.
 */
class MediaSaver(
    private val context: Context,
    private val storage: VaultStorage = VaultGraph.storage(context),
    private val wrapper: KeyWrapper = VaultKeys.wrapper,
) {
    private val repository = VaultGraph.repository(context)

    /**
     * Set when the last [save] returned [DUPLICATE]: the live item that already
     * holds those exact bytes. The importer needs it to decide whether the
     * original is safe to offer for deletion — which depends on that copy
     * still being intact, not merely on its once having been imported.
     *
     * A field rather than part of the result because two callers compare the
     * result against [DUPLICATE] directly, and every MediaSaver is owned by one
     * caller saving one item at a time.
     */
    var duplicateOf: String? = null
        private set

    suspend fun save(request: SaveRequest): Result<String> {
        duplicateOf = null
        val temp = File(request.tempPath)
        if (!temp.exists()) return Result.failure(IllegalStateException("spool missing: ${request.tempPath}"))

        val id = UUID.randomUUID().toString()
        val isVideo = request.mimeType.startsWith("video/")
        val mode = if (isVideo) EnvelopeFormat.MODE_CTR else EnvelopeFormat.MODE_GCM
        val blob = storage.blob(id)
        val thumb = storage.thumb(id)

        try {
            val plaintextLen = temp.length()
            val hash = sha256(temp)

            // Dedup (§4.2): identical content already present → drop the spool, done.
            //
            // "Present" means the file is actually there. A row whose blob was
            // lost is not a copy of anything, and treating it as one discarded
            // the spool — for a share-in, possibly the only copy left — while
            // the ghost row went on not playing. Saving afresh heals it instead.
            val existing = repository.liveIdByHash(hash)
                ?.takeIf { storage.blob(it).exists() }
            if (existing != null) {
                duplicateOf = existing
                temp.delete()
                return Result.success(DUPLICATE)
            }

            // The new files' keys go into the cache as they're made, so the item
            // opens — and a trim can verify it — without unwrapping them back,
            // which the Keystore only allows within five minutes of an unlock.
            // Only while unlocked: a save from the share sheet with the vault
            // locked must not leave live keys behind. Session read first; see
            // DekCache.session.
            val session = DekCache.session
            val cacheKeys = !VaultSession.locked.value
            fun cacheAs(file: File): ((ByteArray) -> Unit)? {
                if (!cacheKeys) return null
                return { dek -> DekCache.put(file.absolutePath, dek, session) }
            }

            // 1. Encrypt + fsync.
            if (isVideo) encryptCtr(temp, blob, cacheAs(blob)) else encryptGcm(temp, blob, cacheAs(blob))

            // 2. Verify on-disk length before trusting the blob.
            val expected = EnvelopeFormat.headerLen(mode) + plaintextLen +
                if (mode == EnvelopeFormat.MODE_GCM) EnvelopeFormat.GCM_TAG_LEN else 0
            check(blob.length() == expected) {
                "blob length ${blob.length()} != expected $expected"
            }

            // 3. Thumbnail (best-effort) + fsync.
            val thumbBytes = Thumbnailer.jpegBytes(temp, request.mimeType)
            if (thumbBytes.isNotEmpty()) {
                writeFsync(thumb, EnvelopeCodec.encryptGcm(thumbBytes, wrapper, cacheAs(thumb)))
            }

            // 4. Probe intrinsic metadata and persist the row (+ tags).
            val meta = MediaProbe.probe(temp, request.mimeType, request.dateTakenMillis)
            val entity = MediaItemEntity(
                id = id,
                originalName = request.originalName,
                mimeType = request.mimeType,
                sizeBytes = plaintextLen,
                dateTakenMillis = meta.dateTakenMillis,
                durationMillis = meta.durationMillis,
                widthPx = meta.widthPx,
                heightPx = meta.heightPx,
                importedAtMillis = System.currentTimeMillis(),
                albumId = null,
                contentHash = hash,
                sourceType = request.source.sourceType,
                sourcePackage = request.source.sourcePackage,
                sourceLabel = request.source.sourceLabel,
                sourceDomain = request.source.sourceDomain,
                cryptoMode = mode.toInt(),
            )
            // Merge any auto-tag rules that match this item's source (§7) with the
            // tags the user picked on the save sheet.
            val autoTags = repository.autoTagsFor(
                request.source.sourceType,
                request.source.sourcePackage,
                request.source.sourceLabel,
                request.source.sourceDomain,
            )
            repository.saveMedia(entity, (request.tagNames + autoTags).distinct())

            // 5. Only now is the spool disposable.
            temp.delete()
            return Result.success(id)
        } catch (t: Throwable) {
            Log.e(TAG, "save failed for ${request.originalName}", t)
            blob.delete()
            thumb.delete()
            DekCache.remove(blob.absolutePath)
            DekCache.remove(thumb.absolutePath)
            // leave temp for retry / sweep
            return Result.failure(t)
        }
    }

    private fun encryptGcm(temp: File, blob: File, onKey: ((ByteArray) -> Unit)?) {
        val cipher = EnvelopeCodec.encryptGcm(temp.readBytes(), wrapper, onKey)
        writeFsync(blob, cipher)
    }

    private fun encryptCtr(temp: File, blob: File, onKey: ((ByteArray) -> Unit)?) {
        FileInputStream(temp).use { input ->
            FileOutputStream(blob).use { output ->
                EnvelopeCodec.encryptCtr(input, output, wrapper, onKey)
                output.fd.sync()
            }
        }
    }

    private fun writeFsync(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "MediaSaver"

        /**
         * Sentinel returned instead of a new id when content-hash dedup (§4.2)
         * recognised the item. Callers report these separately so an import
         * summary can say what was skipped rather than counting it as saved.
         */
        const val DUPLICATE = "duplicate"
    }
}
