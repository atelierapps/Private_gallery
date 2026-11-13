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
    /**
     * The tags this album follows, as JSON (`{"all":false,"tags":[{"id",
     * "name"}]}`), or null for an album filled by hand. Set when the album is
     * made or filled from tags; new items carrying them are then offered on
     * opening it. Tags by id with the name alongside, like saved views, so a
     * rename doesn't break it.
     */
    val tagRule: String? = null,
)
