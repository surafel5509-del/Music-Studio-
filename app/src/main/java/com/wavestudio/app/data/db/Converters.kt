package com.wavestudio.app.data.db

import androidx.room.TypeConverter

class Converters {
    // Placeholder converters (if we add more fields like lists)
    @TypeConverter
    fun fromStringList(list: List<String>?): String = list?.joinToString("|") ?: ""
    @TypeConverter
    fun toStringList(s: String?): List<String> =
        if (s.isNullOrEmpty()) emptyList() else s.split("|")
}
