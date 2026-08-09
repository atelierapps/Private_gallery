package com.atelierapps.vault.media

import android.content.Context
import android.util.Log
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.EnvelopeCodec
import com.atelierapps.vault.crypto.EnvelopeFormat
import com.atelierapps.vault.crypto.KeyWrapper
import com.atelierapps.vault.crypto.VaultKeys
import com.atelierapps.vault.data.entity.MediaItemEntity
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

    suspend fun save(request: SaveRequest): Result<String> {
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
            if (repository.existsByHash(hash)) {
                temp.delete()
                return Result.success("duplicate")
            }

            // 1. Encrypt + fsync.
            if (isVideo) encryptCtr(temp, blob) else encryptGcm(temp, blob)

            // 2. Verify on-disk length before trusting the blob.
            val expected = EnvelopeFormat.headerLen(mode) + plaintextLen +
                if (mode == EnvelopeFormat.MODE_GCM) EnvelopeFormat.GCM_TAG_LEN else 0
            check(blob.length() == expected) {
                "blob length ${blob.length()} != expected $expected"
            }

            // 3. Thumbnail (best-effort) + fsync.
            val thumbBytes = Thumbnailer.jpegBytes(temp, request.mimeType)
            if (thumbBytes.isNotEmpty()) {
                writeFsync(thumb, EnvelopeCodec.encryptGcm(thumbBytes, wrapper))
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
            // leave temp for retry / sweep
            return Result.failure(t)
        }
    }

    private fun encryptGcm(temp: File, blob: File) {
        val cipher = EnvelopeCodec.encryptGcm(temp.readBytes(), wrapper)
        writeFsync(blob, cipher)
    }

    private fun encryptCtr(temp: File, blob: File) {
        FileInputStream(temp).use { input ->
            FileOutputStream(blob).use { output ->
                EnvelopeCodec.encryptCtr(input, output, wrapper)
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

    companion object { private const val TAG = "MediaSaver" }
}
