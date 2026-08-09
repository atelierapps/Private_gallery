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
)
