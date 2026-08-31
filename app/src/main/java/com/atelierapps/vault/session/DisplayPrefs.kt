package com.atelierapps.vault.session

import android.content.Context

/** Grid / display preferences. */
object DisplayPrefs {

    private const val PREFS = "vault_prefs"
    private const val KEY_DATE_HEADERS = "grid_date_headers"
    private const val KEY_COLUMNS = "grid_columns"
    private const val KEY_SORT = "grid_sort"

    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 6

    fun dateHeaders(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DATE_HEADERS, true)

    fun setDateHeaders(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DATE_HEADERS, value).apply()
    }

    fun columns(context: Context): Int =
        prefs(context).getInt(KEY_COLUMNS, 3).coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    fun setColumns(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_COLUMNS, value.coerceIn(MIN_COLUMNS, MAX_COLUMNS)).apply()
    }

    /**
     * The grid's sort order, stored by enum name so reordering or renaming the
     * enum can't silently repoint an existing preference at the wrong sort — an
     * unknown name just falls back to the default.
     */
    fun sort(context: Context): String? = prefs(context).getString(KEY_SORT, null)

    fun setSort(context: Context, name: String) {
        prefs(context).edit().putString(KEY_SORT, name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
