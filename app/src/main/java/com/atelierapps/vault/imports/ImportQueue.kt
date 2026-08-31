package com.atelierapps.vault.imports

import android.content.Context
import android.net.Uri
import com.atelierapps.vault.data.entity.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable, resumable queue backing bulk import (spec §4, §4.1).
 *
 * Imports used to run in the Activity's `viewModelScope`, so leaving the app
 * mid-way silently cancelled them — the worst case being exactly the long ones.
 * The work list now lives on disk instead: [ImportWorker] drains it, and because
 * state survives process death the import simply resumes where it stopped.
 *
 * The file holds only what's needed to re-drive the pipeline (content URIs and
 * the metadata already read from MediaStore). No media bytes live here.
 */
object ImportQueue {

    data class Entry(
        val uri: Uri,
        val mimeType: String,
        val displayName: String,
        val dateTakenMillis: Long,
        val origin: SourceType,
        val bucketName: String?,
        /** Tags to apply on save — typed once for the batch, carried per item. */
        val tagNames: List<String> = emptyList(),
    )

    /** A snapshot of queue + counters, for progress reporting. */
    data class State(
        val total: Int,
        val done: Int,
        val duplicates: Int,
        val failed: Int,
        val remaining: Int,
        val pendingDelete: List<Uri>,
    ) {
        val finished: Boolean get() = remaining == 0
    }

    private const val DIR = "import"
    private const val FILE = "queue.json"

    private fun file(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, FILE)

    @Synchronized
    private fun read(context: Context): JSONObject {
        val f = file(context)
        if (!f.exists()) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    /** Write via a temp file + rename so a crash can't leave a half-written queue. */
    @Synchronized
    private fun write(context: Context, root: JSONObject) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) { f.writeText(root.toString()); tmp.delete() }
    }

    private fun items(root: JSONObject): JSONArray = root.optJSONArray("items") ?: JSONArray()

    private fun toJson(e: Entry): JSONObject = JSONObject().apply {
        put("u", e.uri.toString())
        put("m", e.mimeType)
        put("n", e.displayName)
        put("d", e.dateTakenMillis)
        put("o", e.origin.name)
        put("b", e.bucketName ?: JSONObject.NULL)
        if (e.tagNames.isNotEmpty()) put("t", JSONArray(e.tagNames))
    }

    private fun fromJson(o: JSONObject): Entry = Entry(
        uri = Uri.parse(o.optString("u")),
        mimeType = o.optString("m", "application/octet-stream"),
        displayName = o.optString("n", "item"),
        dateTakenMillis = o.optLong("d", System.currentTimeMillis()),
        origin = runCatching { SourceType.valueOf(o.optString("o")) }.getOrDefault(SourceType.UNKNOWN),
        bucketName = if (o.isNull("b")) null else o.optString("b"),
        // Absent on a queue written before batch tagging existed, so an import
        // already in flight across an app update still drains cleanly.
        tagNames = o.optJSONArray("t")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }.orEmpty(),
    )

    /** Append work. Counters accumulate so a second batch extends the same run. */
    @Synchronized
    fun enqueue(context: Context, entries: List<Entry>, deleteOriginals: Boolean) {
        if (entries.isEmpty()) return
        val root = read(context)
        val arr = items(root)
        entries.forEach { arr.put(toJson(it)) }
        root.put("items", arr)
        root.put("total", root.optInt("total", 0) + entries.size)
        if (deleteOriginals) root.put("deleteOriginals", true)
        write(context, root)
    }

    @Synchronized
    fun peek(context: Context): Entry? {
        val arr = items(read(context))
        return if (arr.length() == 0) null else runCatching { fromJson(arr.getJSONObject(0)) }.getOrNull()
    }

    /**
     * Drop the head and record its outcome. Successful MediaStore imports are
     * remembered for the delete-originals prompt.
     */
    @Synchronized
    fun completeFirst(context: Context, outcome: Outcome, uri: Uri) {
        val root = read(context)
        val arr = items(root)
        if (arr.length() > 0) arr.remove(0)
        root.put("items", arr)
        root.put("done", root.optInt("done", 0) + 1)
        when (outcome) {
            Outcome.DUPLICATE -> root.put("duplicates", root.optInt("duplicates", 0) + 1)
            Outcome.FAILED -> root.put("failed", root.optInt("failed", 0) + 1)
            Outcome.SAVED ->
                if (root.optBoolean("deleteOriginals", false)) {
                    val pd = root.optJSONArray("pendingDelete") ?: JSONArray()
                    pd.put(uri.toString())
                    root.put("pendingDelete", pd)
                }
        }
        write(context, root)
    }

    enum class Outcome { SAVED, DUPLICATE, FAILED }

    @Synchronized
    fun state(context: Context): State {
        val root = read(context)
        val pd = root.optJSONArray("pendingDelete") ?: JSONArray()
        return State(
            total = root.optInt("total", 0),
            done = root.optInt("done", 0),
            duplicates = root.optInt("duplicates", 0),
            failed = root.optInt("failed", 0),
            remaining = items(root).length(),
            pendingDelete = (0 until pd.length()).mapNotNull {
                runCatching { Uri.parse(pd.getString(it)) }.getOrNull()
            },
        )
    }

    /** Forget the originals we offered to delete (accepted, declined, or failed). */
    @Synchronized
    fun clearPendingDelete(context: Context) {
        val root = read(context)
        root.remove("pendingDelete")
        write(context, root)
    }

    /** Reset counters once a finished run has been acknowledged. */
    @Synchronized
    fun resetIfDrained(context: Context) {
        val root = read(context)
        if (items(root).length() > 0) return
        if ((root.optJSONArray("pendingDelete")?.length() ?: 0) > 0) return
        file(context).delete()
    }
}
