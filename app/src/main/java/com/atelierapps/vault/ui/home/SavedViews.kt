package com.atelierapps.vault.ui.home

import com.atelierapps.vault.data.entity.SavedViewEntity
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.filter.DateBucket
import com.atelierapps.vault.filter.MediaFilter
import com.atelierapps.vault.filter.MediaTypeFilter
import org.json.JSONArray
import org.json.JSONObject

/**
 * One chip in the views row: a filter, and optionally the order to show it in.
 *
 * A view is a question, not a list — "videos tagged X under five minutes" —
 * so it stays current without anyone maintaining it.
 */
data class SavedView(
    val id: String,
    val name: String,
    val filter: MediaFilter,
    /** Null keeps whatever order the grid is in. */
    val sort: SortOrder?,
    /** The two that come with the app; they can't be renamed or deleted. */
    val builtIn: Boolean = false,
)

object SavedViews {

    const val CONTINUE_ID = "builtin:continue"
    const val MOST_WATCHED_ID = "builtin:most"
    const val WATCH_LATER_ID = "builtin:later"

    /**
     * Built from the view history and resume points the viewer already
     * records, so both work on day one for anything watched since schema 9.
     */
    val builtIns: List<SavedView> = listOf(
        // First: it's the one you put there on purpose.
        SavedView(
            id = WATCH_LATER_ID,
            name = "Watch later",
            filter = MediaFilter(watchLater = true),
            sort = SortOrder.QUEUE,
            builtIn = true,
        ),
        SavedView(
            id = CONTINUE_ID,
            name = "Continue watching",
            filter = MediaFilter(type = MediaTypeFilter.VIDEO, inProgress = true),
            sort = SortOrder.LAST_WATCHED,
            builtIn = true,
        ),
        SavedView(
            id = MOST_WATCHED_ID,
            name = "Most watched",
            filter = MediaFilter(watched = true),
            sort = SortOrder.MOST_WATCHED,
            builtIn = true,
        ),
    )

    /**
     * Tags are stored by id with the name alongside: a renamed tag keeps its
     * views working, and a deleted one still matches nothing rather than
     * silently dropping out and widening the view.
     */
    fun encode(filter: MediaFilter, sort: SortOrder?, tags: List<TagEntity>): String {
        val tagArray = JSONArray()
        filter.tagNames.forEach { name ->
            val tag = tags.firstOrNull { it.name.equals(name, ignoreCase = true) }
            tagArray.put(JSONObject().put("id", tag?.id ?: "").put("name", name))
        }
        return JSONObject()
            .put("v", 1)
            .put("type", filter.type.name)
            .put("sources", JSONArray(filter.sources.toList()))
            .put("tags", tagArray)
            .put("date", filter.date.name)
            .put("favourites", filter.favourites)
            .put("untagged", filter.untagged)
            .put("shortClips", filter.shortClips)
            .put("unwatched", filter.unwatched)
            .put("inProgress", filter.inProgress)
            .put("watched", filter.watched)
            .put("watchLater", filter.watchLater)
            .apply { if (sort != null) put("sort", sort.name) }
            .toString()
    }

    /** Null if the spec can't be read; the row then just doesn't show it. */
    fun decode(row: SavedViewEntity, tags: List<TagEntity>): SavedView? = runCatching {
        val o = JSONObject(row.spec)
        val byId = tags.associateBy { it.id }
        val tagArray = o.optJSONArray("tags") ?: JSONArray()
        val tagNames = (0 until tagArray.length()).map { i ->
            val t = tagArray.getJSONObject(i)
            byId[t.optString("id")]?.name ?: t.optString("name")
        }.filter { it.isNotEmpty() }.toSet()
        val sourceArray = o.optJSONArray("sources") ?: JSONArray()
        val sources = (0 until sourceArray.length()).map { sourceArray.getString(it) }.toSet()
        val filter = MediaFilter(
            type = enumOr(o.optString("type"), MediaTypeFilter.ALL),
            sources = sources,
            tagNames = tagNames,
            date = enumOr(o.optString("date"), DateBucket.ANY),
            favourites = o.optBoolean("favourites"),
            untagged = o.optBoolean("untagged"),
            shortClips = o.optBoolean("shortClips"),
            unwatched = o.optBoolean("unwatched"),
            inProgress = o.optBoolean("inProgress"),
            watched = o.optBoolean("watched"),
            watchLater = o.optBoolean("watchLater"),
        )
        val sort = o.optString("sort").takeIf { it.isNotEmpty() }
            ?.let { name -> SortOrder.entries.firstOrNull { it.name == name } }
        SavedView(row.id, row.name, filter, sort)
    }.getOrNull()

    /** "Videos · #beach · Short · longest first" — what saving would keep. */
    fun describe(filter: MediaFilter, sort: SortOrder?, sourceLabel: (String) -> String): String {
        val parts = buildList {
            when (filter.type) {
                MediaTypeFilter.IMAGE -> add("Photos")
                MediaTypeFilter.VIDEO -> add("Videos")
                MediaTypeFilter.ALL -> {}
            }
            filter.tagNames.sorted().forEach { add("#$it") }
            filter.sources.forEach { add("from " + sourceLabel(it)) }
            if (filter.date != DateBucket.ANY) add(filter.date.label)
            if (filter.favourites) add("Favourites")
            if (filter.untagged) add("Untagged")
            if (filter.shortClips) add("Short")
            if (filter.unwatched) add("Unwatched")
            if (filter.inProgress) add("In progress")
            if (filter.watched) add("Watched")
            if (filter.watchLater) add("Watch later")
        }
        val what = parts.joinToString(" · ").ifEmpty { "Everything" }
        return if (sort == null) what else what + " — " + sort.label.lowercase()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
