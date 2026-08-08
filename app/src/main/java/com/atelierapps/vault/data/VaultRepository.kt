package com.atelierapps.vault.data

import com.atelierapps.vault.data.db.MediaDao
import com.atelierapps.vault.data.db.TagDao
import com.atelierapps.vault.data.entity.MediaItemEntity
import com.atelierapps.vault.data.entity.MediaTagCrossRef
import com.atelierapps.vault.data.entity.TagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Coordinates the media + tag DAOs (spec §2, §7). */
class VaultRepository(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao,
) {
    fun observeAll() = mediaDao.observeAll()
    fun observeSourceCounts() = mediaDao.observeSourceCounts()
    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAll()

    suspend fun topTags(limit: Int = 6): List<TagEntity> =
        withContext(Dispatchers.IO) { tagDao.topByUse(limit) }

    suspend fun existsByHash(hash: String): Boolean =
        withContext(Dispatchers.IO) { mediaDao.existsByHash(hash) }

    suspend fun allIds(): List<String> =
        withContext(Dispatchers.IO) { mediaDao.allIds() }

    suspend fun allMedia() =
        withContext(Dispatchers.IO) { mediaDao.allWithTags() }

    suspend fun deleteMedia(id: String) =
        withContext(Dispatchers.IO) {
            mediaDao.deleteCrossRefs(id)
            mediaDao.delete(id)
        }

    /**
     * Persist a media row and its tags atomically-ish: the row insert and tag
     * bump happen after the blob is already verified on disk (spec §4 hard rule).
     * Tag names are resolved case-insensitively, created on first use, and their
     * `useCount` bumped so the share-sheet chips reflect real usage (§7).
     */
    suspend fun saveMedia(media: MediaItemEntity, tagNames: List<String>) =
        withContext(Dispatchers.IO) {
            mediaDao.insert(media)
            if (tagNames.isEmpty()) return@withContext
            val tagIds = tagNames.map { resolveTag(it) }
            mediaDao.insertCrossRefs(tagIds.map { MediaTagCrossRef(media.id, it) })
            tagIds.forEach { tagDao.incrementUse(it) }
        }

    private suspend fun resolveTag(rawName: String): String {
        val name = rawName.trim()
        tagDao.byName(name)?.let { return it.id }
        val tag = TagEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            colorHex = defaultColorFor(name),
            useCount = 0,
            createdAtMillis = System.currentTimeMillis(),
        )
        return runCatching { tagDao.insert(tag); tag.id }
            // lost a race on the unique index — re-read the winner
            .getOrElse { tagDao.byName(name)?.id ?: throw it }
    }

    private fun defaultColorFor(name: String): String {
        val palette = listOf("#D8B463", "#7FA88B", "#C08A6E", "#8091C0", "#B07FA8", "#6EA8B0")
        return palette[(name.lowercase().hashCode() and 0x7fffffff) % palette.size]
    }
}
