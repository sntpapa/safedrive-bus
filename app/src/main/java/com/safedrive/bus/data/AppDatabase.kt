package com.safedrive.bus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 기기 내부 저장소. 외부 전송 경로는 만들지 않는다.
 */
@Database(
    entities = [TripEntity::class, EventEntity::class, SpeedZoneEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun eventDao(): EventDao
    abstract fun speedZoneDao(): SpeedZoneDao

    companion object {
        /**
         * 이벤트에 판정값과 속도정확도를 추가한다.
         * 기존 운행 이력을 지우지 않기 위해 컬럼만 덧붙인다.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE events ADD COLUMN judged_value REAL NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE events ADD COLUMN speed_accuracy_mps REAL")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "safedrive.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
