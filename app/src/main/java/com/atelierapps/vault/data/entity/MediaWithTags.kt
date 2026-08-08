package com.atelierapps.vault.data.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/** A media item with its tags, resolved via the join table (spec §2). */
data class MediaWithTags(
    @Embedded val media: MediaItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = MediaTagCrossRef::class,
            parentColumn = "mediaId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<TagEntity>,
)
