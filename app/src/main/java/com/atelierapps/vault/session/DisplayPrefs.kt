package com.atelierapps.vault.session

import android.content.Context

/** Grid / display preferences. */
object DisplayPrefs {

    private const val PREFS = "vault_prefs"
    private const val KEY_DATE_HEADERS = "grid_date_headers"
    private const val KEY_COLUMNS = "grid_columns"

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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
