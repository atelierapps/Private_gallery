package com.atelierapps.vault.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atelierapps.vault.data.entity.AlbumEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(album: AlbumEntity)

    @Query("SELECT * FROM album ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("UPDATE album SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE album SET coverId = :mediaId WHERE id = :id")
    suspend fun setCover(id: String, mediaId: String?)

    @Query("SELECT COUNT(*) FROM album")
    suspend fun count(): Int

    @Query("DELETE FROM album WHERE id = :id")
    suspend fun delete(id: String)

    // ---- membership lives on the media row's albumId ----

    @Query("UPDATE media SET albumId = :albumId WHERE id IN (:ids)")
    suspend fun setAlbumForIds(ids: List<String>, albumId: String?)

    /** Detach every member — used when an album is deleted (items are kept). */
    @Query("UPDATE media SET albumId = NULL WHERE albumId = :albumId")
    suspend fun clearMembers(albumId: String)
}
