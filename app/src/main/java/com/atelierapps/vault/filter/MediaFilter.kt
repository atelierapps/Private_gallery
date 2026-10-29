package com.atelierapps.vault.filter

import com.atelierapps.vault.data.entity.MediaWithTags

/**
 * Date windows for the "Date" filter (spec §7). Rolling windows relative to now
 * — simple and honest; no calendar-boundary math in v1.
 */
enum class DateBucket(val label: String, val windowMillis: Long?) {
    ANY("Any time", null),
    DAY("Last 24 hours", 24L * 60 * 60 * 1000),
    WEEK("Last 7 days", 7L * 24 * 60 * 60 * 1000),
    MONTH("Last 30 days", 30L * 24 * 60 * 60 * 1000),
    YEAR("Last year", 365L * 24 * 60 * 60 * 1000);

    fun matches(dateMillis: Long, now: Long): Boolean {
        val w = windowMillis ?: return true
        return now - dateMillis <= w
    }
}

/** Media-type filter (spec §7). */
enum class MediaTypeFilter { ALL, IMAGE, VIDEO }

/**
 * The active filter (spec §7). Combining semantics:
 *  - **across** categories → AND (source AND tag AND date),
 *  - within **sources** → OR (union — an item can't be from two apps at once),
 *  - within **tags** → AND (must carry *all* selected tags — "keep AND travel").
 *
 * Applied in-memory over the observed list. For a personal-scale vault this is
 * simple and correct; a Room `@RawQuery` path is a later optimization if a
 * library grows large.
 */
data class MediaFilter(
    val type: MediaTypeFilter = MediaTypeFilter.ALL,
    val sources: Set<String> = emptySet(),   // source keys: sourcePackage, or sourceLabel for imports
    val tagNames: Set<String> = emptySet(),  // case-insensitive
    val date: DateBucket = DateBucket.ANY,
    /** Pinned items only. Pin is what this app has instead of a heart. */
    val favourites: Boolean = false,
    /** Nothing tagged yet: the "still to sort" pile after an import. */
    val untagged: Boolean = false,
    /** Videos of [SHORT_CLIP_MILLIS] or less. */
    val shortClips: Boolean = false,
    /** Never opened in the viewer (since schema 9, when the history began). */
    val unwatched: Boolean = false,
) {
    val isEmpty: Boolean
        get() = type == MediaTypeFilter.ALL && sources.isEmpty() &&
            tagNames.isEmpty() && date == DateBucket.ANY &&
            !favourites && !untagged && !shortClips && !unwatched

    fun matches(item: MediaWithTags, now: Long): Boolean {
        val isVideo = item.media.mimeType.startsWith("video/")
        when (type) {
            MediaTypeFilter.IMAGE -> if (isVideo) return false
            MediaTypeFilter.VIDEO -> if (!isVideo) return false
            MediaTypeFilter.ALL -> {}
        }
        if (sources.isNotEmpty()) {
            val sourceKey = item.media.sourcePackage ?: item.media.sourceLabel
            if (sourceKey !in sources) return false
        }
        if (favourites && !item.media.isPinned) return false
        if (untagged && item.tags.isNotEmpty()) return false
        if (unwatched && item.media.viewCount > 0) return false
        if (shortClips) {
            // A video with no recorded length is left out rather than guessed
            // at: claiming an unknown clip is short is the worse mistake.
            val d = item.media.durationMillis
            if (!isVideo || d == null || d <= 0L || d > SHORT_CLIP_MILLIS) return false
        }
        if (tagNames.isNotEmpty()) {
            val itemTags = item.tags.mapTo(HashSet()) { it.name.lowercase() }
            if (tagNames.any { it.lowercase() !in itemTags }) return false
        }
        return date.matches(item.media.dateTakenMillis, now)
    }

    /** Toggle a type on/off (selecting the active one clears back to ALL). */
    fun withType(t: MediaTypeFilter) = copy(type = if (type == t) MediaTypeFilter.ALL else t)

    fun toggleSource(pkg: String) =
        copy(sources = if (pkg in sources) sources - pkg else sources + pkg)

    fun toggleTag(name: String) =
        copy(tagNames = if (name in tagNames) tagNames - name else tagNames + name)

    fun withDate(bucket: DateBucket) = copy(date = bucket)

    fun toggleFavourites() = copy(favourites = !favourites)
    fun toggleUntagged() = copy(untagged = !untagged)
    fun toggleShortClips() = copy(shortClips = !shortClips)
    fun toggleUnwatched() = copy(unwatched = !unwatched)

    companion object {
        const val SHORT_CLIP_MILLIS = 15_000L
    }
}
