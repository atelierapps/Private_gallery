package com.atelierapps.vault.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.atelierapps.vault.data.entity.MediaItemEntity
import com.atelierapps.vault.data.entity.MediaTagCrossRef
import com.atelierapps.vault.data.entity.MediaWithTags
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(media: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<MediaTagCrossRef>)

    // "Live" = not in the recycle bin. Every grid/filter query excludes trashed
    // rows; the trash screen queries the complement.
    @Transaction
    @Query("SELECT * FROM media WHERE deletedAtMillis IS NULL ORDER BY importedAtMillis DESC")
    fun observeAll(): Flow<List<MediaWithTags>>

    @Transaction
    @Query("SELECT * FROM media WHERE deletedAtMillis IS NULL ORDER BY importedAtMillis DESC")
    suspend fun allWithTags(): List<MediaWithTags>

    /** Recycle bin: soft-deleted rows, most-recently-deleted first (§8). */
    @Transaction
    @Query("SELECT * FROM media WHERE deletedAtMillis IS NOT NULL ORDER BY deletedAtMillis DESC")
    fun observeTrash(): Flow<List<MediaWithTags>>

    @Transaction
    @Query("SELECT * FROM media WHERE deletedAtMillis IS NOT NULL ORDER BY deletedAtMillis DESC")
    suspend fun allTrash(): List<MediaWithTags>

    @Query("SELECT COUNT(*) FROM media WHERE deletedAtMillis IS NOT NULL")
    fun observeTrashCount(): Flow<Int>

    @Transaction
    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun byId(id: String): MediaWithTags?

    @Query("DELETE FROM media_tag WHERE mediaId = :id")
    suspend fun deleteCrossRefs(id: String)

    @Query("UPDATE media SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    /** Move to the recycle bin (soft delete). Blob + thumbnail stay on disk. */
    @Query("UPDATE media SET deletedAtMillis = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** Bring an item back out of the recycle bin. */
    @Query("UPDATE media SET deletedAtMillis = NULL WHERE id = :id")
    suspend fun restore(id: String)

    /** Trashed ids past the retention cutoff — eligible for permanent purge. */
    @Query("SELECT id FROM media WHERE deletedAtMillis IS NOT NULL AND deletedAtMillis < :cutoff")
    suspend fun expiredTrashIds(cutoff: Long): List<String>

    // Dedup considers live items only, so re-importing something you trashed
    // brings back a fresh live copy instead of silently vanishing into the bin.
    @Query("SELECT EXISTS(SELECT 1 FROM media WHERE contentHash = :hash AND deletedAtMillis IS NULL LIMIT 1)")
    suspend fun existsByHash(hash: String): Boolean

    @Query("SELECT id FROM media WHERE deletedAtMillis IS NULL")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Distinct sources with counts — the "By source" filter chips (§7). Keyed by
     * the app package when known (share path) and otherwise by the source label,
     * so imported gallery folders ("Instagram", "Download", …) appear as sources
     * too, not just share-sheet apps.
     */
    @Query(
        "SELECT COALESCE(sourcePackage, sourceLabel) AS pkg, sourceLabel AS label, COUNT(*) AS count " +
            "FROM media WHERE COALESCE(sourcePackage, sourceLabel) IS NOT NULL AND deletedAtMillis IS NULL " +
            "GROUP BY COALESCE(sourcePackage, sourceLabel) ORDER BY count DESC",
    )
    fun observeSourceCounts(): Flow<List<SourceCount>>

    data class SourceCount(val pkg: String, val label: String?, val count: Int)
}
