package com.atelierapps.vault.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A named album — a curated collection of vault items (spec §7). Membership is
 * held by [MediaItemEntity.albumId]; an item belongs to at most one album, which
 * is what distinguishes albums from the many-to-many tag system.
 */
@Entity(tableName = "album")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMillis: Long,
    /** Chosen cover item; null falls back to the most recent member. */
    val coverId: String? = null,
)
