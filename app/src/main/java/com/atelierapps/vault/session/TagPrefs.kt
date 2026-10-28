package com.atelierapps.vault.session

import android.content.Context

/**
 * How tag lists are ordered, and which tags were used most recently.
 *
 * Recency lives here rather than in the database to avoid a schema change for
 * an ordering hint. That is only acceptable because what is stored is the tag's
 * **id** — a random UUID — never its name: these preferences are not encrypted,
 * and a list of tag names would be a plaintext summary of what is in here.
 * An id for a tag that has since been deleted simply matches nothing.
 */
object TagPrefs {

    private const val PREFS = "vault_prefs"
    private const val KEY_RECENT = "tag_recent_ids"
    private const val KEY_ORDER = "tag_order"
    private const val MAX_RECENT = 12

    enum class Order(val label: String) {
        RECENT("Recent"),
        MOST_USED("Most used"),
        NAME("A–Z"),
    }

    /** Most recent first. */
    fun recentIds(context: Context): List<String> =
        prefs(context).getString(KEY_RECENT, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /**
     * Move [ids] to the front, keeping the list short. Synchronized because the
     * import worker and the UI can both tag at once, and this is a
     * read-modify-write.
     */
    @Synchronized
    fun noteUsed(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val next = (ids.toList() + recentIds(context)).distinct().take(MAX_RECENT)
        prefs(context).edit().putString(KEY_RECENT, next.joinToString(",")).apply()
    }

    fun order(context: Context): Order =
        prefs(context).getString(KEY_ORDER, null)
            ?.let { name -> Order.entries.firstOrNull { it.name == name } }
            ?: Order.RECENT

    fun setOrder(context: Context, order: Order) {
        prefs(context).edit().putString(KEY_ORDER, order.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
