package com.atelierapps.vault.data

import com.atelierapps.vault.data.db.AlbumDao
import com.atelierapps.vault.data.db.AutoTagRuleDao
import com.atelierapps.vault.data.db.MediaDao
import com.atelierapps.vault.data.db.TagDao
import com.atelierapps.vault.data.entity.AlbumEntity
import com.atelierapps.vault.data.entity.AutoTagRuleEntity
import com.atelierapps.vault.data.entity.MediaItemEntity
import com.atelierapps.vault.data.entity.MediaTagCrossRef
import com.atelierapps.vault.data.entity.SourceType
import com.atelierapps.vault.data.entity.TagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Coordinates the media + tag + auto-tag-rule + album DAOs (spec §2, §7). */
class VaultRepository(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao,
    private val ruleDao: AutoTagRuleDao,
    private val albumDao: AlbumDao,
) {
    fun observeAll() = mediaDao.observeAll()
    fun observeTrash() = mediaDao.observeTrash()
    fun observeTrashCount() = mediaDao.observeTrashCount()
    fun observeSourceCounts() = mediaDao.observeSourceCounts()
    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAll()

    suspend fun allTrash() = withContext(Dispatchers.IO) { mediaDao.allTrash() }

    suspend fun topTags(limit: Int = 6): List<TagEntity> =
        withContext(Dispatchers.IO) { tagDao.topByUse(limit) }

    suspend fun existsByHash(hash: String): Boolean =
        withContext(Dispatchers.IO) { mediaDao.existsByHash(hash) }

    suspend fun allIds(): List<String> =
        withContext(Dispatchers.IO) { mediaDao.allIds() }

    suspend fun allMedia() =
        withContext(Dispatchers.IO) { mediaDao.allWithTags() }

    /** Move an item to the recycle bin (soft delete). Blob + thumbnail stay. */
    suspend fun trashMedia(id: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { mediaDao.softDelete(id, now) }

    /** Bring an item back out of the recycle bin. */
    suspend fun restoreMedia(id: String) =
        withContext(Dispatchers.IO) { mediaDao.restore(id) }

    /** Permanently drop the row + tag links (caller deletes the blobs). */
    suspend fun purgeMedia(id: String) =
        withContext(Dispatchers.IO) {
            mediaDao.deleteCrossRefs(id)
            mediaDao.delete(id)
        }

    /** Ids whose retention window has lapsed — ready for permanent purge. */
    suspend fun expiredTrashIds(cutoff: Long): List<String> =
        withContext(Dispatchers.IO) { mediaDao.expiredTrashIds(cutoff) }

    suspend fun setPinned(id: String, pinned: Boolean) =
        withContext(Dispatchers.IO) { mediaDao.setPinned(id, pinned) }

    /** Rename an item. Only the display name changes — the blob is untouched. */
    /** Display rotation for a video encoded the wrong way up. */
    suspend fun setVideoRotation(id: String, degrees: Int?) = mediaDao.setVideoRotation(id, degrees)

    /** Override where an item is filed as having come from. */
    suspend fun setSourceLabel(id: String, label: String?) = mediaDao.setSourceLabel(id, label)

    /** Where you got to in a video; null once it has been watched to the end. */
    suspend fun setResumePosition(id: String, millis: Long?) = mediaDao.setResumePosition(id, millis)

    suspend fun renameMedia(id: String, name: String) =
        withContext(Dispatchers.IO) {
            val clean = name.trim()
            if (clean.isNotEmpty()) mediaDao.rename(id, clean)
        }

    /** Add tags to an existing item (retroactive/bulk tagging, spec §7 v2). */
    suspend fun addTags(mediaId: String, tagNames: List<String>) =
        withContext(Dispatchers.IO) {
            if (tagNames.isEmpty()) return@withContext
            val tagIds = tagNames.map { resolveTag(it) }
            mediaDao.insertCrossRefs(tagIds.map { MediaTagCrossRef(mediaId, it) })
            tagIds.forEach { tagDao.incrementUse(it) }
        }

    /**
     * Make [tagNames] the item's exact tag set — additions and removals both.
     * The bulk tagger is additive by design (you're painting a label across a
     * selection), but editing one item needs to be able to take a tag off again.
     */
    suspend fun setTagsForMedia(mediaId: String, tagNames: List<String>) =
        withContext(Dispatchers.IO) {
            val desiredIds = tagNames.map { it.trim() }.filter { it.isNotEmpty() }
                .map { resolveTag(it) }.toSet()
            val currentIds = mediaDao.tagIdsFor(mediaId).toSet()

            val toRemove = currentIds - desiredIds
            if (toRemove.isNotEmpty()) mediaDao.unlinkTags(mediaId, toRemove.toList())

            val toAdd = desiredIds - currentIds
            if (toAdd.isNotEmpty()) {
                mediaDao.insertCrossRefs(toAdd.map { MediaTagCrossRef(mediaId, it) })
                toAdd.forEach { tagDao.incrementUse(it) }
            }
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

    // ---- auto-tag rules (§7) ----

    fun observeRules(): Flow<List<AutoTagRuleEntity>> = ruleDao.observeAll()

    suspend fun upsertRule(rule: AutoTagRuleEntity) =
        withContext(Dispatchers.IO) { ruleDao.upsert(rule) }

    suspend fun setRuleEnabled(id: String, enabled: Boolean) =
        withContext(Dispatchers.IO) { ruleDao.setEnabled(id, enabled) }

    suspend fun deleteRule(id: String) =
        withContext(Dispatchers.IO) { ruleDao.delete(id) }

    /** Tags to auto-apply to an item with this source, from all enabled rules. */
    suspend fun autoTagsFor(
        type: SourceType,
        pkg: String?,
        label: String?,
        domain: String?,
    ): List<String> = withContext(Dispatchers.IO) {
        ruleDao.allEnabled()
            .filter { it.matches(type, pkg, label, domain) }
            .flatMap { it.tags() }
            .distinct()
    }

    // ---- tag maintenance (§7) ----

    fun observeTagUsage(): Flow<List<com.atelierapps.vault.data.db.TagUsage>> = tagDao.observeUsage()

    /** Rename a tag, unless the new name collides with an existing one. */
    suspend fun renameTag(id: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        if (clean.isEmpty()) return@withContext false
        val existing = tagDao.byName(clean)
        if (existing != null && existing.id != id) return@withContext false
        tagDao.rename(id, clean)
        true
    }

    /** Delete a tag and unlink it from every item (the items themselves stay). */
    suspend fun deleteTag(id: String) = withContext(Dispatchers.IO) {
        tagDao.deleteLinks(id)
        tagDao.deleteTag(id)
    }

    /** Fold [sourceId] into [targetId]: its items gain the target tag, it goes away. */
    suspend fun mergeTags(sourceId: String, targetId: String) = withContext(Dispatchers.IO) {
        if (sourceId == targetId) return@withContext
        tagDao.repointLinks(sourceId, targetId)
        // Items that already carried the target keep their row; the ignored
        // duplicates are still pointing at the source, so clear the remainder.
        tagDao.deleteLinks(sourceId)
        tagDao.deleteTag(sourceId)
    }

    // ---- albums (§7) ----

    fun observeAlbums(): Flow<List<AlbumEntity>> = albumDao.observeAll()

    suspend fun albumCount(): Int = withContext(Dispatchers.IO) { albumDao.count() }

    suspend fun tagCount(): Int = withContext(Dispatchers.IO) { tagDao.count() }

    suspend fun createAlbum(name: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        albumDao.upsert(AlbumEntity(id, name.trim(), System.currentTimeMillis()))
        id
    }

    suspend fun renameAlbum(id: String, name: String) =
        withContext(Dispatchers.IO) { albumDao.rename(id, name.trim()) }

    /** Delete the album but keep its items (their albumId is cleared). */
    suspend fun deleteAlbum(id: String) = withContext(Dispatchers.IO) {
        albumDao.clearMembers(id)
        albumDao.delete(id)
    }

    /** Pin a specific item as the album's cover (null reverts to most-recent). */
    suspend fun setAlbumCover(albumId: String, mediaId: String?) =
        withContext(Dispatchers.IO) { albumDao.setCover(albumId, mediaId) }

    suspend fun setAlbumForItems(ids: Collection<String>, albumId: String?) =
        withContext(Dispatchers.IO) { albumDao.setAlbumForIds(ids.toList(), albumId) }

    private fun defaultColorFor(name: String): String {
        val palette = listOf("#D8B463", "#7FA88B", "#C08A6E", "#8091C0", "#B07FA8", "#6EA8B0")
        return palette[(name.lowercase().hashCode() and 0x7fffffff) % palette.size]
    }
}
