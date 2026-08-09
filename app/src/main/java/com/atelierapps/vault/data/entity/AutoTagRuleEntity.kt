package com.atelierapps.vault.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** What an auto-tag rule matches against. */
enum class RuleMatchKind {
    /** Free-text contains-match against the item's source label, package, or domain. */
    SOURCE,

    /** Exact match against how the item was added ([SourceType]). */
    TYPE,
}

/**
 * A rule that auto-applies tags to items as they're saved (spec §7). Matching is
 * deliberately forgiving for [RuleMatchKind.SOURCE] — one rule value of
 * "instagram" catches the label "Instagram", the package "com.instagram.android",
 * and the domain "instagram.com" — so the owner never has to know package names.
 */
@Entity(tableName = "auto_tag_rule")
data class AutoTagRuleEntity(
    @PrimaryKey val id: String,
    val matchKind: RuleMatchKind,
    val matchValue: String,          // free text (SOURCE) or a SourceType.name (TYPE)
    val tagNames: String,            // comma-joined tag names to apply
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    val createdAtMillis: Long,
) {
    /** True if this rule fires for an item with the given source attribution. */
    fun matches(type: SourceType, pkg: String?, label: String?, domain: String?): Boolean {
        if (!enabled) return false
        return when (matchKind) {
            RuleMatchKind.TYPE -> type.name.equals(matchValue.trim(), ignoreCase = true)
            RuleMatchKind.SOURCE -> {
                val needle = matchValue.trim()
                needle.isNotEmpty() &&
                    listOfNotNull(label, pkg, domain).any { it.contains(needle, ignoreCase = true) }
            }
        }
    }

    /** The tags this rule applies, cleaned and de-duplicated. */
    fun tags(): List<String> =
        tagNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
