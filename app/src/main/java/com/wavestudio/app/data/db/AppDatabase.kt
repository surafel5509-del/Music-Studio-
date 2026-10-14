package com.wavestudio.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.wavestudio.app.data.db.dao.ProjectDao
import com.wavestudio.app.data.db.entity.ProjectEntity

@Database(
    entities = [ProjectEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun create(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "wavestudio.db")
                // No destructive fallback: a schema change must ship a Migration so the project index is never wiped.
                .build().also { INSTANCE = it }
        }
    }
}
