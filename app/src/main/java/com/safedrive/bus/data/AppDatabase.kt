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
    version = 4,
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

        /**
         * 교차검증 근거를 이벤트에 함께 남긴다.
         *
         * 2026-09-13 실측에서 "폴백이 왜 안 걸렸나", "과속이 오매칭인가"를 기록만으로는
         * 판단할 수 없었다. 판정에 실제로 쓴 값(평균 종가속도·수평 피크)과 매칭된
         * 도로를 저장한다. 제한속도 보류는 전체 대비 비율로 보여 주기 위해 분모를 남긴다.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE events ADD COLUMN mean_kmh_per_sec REAL NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE events ADD COLUMN horiz_peak_mps2 REAL NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE events ADD COLUMN road_name TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN match_distance_m REAL")
                db.execSQL(
                    "ALTER TABLE trips ADD COLUMN limit_sample_total INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 이벤트마다 어느 검증 경로를 탔는지 남긴다.
         *
         * 2026-09-14 실측에서 보정 완료 구간 경고가 왜 IMU 검증을 통과했는지(부호 신뢰가
         * 꺼져 있었는지) 기록만으로 확인할 수 없었다.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE events ADD COLUMN horiz_lpf_peak_mps2 REAL NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE events ADD COLUMN sign_trusted INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE events ADD COLUMN sign_agreement REAL")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "safedrive.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
