package com.safedrive.bus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 기기 내부 저장소. 외부 전송 경로는 만들지 않는다.
 */
@Database(
    entities = [TripEntity::class, EventEntity::class, SpeedZoneEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun eventDao(): EventDao
    abstract fun speedZoneDao(): SpeedZoneDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "safedrive.db"
            ).build().also { instance = it }
        }
    }
}
