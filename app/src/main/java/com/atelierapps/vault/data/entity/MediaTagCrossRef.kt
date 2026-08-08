package com.atelierapps.vault.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Media ↔ tag join (spec §2). Indexed both directions — the filter bar queries
 * "media with tag X" and the viewer queries "tags of media Y" constantly.
 */
@Entity(
    tableName = "media_tag",
    primaryKeys = ["mediaId", "tagId"],
    indices = [Index("mediaId"), Index("tagId")],
)
data class MediaTagCrossRef(
    val mediaId: String,
    val tagId: String,
)
