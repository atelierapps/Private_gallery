package com.atelierapps.vault.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atelierapps.vault.data.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TagEntity)

    /** Case-insensitive lookup — tag names are unique regardless of case (§7). */
    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): TagEntity?

    /**
     * Ordering is computed from the join, not from the stored `useCount`.
     * `useCount` only ever incremented — nothing decremented it when items were
     * deleted or binned — so it drifted upward and the "most used" ordering
     * slowly became wrong. Counting live (non-trashed) members can't drift.
     */
    @Query(
        """
        SELECT t.* FROM tags t
        LEFT JOIN media_tag mt ON mt.tagId = t.id
        LEFT JOIN media m ON m.id = mt.mediaId AND m.deletedAtMillis IS NULL
        GROUP BY t.id
        ORDER BY COUNT(m.id) DESC, t.name COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun topByUse(limit: Int): List<TagEntity>

    @Query(
        """
        SELECT t.* FROM tags t
        LEFT JOIN media_tag mt ON mt.tagId = t.id
        LEFT JOIN media m ON m.id = mt.mediaId AND m.deletedAtMillis IS NULL
        GROUP BY t.id
        ORDER BY COUNT(m.id) DESC, t.name COLLATE NOCASE ASC
        """,
    )
    fun observeAll(): Flow<List<TagEntity>>

    /** Kept so the stored column stays roughly meaningful; ordering no longer uses it. */
    @Query("UPDATE tags SET useCount = useCount + 1 WHERE id = :id")
    suspend fun incrementUse(id: String)
}
