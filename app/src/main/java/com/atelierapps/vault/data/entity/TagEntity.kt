package com.atelierapps.vault.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user tag (spec §7). `useCount` drives the quick-tag chips in the share sheet
 * and the tag chips in the filter bar. Names are unique case-insensitively —
 * enforced by a NOCASE unique index plus normalized lookups in the DAO.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey val id: String,   // UUID
    val name: String,             // display form; uniqueness is case-insensitive
    val colorHex: String,
    val useCount: Int,
    val createdAtMillis: Long,
)
