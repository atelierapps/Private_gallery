package com.atelierapps.vault.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atelierapps.vault.data.entity.AutoTagRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoTagRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AutoTagRuleEntity)

    @Query("SELECT * FROM auto_tag_rule ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<AutoTagRuleEntity>>

    /** Snapshot used by the save pipeline to auto-tag a new item. */
    @Query("SELECT * FROM auto_tag_rule WHERE enabled = 1")
    suspend fun allEnabled(): List<AutoTagRuleEntity>

    @Query("UPDATE auto_tag_rule SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM auto_tag_rule WHERE id = :id")
    suspend fun delete(id: String)
}
