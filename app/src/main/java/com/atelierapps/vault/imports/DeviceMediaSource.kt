package com.atelierapps.vault.imports

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.atelierapps.vault.data.entity.SourceType

/** A device folder (MediaStore bucket) with its media count and a cover item. */
data class MediaFolder(
    val bucketId: String,
    val name: String,
    val count: Int,
    val coverUri: Uri,
)

/**
 * Enumerates importable media via MediaStore (spec §4, §4.1).
 *
 * Folder import (§4.1) is done through MediaStore **buckets**, not the SAF tree
 * picker — MIUI blocks SAF access to the very folders that hold photos ("choose
 * a different folder"). Since we already hold read-media permission, we browse
 * folders exactly like the stock gallery does, and originals delete through the
 * same `createDeleteRequest` path as the main picker.
 *
 * Deliberately NOT the Photo Picker — its URIs can't be passed to
 * `createDeleteRequest()`, so originals could never be removed.
 */
object DeviceMediaSource {

    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private const val COL_ID = MediaStore.Files.FileColumns._ID
    private const val COL_NAME = MediaStore.Files.FileColumns.DISPLAY_NAME
    private const val COL_MIME = MediaStore.Files.FileColumns.MIME_TYPE
    private const val COL_TAKEN = MediaStore.Files.FileColumns.DATE_TAKEN
    private const val COL_MODIFIED = MediaStore.Files.FileColumns.DATE_MODIFIED
    private const val COL_DURATION = MediaStore.Files.FileColumns.DURATION
    private const val COL_TYPE = MediaStore.Files.FileColumns.MEDIA_TYPE
    private const val COL_BUCKET_ID = MediaStore.Files.FileColumns.BUCKET_ID
    private const val COL_BUCKET_NAME = MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME

    private val TYPE_IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
    private val TYPE_VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

    /**
     * Media newest-first, paginated (spec §4). When [bucketId] is set, restrict
     * to that folder; [origin] tags the resulting items for attribution.
     */
    fun queryDevice(
        context: Context,
        limit: Int,
        offset: Int,
        bucketId: String? = null,
        origin: SourceType = SourceType.LOCAL_IMPORT,
    ): List<DeviceMedia> {
        val projection = arrayOf(COL_ID, COL_NAME, COL_MIME, COL_TAKEN, COL_MODIFIED, COL_DURATION, COL_TYPE)
        val selection = StringBuilder("$COL_TYPE IN (?, ?)")
        val args = arrayListOf(TYPE_IMAGE.toString(), TYPE_VIDEO.toString())
        if (bucketId != null) {
            selection.append(" AND $COL_BUCKET_ID = ?")
            args.add(bucketId)
        }
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.toString())
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(COL_TAKEN, COL_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }

        val out = ArrayList<DeviceMedia>()
        context.contentResolver.query(collection, projection, queryArgs, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(COL_ID)
            val nameCol = c.getColumnIndexOrThrow(COL_NAME)
            val mimeCol = c.getColumnIndexOrThrow(COL_MIME)
            val takenCol = c.getColumnIndexOrThrow(COL_TAKEN)
            val modCol = c.getColumnIndexOrThrow(COL_MODIFIED)
            val durCol = c.getColumnIndexOrThrow(COL_DURATION)
            val typeCol = c.getColumnIndexOrThrow(COL_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val mime = c.getString(mimeCol) ?: continue
                val taken = if (!c.isNull(takenCol)) c.getLong(takenCol) else c.getLong(modCol) * 1000L
                val isVideo = c.getInt(typeCol) == TYPE_VIDEO
                out += DeviceMedia(
                    uri = ContentUris.withAppendedId(collection, id),
                    mimeType = mime,
                    displayName = c.getString(nameCol) ?: "IMG_$id",
                    dateTakenMillis = taken,
                    durationMillis = if (isVideo && !c.isNull(durCol)) c.getLong(durCol) else null,
                    origin = origin,
                )
            }
        }
        return out
    }

    /** Folders (buckets) holding image/video, with counts and a cover, newest first. */
    fun queryFolders(context: Context): List<MediaFolder> {
        val projection = arrayOf(COL_ID, COL_BUCKET_ID, COL_BUCKET_NAME, COL_MODIFIED)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "$COL_TYPE IN (?, ?)")
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(TYPE_IMAGE.toString(), TYPE_VIDEO.toString()),
            )
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(COL_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }

        // bucketId -> [name, count, coverId(first-seen == most recent)]
        val order = ArrayList<String>()
        val names = HashMap<String, String>()
        val counts = HashMap<String, Int>()
        val covers = HashMap<String, Long>()
        context.contentResolver.query(collection, projection, queryArgs, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(COL_ID)
            val bidCol = c.getColumnIndexOrThrow(COL_BUCKET_ID)
            val bnameCol = c.getColumnIndexOrThrow(COL_BUCKET_NAME)
            while (c.moveToNext()) {
                if (c.isNull(bidCol)) continue
                val bid = c.getString(bidCol) ?: continue
                if (bid !in counts) {
                    order.add(bid)
                    names[bid] = c.getString(bnameCol) ?: "Folder"
                    covers[bid] = c.getLong(idCol)
                    counts[bid] = 0
                }
                counts[bid] = counts[bid]!! + 1
            }
        }
        return order.map { bid ->
            MediaFolder(
                bucketId = bid,
                name = names[bid] ?: "Folder",
                count = counts[bid] ?: 0,
                coverUri = ContentUris.withAppendedId(collection, covers[bid] ?: 0L),
            )
        }.sortedByDescending { it.count }
    }
}
