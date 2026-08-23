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

    // ---- maintenance (§7): tags used to be create-only, so a typo was forever ----

    /** Every tag with its live member count, for the tag manager. */
    @Query(
        """
        SELECT t.id AS id, t.name AS name, t.colorHex AS colorHex, COUNT(m.id) AS liveCount
        FROM tags t
        LEFT JOIN media_tag mt ON mt.tagId = t.id
        LEFT JOIN media m ON m.id = mt.mediaId AND m.deletedAtMillis IS NULL
        GROUP BY t.id
        ORDER BY liveCount DESC, t.name COLLATE NOCASE ASC
        """,
    )
    fun observeUsage(): Flow<List<TagUsage>>

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM media_tag WHERE tagId = :id")
    suspend fun deleteLinks(id: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: String)

    /**
     * Repoint one tag's members onto another. OR IGNORE covers items that already
     * carry the target tag (the (mediaId, tagId) primary key would collide);
     * [deleteLinks] then clears whatever those ignored rows left behind.
     */
    @Query("UPDATE OR IGNORE media_tag SET tagId = :targetId WHERE tagId = :sourceId")
    suspend fun repointLinks(sourceId: String, targetId: String)

}

/** A tag plus how many live (non-trashed) items carry it. */
data class TagUsage(
    val id: String,
    val name: String,
    val colorHex: String,
    val liveCount: Int,
)
