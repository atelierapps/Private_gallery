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

    @Transaction
    @Query("SELECT * FROM media ORDER BY importedAtMillis DESC")
    fun observeAll(): Flow<List<MediaWithTags>>

    @Transaction
    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun byId(id: String): MediaWithTags?

    @Query("SELECT EXISTS(SELECT 1 FROM media WHERE contentHash = :hash LIMIT 1)")
    suspend fun existsByHash(hash: String): Boolean

    @Query("SELECT id FROM media")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun delete(id: String)

    /** Distinct source apps with counts — the "By source" filter chips (§7). */
    @Query(
        "SELECT sourcePackage AS pkg, sourceLabel AS label, COUNT(*) AS count " +
            "FROM media WHERE sourcePackage IS NOT NULL " +
            "GROUP BY sourcePackage ORDER BY count DESC",
    )
    fun observeSourceCounts(): Flow<List<SourceCount>>

    data class SourceCount(val pkg: String, val label: String?, val count: Int)
}
