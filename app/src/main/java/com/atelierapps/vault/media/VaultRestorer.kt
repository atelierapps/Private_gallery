package com.atelierapps.vault.media

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.SourceType
import org.json.JSONObject
import java.io.File

data class RestoreProgress(val done: Int, val total: Int, val imported: Int, val failed: Int)
data class RestoreResult(val imported: Int, val failed: Int, val total: Int, val hadManifest: Boolean)

/**
 * Rebuilds the vault from an export folder (spec §11 round-trip). Reads
 * `manifest.json`, re-encrypts each listed file back into the vault, and
 * restores its tags + source metadata. Dedup in [MediaSaver] makes restore
 * idempotent — re-running skips items already present.
 */
object VaultRestorer {

    suspend fun restoreAll(
        context: Context,
        treeUri: Uri,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return RestoreResult(0, 0, 0, hadManifest = false)
        val manifestDoc = tree.findFile("manifest.json")
            ?: return RestoreResult(0, 0, 0, hadManifest = false)

        val json = context.contentResolver.openInputStream(manifestDoc.uri)!!
            .use { it.readBytes().decodeToString() }
        val items = JSONObject(json).optJSONArray("items") ?: return RestoreResult(0, 0, 0, hadManifest = true)

        val byName = tree.listFiles().filter { it.isFile }.associateBy { it.name }
        val saver = MediaSaver(context)
        var imported = 0
        var failed = 0
        for (i in 0 until items.length()) {
            val obj = items.getJSONObject(i)
            val doc = byName[obj.optString("name")]
            val ok = doc != null && runCatching { restoreOne(context, saver, doc, obj) }.getOrDefault(false)
            if (ok) imported++ else failed++
            onProgress(RestoreProgress(i + 1, items.length(), imported, failed))
        }
        return RestoreResult(imported, failed, items.length(), hadManifest = true)
    }

    private suspend fun restoreOne(
        context: Context,
        saver: MediaSaver,
        doc: DocumentFile,
        obj: JSONObject,
    ): Boolean {
        val temp = spool(context, doc.uri)
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

    private fun spool(context: Context, uri: Uri): File {
        val temp = VaultGraph.storage(context).newTempFile()
        context.contentResolver.openInputStream(uri)!!.use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        return temp
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).ifBlank { null }
}
