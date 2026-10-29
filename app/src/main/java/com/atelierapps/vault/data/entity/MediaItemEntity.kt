package com.atelierapps.vault.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Metadata for one encrypted media blob (spec §2). The blob itself lives at
 * `vault/<id>` and its thumbnail at `thumbs/<id>`; only this row is in Room.
 *
 * Indices on source columns back the filter bar (spec §7). Note (spec §2.1):
 * this table is plaintext — filenames and source host are readable by anyone
 * with filesystem access, which is why `sourceUrl` is intentionally absent and
 * only the host is kept.
 */
@Entity(
    tableName = "media",
    indices = [
        Index("sourcePackage"),
        Index("sourceDomain"),
        Index("importedAtMillis"),
        Index("contentHash"),
        Index("deletedAtMillis"),
    ],
)
data class MediaItemEntity(
    @PrimaryKey val id: String,            // UUID, == blob filename
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
    val durationMillis: Long?,             // video only
    val widthPx: Int,
    val heightPx: Int,
    val importedAtMillis: Long,
    val albumId: String?,
    val contentHash: String?,              // sha-256 of plaintext, for dedup (§4.2)

    // source attribution (§6)
    val sourceType: SourceType,
    val sourcePackage: String?,            // e.g. com.whatsapp
    val sourceLabel: String?,              // cached display name, e.g. "WhatsApp"
    val sourceDomain: String?,             // host only — no full URL (§2.1)

    @ColumnInfo(defaultValue = "0")
    val cryptoMode: Int,                   // EnvelopeFormat.MODE_GCM / MODE_CTR

    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false,         // pinned items sort to the top

    // Recycle bin: null == live; a timestamp == soft-deleted, blob kept on disk
    // until the retention window lapses and it's purged (§8). Indexed so the
    // "live only" filter on every grid query stays cheap. No DB defaultValue —
    // nullable columns need none, and declaring DEFAULT NULL risks a Room
    // schema-validation mismatch against what PRAGMA reports.
    val deletedAtMillis: Long? = null,

    // How far into a video you got, so a long one resumes across app restarts
    // rather than only within a session. Null once it has been watched to the
    // end, or for anything that isn't a video. Only worth keeping now the
    // database is encrypted — as a plaintext column this was a log of what you
    // watched and how far.
    val resumePositionMillis: Long? = null,

    // How far to turn this video on playback. A file that was encoded crooked
    // stays crooked, so correcting it once should stick rather than being
    // re-done every time it is opened. Display-only: the bytes aren't touched.
    val videoRotationDegrees: Int? = null,

    // A line or two of your own about the item ("use this transition"),
    // searchable. In this table, so it is encrypted with everything else and
    // goes into backups the same way tags do.
    val note: String? = null,

    // When it was last on screen for a couple of seconds, and how many times.
    // "Unwatched" and "Not seen lately" come from these. The history starts
    // with schema 9: everything already in the library begins as never seen.
    val lastViewedAtMillis: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val viewCount: Int = 0,
)
