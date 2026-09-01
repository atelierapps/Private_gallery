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
) {
    val isEmpty: Boolean
        get() = type == MediaTypeFilter.ALL && sources.isEmpty() &&
            tagNames.isEmpty() && date == DateBucket.ANY

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
}
