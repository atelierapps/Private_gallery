package com.atelierapps.vault.media

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.SourceType
import com.atelierapps.vault.data.entity.TagEntity
import kotlinx.coroutines.flow.first
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
    /** Created by this restore; ones already here by the same name aren't counted. */
    val albums: Int = 0,
    val views: Int = 0,
)

/**
 * Rebuilds the vault from an export folder (spec §11 round-trip). Reads
 * `manifest.json`, re-encrypts each listed file back into the vault, and
 * restores its tags + source metadata, then the albums and saved views
 * built on top of them. Dedup in [MediaSaver] makes restore
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
        // Where each item that names an album ended up in the vault, new or
        // already here, for putting albums back together once all are in.
        val placed = ArrayList<Pair<String, String>>()
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
            // Held means held: a live row whose file is gone does not count, or
            // restoring from a backup would skip precisely the items that most
            // need it.
            val heldId = knownHash?.let { repo.liveIdByHash(it) }
                ?.takeIf { VaultGraph.storage(context).blob(it).exists() }
            if (heldId != null) {
                obj.optStringOrNull("albumId")?.let { placed.add(heldId to it) }
                imported++
                onProgress(RestoreProgress(i + 1, items.length(), imported, failures.size))
                continue
            }

            val doc = byName[obj.optString(if (key == null) "name" else "file")]
            if (doc == null) {
                failures.add(TransferFailure(shown, "not in the folder — was it copied whole?"))
            } else {
                val attempt = runCatching { restoreOne(context, saver, doc, obj, key) }
                val landed = attempt.getOrNull()
                when {
                    landed != null -> {
                        imported++
                        val albumId = obj.optStringOrNull("albumId")
                        if (albumId != null && landed.isNotEmpty()) placed.add(landed to albumId)
                    }
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
        // After every item, so an album's members are all in place, and never
        // allowed to fail the restore: the media is what matters, and it's in.
        val organised = runCatching { organisation(context, JSONObject(json), placed) }
            .onFailure { Log.e(TAG, "albums/rules/views not restored", it) }
            .getOrNull()
        return RestoreResult(
            imported, failures.size, items.length(), true, failures,
            albums = organised?.albums ?: 0,
            views = organised?.views ?: 0,
        )
    }

    private data class Organised(val albums: Int, val views: Int)

    /**
     * Rebuilds albums and saved views from the manifest. Both sections are
     * optional (and a "rules" section from before auto-tag rules were retired
     * is ignored), so a backup from before they were written
     * restores as it always did.
     *
     * Only ever adds. Restoring into a library that already has things:
     *  - an album with the same name is reused, not doubled, and keeps its cover;
     *  - an item already in an album stays where it is;
     *  - a view with a name already taken is skipped.
     * That also makes running the same restore twice harmless.
     */
    private suspend fun organisation(
        context: Context,
        root: JSONObject,
        placed: List<Pair<String, String>>,
    ): Organised {
        val repo = VaultGraph.repository(context)

        // ---- albums ----
        var albumsMade = 0
        val albumFor = HashMap<String, String>() // backup album id -> vault album id
        val existingAlbums = repo.observeAlbums().first()
        val tags = repo.observeTags().first()
        val albums = root.optJSONArray("albums")
        for (i in 0 until (albums?.length() ?: 0)) {
            val a = albums!!.optJSONObject(i) ?: continue
            val name = a.optString("name").trim()
            val backupId = a.optString("id")
            if (name.isEmpty() || backupId.isEmpty()) continue
            val match = existingAlbums.firstOrNull { it.name.equals(name, ignoreCase = true) }
            albumFor[backupId] = match?.id ?: run {
                val made = repo.createAlbum(name, a.optLong("createdAtMillis", System.currentTimeMillis()))
                albumsMade++
                a.optStringOrNull("cover")?.let { sha -> repo.liveIdByHash(sha)?.let { repo.setAlbumCover(made, it) } }
                made
            }
            // The tags it follows, unless the album here already follows some,
            // and its skip list, so restoring doesn't re-offer what was turned
            // down. Ids re-pointed at this library's tags and items.
            val target = albumFor.getValue(backupId)
            val rule = a.optStringOrNull("tagRule")
            if (rule != null && existingAlbums.firstOrNull { it.id == target }?.tagRule == null) {
                repo.setAlbumTagRule(target, withCurrentTagIds(rule, tags))
            }
            a.optJSONArray("skip")?.let { arr ->
                val ids = (0 until arr.length()).mapNotNull { repo.liveIdByHash(arr.optString(it)) }
                repo.skipForAlbum(target, ids)
            }
        }
        placed.groupBy({ albumFor[it.second] }, { it.first }).forEach { (album, ids) ->
            if (album == null) return@forEach
            val free = ids.filter { repo.mediaById(it)?.media?.albumId == null }
            if (free.isNotEmpty()) repo.setAlbumForItems(free, album)
        }

        // ---- saved views ----
        var viewsMade = 0
        val takenNames = repo.observeSavedViews().first().mapTo(HashSet()) { it.name.lowercase() }
        val views = root.optJSONArray("views")
        for (i in 0 until (views?.length() ?: 0)) {
            val v = views!!.optJSONObject(i) ?: continue
            val name = v.optString("name").trim()
            val spec = v.optString("spec")
            if (name.isEmpty() || spec.isEmpty() || name.lowercase() in takenNames) continue
            repo.createSavedView(name, withCurrentTagIds(spec, tags), v.optLong("createdAtMillis", System.currentTimeMillis()))
            takenNames.add(name.lowercase())
            viewsMade++
        }
        return Organised(albumsMade, viewsMade)
    }

    /**
     * A view names its tags by id, and the ids in a backup are the old
     * library's. Point them at this library's tags of the same name, so the
     * view survives a later rename here too. Left as it was if it won't parse:
     * the view still matches by the names stored alongside.
     */
    private fun withCurrentTagIds(spec: String, tags: List<TagEntity>): String = runCatching {
        val o = JSONObject(spec)
        val arr = o.optJSONArray("tags") ?: return spec
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val tag = tags.firstOrNull { it.name.equals(t.optString("name"), ignoreCase = true) }
            t.put("id", tag?.id ?: "")
        }
        o.toString()
    }.getOrDefault(spec)

    private suspend fun restoreOne(
        context: Context,
        saver: MediaSaver,
        doc: DocumentFile,
        obj: JSONObject,
        key: SecretKey?,
    ): String? {
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
        val saved = saver.save(
            SaveRequest(
                tempPath = temp.absolutePath,
                mimeType = obj.optString("mimeType", doc.type ?: "application/octet-stream"),
                originalName = obj.optString("name", doc.name ?: "restored"),
                dateTakenMillis = obj.optLong("dateTakenMillis", System.currentTimeMillis()),
                tagNames = tags,
                source = source,
            ),
        )
        // Note and view history go on after the item is safely in, and can't
        // fail it: by now the media is verified and saved, and a lost note is
        // a nuisance where a lost item is a loss. A duplicate keeps whatever
        // the copy already here has.
        val id = saved.getOrNull()
        if (id != null && id != MediaSaver.DUPLICATE) {
            runCatching {
                val repo = VaultGraph.repository(context)
                obj.optStringOrNull("note")?.let { repo.setNote(id, it) }
                val views = obj.optInt("viewCount", 0)
                if (views > 0) {
                    val at = if (obj.has("lastViewedAtMillis")) obj.optLong("lastViewedAtMillis") else null
                    repo.setViewHistory(id, at, views)
                }
                if (obj.optBoolean("pinned", false)) repo.setPinned(id, true)
                if (obj.has("videoRotationDegrees")) repo.setVideoRotation(id, obj.optInt("videoRotationDegrees"))
                obj.optJSONArray("bookmarks")?.let { arr ->
                    val marks = (0 until arr.length()).map { arr.optLong(it, -1L) }.filter { it >= 0 }
                    if (marks.isNotEmpty()) repo.setBookmarks(id, marks)
                }
                if (obj.has("watchLaterAtMillis")) {
                    repo.setWatchLater(listOf(id), obj.optLong("watchLaterAtMillis"))
                }
            }.onFailure { Log.w("VaultRestorer", "note/history not restored for " + id, it) }
        }
        // The item's id here, whether just saved or already held under
        // another; "" when it landed but which item it is isn't known.
        return when {
            id == null -> null
            id == MediaSaver.DUPLICATE -> saver.duplicateOf ?: ""
            else -> id
        }
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
