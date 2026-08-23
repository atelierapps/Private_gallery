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

data class ExportProgress(val done: Int, val total: Int, val failed: Int)
data class ExportResult(val exported: Int, val failed: Int, val total: Int)

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
     * Export the vault, or just [onlyIds] when a selection or album was chosen.
     * A scoped export still writes a manifest, so it round-trips through restore
     * exactly like a full backup does.
     */
    suspend fun exportAll(
        context: Context,
        treeUri: Uri,
        onlyIds: Set<String>? = null,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return ExportResult(0, 0, 0)
        val all = VaultGraph.repository(context).allMedia()
        val items = if (onlyIds == null) all else all.filter { it.media.id in onlyIds }

        val manifest = JSONArray()
        var done = 0
        var failed = 0
        for (mwt in items) {
            val ok = runCatching { exportOne(context, tree, mwt) }.getOrDefault(false)
            if (ok) manifest.put(manifestEntry(mwt)) else failed++
            done++
            onProgress(ExportProgress(done, items.size, failed))
        }

        runCatching { writeManifest(context, tree, manifest) }
            .onFailure { Log.e(TAG, "manifest write failed", it) }

        return ExportResult(exported = done - failed, failed = failed, total = items.size)
    }

    private fun exportOne(context: Context, tree: DocumentFile, mwt: MediaWithTags): Boolean {
        val item = mwt.media
        val name = ensureExtension(item.originalName, item.mimeType)
        val target = tree.createFile(item.mimeType, name) ?: return false
        val blob = VaultGraph.storage(context).blob(item.id)
        context.contentResolver.openOutputStream(target.uri)!!.use { out ->
            if (item.mimeType.startsWith("video/")) MediaCrypto.decryptCtrTo(blob, out)
            else out.write(MediaCrypto.decryptGcmFile(blob))
        }
        return true
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
        }
    }

    private fun writeManifest(context: Context, tree: DocumentFile, manifest: JSONArray) {
        tree.findFile("manifest.json")?.delete()
        val file = tree.createFile("application/json", "manifest.json") ?: return
        val root = JSONObject().apply {
            put("app", "Vault")
            put("version", 1)
            put("items", manifest)
        }
        context.contentResolver.openOutputStream(file.uri)!!.use { out ->
            out.write(root.toString(2).toByteArray())
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
