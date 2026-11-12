package com.atelierapps.vault.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atelierapps.vault.data.entity.SavedViewEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedViewDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(view: SavedViewEntity)

    /** Oldest first, so a new view lands at the end of the row, not in front of the ones you use. */
    @Query("SELECT * FROM saved_view ORDER BY createdAtMillis ASC")
    fun observeAll(): Flow<List<SavedViewEntity>>

    @Query("UPDATE saved_view SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM saved_view WHERE id = :id")
    suspend fun delete(id: String)
}
