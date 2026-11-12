package com.atelierapps.vault.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved view: a named filter and sort that the home screen can switch to in
 * one tap. It holds the *question*, not a list of items, so it keeps itself up
 * to date — everything imported later that fits it turns up in it.
 *
 * [spec] is the filter as JSON (see `SavedViews` in ui/home). In the encrypted
 * database rather than prefs, like albums: tag names and sources together are
 * a summary of the library, and prefs are plaintext.
 */
@Entity(tableName = "saved_view")
data class SavedViewEntity(
    @PrimaryKey val id: String,
    val name: String,
    val spec: String,
    val createdAtMillis: Long,
)
