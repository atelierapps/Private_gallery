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

    @Query("SELECT * FROM tags ORDER BY useCount DESC, name COLLATE NOCASE ASC LIMIT :limit")
    suspend fun topByUse(limit: Int): List<TagEntity>

    @Query("SELECT * FROM tags ORDER BY useCount DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("UPDATE tags SET useCount = useCount + 1 WHERE id = :id")
    suspend fun incrementUse(id: String)
}
