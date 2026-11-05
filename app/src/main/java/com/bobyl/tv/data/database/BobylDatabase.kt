package com.bobyl.tv.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [StorageMeta::class, FilmRow::class, FilmDetailsRow::class, HistoryRow::class,
    PersonalRatingRow::class, LegacyRatingRow::class, PlaybackSelectionRow::class, LegacyImportRow::class,
    CacheRow::class, LocalFileRow::class], version = 1, exportSchema = true)
internal abstract class BobylDatabase : RoomDatabase() {
    abstract fun storage(): StorageDao

    companion object {
        const val Name = "bobyl.db"
        fun build(context: Context, name: String = Name): BobylDatabase =
            Room.databaseBuilder(context.applicationContext, BobylDatabase::class.java, name).build()
    }
}
