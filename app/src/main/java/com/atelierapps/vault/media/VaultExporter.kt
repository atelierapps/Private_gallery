package com.atelierapps.vault.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.MediaCrypto
import com.atelierapps.vault.data.entity.MediaWithTags
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.SecretKey

data class ExportProgress(val done: Int, val total: Int, val failed: Int)
data class ExportResult(
    val exported: Int,
    val failed: Int,
    val total: Int,
    /** Which items didn't make it, and why. Empty when everything did. */
    val failures: List<TransferFailure> = emptyList(),
)

/** A backup already sitting in the folder the user just picked. */
data class ExistingBackup(val encrypted: Boolean, val items: Int)

/**
 * Exports the whole vault to a user-picked folder (spec §11) — the only backup.
 *
 * Decrypts every item back to its original filename + extension, and writes a
 * `manifest.json` alongside carrying tags and source metadata so a re-import can
 * restore them. Output is **plaintext** on removable/user storage, by design.
 *
 * Destination is a SAF tree the user picks (Downloads, SD card, USB-OTG…) — a
 * non-media folder, so MIUI's media-folder SAF block doesn't apply.
 */
object VaultExporter {

    /**
     * Names an encrypted backup gives its item files: a bare UUID plus `.bin`.
     * Matched exactly so clearing a folder can only ever remove files this app
     * wrote — a stray `notes.bin` of someone else's is not going anywhere.
     */
    private val ITEM_FILE =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.bin$")

    /**
     * What is already in [treeUri], if it holds a backup — so the caller can ask
     * before overwriting one.
     *
     * This matters more than it looks. Every export mints a **fresh salt**, so
     * the moment a second run writes its header, the first run's files can no
     * longer be opened by anything, the same passphrase included. Two backups
     * cannot share a folder; the only question is whether the user knows that
     * before the second one starts.
     */
    fun existingBackup(context: Context, treeUri: Uri): ExistingBackup? {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val names = tree.listFiles().filter { it.isFile }.mapNotNull { it.name }
        val encrypted = BackupCrypto.HEADER in names
        val plain = "manifest.json" in names
        if (!encrypted && !plain) return null
        return ExistingBackup(
            encrypted = encrypted,
            items = if (encrypted) names.count { ITEM_FILE.matches(it) } else names.size - 1,
        )
    }

    /**
     * Export the vault, or just [onlyIds] when a selection or album was chosen.
     * A scoped export still writes a manifest, so it round-trips through restore
     * exactly like a full backup does.
     */
    suspend fun exportAll(
        context: Context,
        treeUri: Uri,
        onlyIds: Set<String>? = null,
        passphrase: CharArray? = null,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return ExportResult(0, 0, 0)
        val all = VaultGraph.repository(context).allMedia()
        val items = if (onlyIds == null) all else all.filter { it.media.id in onlyIds }

        // The header goes down first. Written last, a run interrupted halfway
        // would leave a folder of ciphertext with nothing saying how to open it.
        val key = passphrase?.let {
            val (header, derived) = BackupCrypto.newHeader(it)
            tree.findFile(BackupCrypto.HEADER)?.delete()
            val file = tree.createFile("application/json", BackupCrypto.HEADER)
                ?: return ExportResult(0, 0, items.size)
            context.contentResolver.openOutputStream(file.uri)!!.use { out ->
                out.write(BackupCrypto.headerToJson(header).toByteArray())
            }
            derived
        }

        // What is already in the folder, listed once. `createFile` does not
        // overwrite: given a name that exists it invents "name (1)" instead, and
        // the manifest would still point at the original. Exporting twice into
        // one folder therefore used to leave every entry resolving to the
        // *previous* run's ciphertext — encrypted under a different salt, so the
        // whole backup restored as "truncated". Replacing by name is also no
        // loss: the new header above already re-keys the folder, which orphans
        // anything left from the earlier run regardless.
        val existing = tree.listFiles().filter { it.isFile }.associateBy { it.name }

        // An encrypted run additionally clears the previous one's item files.
        // Not tidiness: an item exported last time and since deleted from the
        // library is not rewritten now, so it would sit there forever as a file
        // no passphrase can open, padding the folder and the count. Only exact
        // `<uuid>.bin` names are touched.
        if (key != null) {
            val keeping = items.mapTo(HashSet()) { it.media.id + ".bin" }
            existing.forEach { (name, doc) ->
                if (name != null && name !in keeping && ITEM_FILE.matches(name)) {
                    runCatching { doc.delete() }
                }
            }
        }

        val manifest = JSONArray()
        val failures = ArrayList<TransferFailure>()
        var done = 0
        for (mwt in items) {
            val attempt = runCatching { exportOne(context, tree, mwt, key, existing) }
            val entry = attempt.getOrNull()
            if (entry != null) {
                manifest.put(entry)
            } else {
                // Never swallow this. A count on its own can't tell you whether
                // three thumbnails or three irreplaceable videos are missing.
                val error = attempt.exceptionOrNull()
                Log.e(TAG, "export failed: " + mwt.media.originalName, error)
                failures.add(
                    TransferFailure(
                        mwt.media.originalName,
                        if (attempt.isSuccess) "the folder wouldn't accept a new file"
                        else TransferFailure.describe(error),
                    ),
                )
            }
            done++
            onProgress(ExportProgress(done, items.size, failures.size))
        }

        runCatching { writeManifest(context, tree, manifest, key) }
            .onFailure { Log.e(TAG, "manifest write failed", it) }

        return ExportResult(
            exported = done - failures.size,
            failed = failures.size,
            total = items.size,
            failures = failures,
        )
    }

    /** Writes one item and returns its manifest entry, or null if it failed. */
    private fun exportOne(
        context: Context,
        tree: DocumentFile,
        mwt: MediaWithTags,
        key: SecretKey?,
        existing: Map<String?, DocumentFile>,
    ): JSONObject? {
        val item = mwt.media
        // Encrypted backups are named by id, not by title: a directory listing
        // of holiday-2019.jpg would give away everything the ciphertext hides.
        val name =
            if (key == null) ensureExtension(item.originalName, item.mimeType)
            else item.id + ".bin"
        val type = if (key == null) item.mimeType else "application/octet-stream"
        existing[name]?.delete()
        val target = tree.createFile(type, name) ?: return null
        val blob = VaultGraph.storage(context).blob(item.id)

        var plaintextBytes = 0L
        context.contentResolver.openOutputStream(target.uri)!!.use { out ->
            if (key == null) {
                if (item.mimeType.startsWith("video/")) MediaCrypto.decryptCtrTo(blob, out)
                else out.write(MediaCrypto.decryptGcmFile(blob))
            } else {
                // The vault's decryption writes straight into the backup's
                // cipher, so the plaintext exists only in flight between the two
                // and is never buffered whole or written anywhere.
                plaintextBytes = BackupCrypto.encryptTo(key, out) { sink ->
                    if (item.mimeType.startsWith("video/")) MediaCrypto.decryptCtrTo(blob, sink)
                    else sink.write(MediaCrypto.decryptGcmFile(blob))
                }
            }
        }
        return manifestEntry(mwt).apply {
            if (key != null) {
                put("file", name)
                // Checked on restore: CipherInputStream reports a failed tag as
                // end-of-stream rather than an error, so length is what turns a
                // truncated file back into a visible failure.
                put("plainBytes", plaintextBytes)
            }
        }
    }

    private fun manifestEntry(mwt: MediaWithTags): JSONObject {
        val m = mwt.media
        return JSONObject().apply {
            put("name", ensureExtension(m.originalName, m.mimeType))
            put("mimeType", m.mimeType)
            put("dateTakenMillis", m.dateTakenMillis)
            put("sourceType", m.sourceType.name)
            put("sourcePackage", m.sourcePackage ?: JSONObject.NULL)
            put("sourceLabel", m.sourceLabel ?: JSONObject.NULL)
            put("sourceDomain", m.sourceDomain ?: JSONObject.NULL)
            put("tags", JSONArray().apply { mwt.tags.forEach { put(it.name) } })
            // The plaintext hash the vault already stores. Recording it lets a
            // restore recognise an item it already holds *before* decrypting it,
            // which is what makes resuming an interrupted restore cheap instead
            // of a second full pass over everything that already landed.
            m.contentHash?.let { put("sha256", it) }
        }
    }

    private fun writeManifest(
        context: Context,
        tree: DocumentFile,
        manifest: JSONArray,
        key: SecretKey?,
    ) {
        val name = if (key == null) "manifest.json" else BackupCrypto.MANIFEST
        tree.findFile(name)?.delete()
        val file = tree.createFile("application/octet-stream", name) ?: return
        val root = JSONObject().apply {
            put("app", "Vault")
            put("version", 1)
            put("items", manifest)
        }
        val bytes = root.toString(2).toByteArray()
        context.contentResolver.openOutputStream(file.uri)!!.use { out ->
            // The manifest holds names, tags and sources — the same metadata the
            // database encrypts — so it is never written in the clear either.
            out.write(if (key == null) bytes else BackupCrypto.encryptBytes(key, bytes))
        }
    }

    private fun ensureExtension(name: String, mimeType: String): String {
        if (name.contains('.')) return name
        val ext = when {
            mimeType == "image/jpeg" -> "jpg"
            mimeType == "image/png" -> "png"
            mimeType == "image/webp" -> "webp"
            mimeType == "image/gif" -> "gif"
            mimeType == "video/mp4" -> "mp4"
            mimeType.startsWith("image/") -> mimeType.substringAfter('/')
            mimeType.startsWith("video/") -> mimeType.substringAfter('/')
            else -> return name
        }
        return "$name.$ext"
    }

    private const val TAG = "VaultExporter"
}
