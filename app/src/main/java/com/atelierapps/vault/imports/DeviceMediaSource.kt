package com.atelierapps.vault.imports

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.atelierapps.vault.data.entity.SourceType

/**
 * Enumerates importable media (spec §4, §4.1).
 *
 * Device gallery: queries `MediaStore.Files` for images+videos, newest first,
 * paginated (spec §4). Deliberately NOT the Photo Picker — its URIs can't be
 * passed to `createDeleteRequest()`, so originals could never be removed.
 *
 * Folder: enumerates image/video children of a SAF tree the user picked.
 */
object DeviceMediaSource {

    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun queryDevice(context: Context, limit: Int, offset: Int): List<DeviceMedia> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                ),
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Files.FileColumns.DATE_TAKEN, MediaStore.Files.FileColumns.DATE_MODIFIED),
            )
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }

        val out = ArrayList<DeviceMedia>()
        context.contentResolver.query(collection, projection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val mime = c.getString(mimeCol) ?: continue
                val taken = if (!c.isNull(takenCol)) c.getLong(takenCol)
                    else c.getLong(modCol) * 1000L
                val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                out += DeviceMedia(
                    uri = ContentUris.withAppendedId(collection, id),
                    mimeType = mime,
                    displayName = c.getString(nameCol) ?: "IMG_$id",
                    dateTakenMillis = taken,
                    durationMillis = if (isVideo && !c.isNull(durCol)) c.getLong(durCol) else null,
                    origin = SourceType.LOCAL_IMPORT,
                )
            }
        }
        return out
    }

    fun listFolder(context: Context, treeUri: Uri): List<DeviceMedia> {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile && (it.type?.startsWith("image/") == true || it.type?.startsWith("video/") == true) }
            .sortedByDescending { it.lastModified() }
            .map { doc ->
                DeviceMedia(
                    uri = doc.uri,
                    mimeType = doc.type ?: "application/octet-stream",
                    displayName = doc.name ?: "file",
                    dateTakenMillis = doc.lastModified(),
                    durationMillis = null,
                    origin = SourceType.FOLDER_IMPORT,
                )
            }
    }
}
