package com.atelierapps.vault.data.db

import androidx.room.TypeConverter
import com.atelierapps.vault.data.entity.SourceType

class Converters {
    @TypeConverter
    fun sourceTypeToString(value: SourceType): String = value.name

    @TypeConverter
    fun stringToSourceType(value: String): SourceType =
        runCatching { SourceType.valueOf(value) }.getOrDefault(SourceType.UNKNOWN)
}
