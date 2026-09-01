package com.atelierapps.vault.media

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.SourceType
import org.json.JSONObject
import java.io.File
import javax.crypto.SecretKey
import android.util.Log

data class RestoreProgress(val done: Int, val total: Int, val imported: Int, val failed: Int)
data class RestoreResult(
    val imported: Int,
    val failed: Int,
    val total: Int,
    val hadManifest: Boolean,
    val failures: List<TransferFailure> = emptyList(),
)

/**
 * Rebuilds the vault from an export folder (spec §11 round-trip). Reads
 * `manifest.json`, re-encrypts each listed file back into the vault, and
 * restores its tags + source metadata. Dedup in [MediaSaver] makes restore
 * idempotent — re-running skips items already present.
 */
object VaultRestorer {

    /** True if this folder holds an encrypted backup and needs a passphrase. */
    fun isEncrypted(context: Context, treeUri: Uri): Boolean =
        DocumentFile.fromTreeUri(context, treeUri)?.findFile(BackupCrypto.HEADER) != null

    suspend fun restoreAll(
        context: Context,
        treeUri: Uri,
        passphrase: CharArray? = null,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return RestoreResult(0, 0, 0, hadManifest = false)

        // An encrypted backup announces itself with a header; without one this
        // is a plaintext export and reads exactly as it always did.
        val headerDoc = tree.findFile(BackupCrypto.HEADER)
        val key =
            if (headerDoc == null) {
                null
            } else {
                val text = context.contentResolver.openInputStream(headerDoc.uri)!!
                    .use { it.readBytes().decodeToString() }
                val header = BackupCrypto.headerFromJson(text)
                    ?: return RestoreResult(0, 0, 0, hadManifest = false)
                // Throws WrongPassphrase, which the caller turns into a message
                // rather than four hundred failed items.
                BackupCrypto.keyFor(header, passphrase ?: CharArray(0))
            }

        val manifestName = if (key == null) "manifest.json" else BackupCrypto.MANIFEST
        val manifestDoc = tree.findFile(manifestName)
            ?: return RestoreResult(0, 0, 0, hadManifest = false)

        val raw = context.contentResolver.openInputStream(manifestDoc.uri)!!.use { it.readBytes() }
        val json = if (key == null) raw.decodeToString()
        else BackupCrypto.decryptBytes(key, raw).decodeToString()
        val items = JSONObject(json).optJSONArray("items") ?: return RestoreResult(0, 0, 0, hadManifest = true)

        val byName = tree.listFiles().filter { it.isFile }.associateBy { it.name }
        val saver = MediaSaver(context)
        val repo = VaultGraph.repository(context)
        val failures = ArrayList<TransferFailure>()
        var imported = 0
        for (i in 0 until items.length()) {
            val obj = items.getJSONObject(i)
            // Encrypted entries carry their own opaque filename; plaintext ones
            // are still found by the name they were written under.
            val shown = obj.optString("name", "item " + (i + 1))

            // Already here? Then skip it without opening the file at all.
            //
            // MediaSaver dedups too, but only after the item has been decrypted
            // out of the backup and hashed — so re-running a restore that was
            // interrupted at file 25 of 368 used to decrypt all 25 again before
            // discarding them. The exported hash makes that a lookup. Absent
            // from manifests written before this existed, which simply fall
            // through to the old path.
            val knownHash = obj.optStringOrNull("sha256")
            if (knownHash != null && repo.existsByHash(knownHash)) {
                imported++
                onProgress(RestoreProgress(i + 1, items.length(), imported, failures.size))
                continue
            }

            val doc = byName[obj.optString(if (key == null) "name" else "file")]
            if (doc == null) {
                failures.add(TransferFailure(shown, "not in the folder — was it copied whole?"))
            } else {
                val attempt = runCatching { restoreOne(context, saver, doc, obj, key) }
                when {
                    attempt.getOrDefault(false) -> imported++
                    else -> {
                        Log.e(TAG, "restore failed: " + shown, attempt.exceptionOrNull())
                        failures.add(
                            TransferFailure(
                                shown,
                                if (attempt.isSuccess) "couldn't be saved back in"
                                else TransferFailure.describe(attempt.exceptionOrNull()),
                            ),
                        )
                    }
                }
            }
            onProgress(RestoreProgress(i + 1, items.length(), imported, failures.size))
        }
        return RestoreResult(imported, failures.size, items.length(), true, failures)
    }

    private suspend fun restoreOne(
        context: Context,
        saver: MediaSaver,
        doc: DocumentFile,
        obj: JSONObject,
        key: SecretKey?,
    ): Boolean {
        val temp = if (key == null) spool(context, doc.uri) else unseal(context, doc.uri, obj, key)
        val tags = obj.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }.orEmpty()
        val source = SourceInfo(
            sourceType = runCatching { SourceType.valueOf(obj.optString("sourceType")) }
                .getOrDefault(SourceType.UNKNOWN),
            sourcePackage = obj.optStringOrNull("sourcePackage"),
            sourceLabel = obj.optStringOrNull("sourceLabel"),
            sourceDomain = obj.optStringOrNull("sourceDomain"),
        )
        return saver.save(
            SaveRequest(
                tempPath = temp.absolutePath,
                mimeType = obj.optString("mimeType", doc.type ?: "application/octet-stream"),
                originalName = obj.optString("name", doc.name ?: "restored"),
                dateTakenMillis = obj.optLong("dateTakenMillis", System.currentTimeMillis()),
                tagNames = tags,
                source = source,
            ),
        ).isSuccess
    }

    /**
     * Decrypts one backed-up file to a spool, and refuses it if the length does
     * not match what the manifest recorded.
     *
     * That check is not belt-and-braces. CipherInputStream reports a failed GCM
     * tag as end of stream rather than throwing, so without it a truncated or
     * tampered file would restore quietly as a shorter, plausible one.
     */
    private fun unseal(context: Context, uri: Uri, obj: JSONObject, key: SecretKey): File {
        val temp = VaultGraph.storage(context).newTempFile()
        val written = context.contentResolver.openInputStream(uri)!!.use { input ->
            temp.outputStream().use { output -> BackupCrypto.decryptStream(key, input, output) }
        }
        val expected = obj.optLong("plainBytes", -1L)
        if (expected >= 0 && written != expected) {
            temp.delete()
            error("backup item is truncated: got " + written + " of " + expected + " bytes")
        }
        return temp
    }

    private fun spool(context: Context, uri: Uri): File {
        val temp = VaultGraph.storage(context).newTempFile()
        context.contentResolver.openInputStream(uri)!!.use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        return temp
    }

    private const val TAG = "VaultRestorer"

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).ifBlank { null }
}
