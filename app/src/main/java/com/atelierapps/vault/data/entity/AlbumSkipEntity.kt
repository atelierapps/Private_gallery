package com.atelierapps.vault.data.entity

import androidx.room.Entity

/**
 * An item a tag-following album should stop offering: turned down when
 * reviewing new matches, or taken out of the album by hand. Without this the
 * album would ask about the same items every time it opened.
 *
 * Rows outlive their media harmlessly (ids are never reused) and go with
 * their album.
 */
@Entity(tableName = "album_skip", primaryKeys = ["albumId", "mediaId"])
data class AlbumSkipEntity(
    val albumId: String,
    val mediaId: String,
)
