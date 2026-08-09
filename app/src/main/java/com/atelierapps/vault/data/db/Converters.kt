package com.atelierapps.vault.data.db

import androidx.room.TypeConverter
import com.atelierapps.vault.data.entity.RuleMatchKind
import com.atelierapps.vault.data.entity.SourceType

class Converters {
    @TypeConverter
    fun sourceTypeToString(value: SourceType): String = value.name

    @TypeConverter
    fun stringToSourceType(value: String): SourceType =
        runCatching { SourceType.valueOf(value) }.getOrDefault(SourceType.UNKNOWN)

    @TypeConverter
    fun ruleMatchKindToString(value: RuleMatchKind): String = value.name

    @TypeConverter
    fun stringToRuleMatchKind(value: String): RuleMatchKind =
        runCatching { RuleMatchKind.valueOf(value) }.getOrDefault(RuleMatchKind.SOURCE)
}
