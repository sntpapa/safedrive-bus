package com.safedrive.bus.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 유형별 집계 결과. */
data class TypeCount(val type: String, val total: Int, val warned: Int)

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun byId(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE started_at >= :sinceMs ORDER BY started_at DESC")
    fun recent(sinceMs: Long): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY started_at DESC LIMIT 1")
    suspend fun latest(): TripEntity?

    @Query("DELETE FROM trips WHERE started_at < :beforeMs")
    suspend fun purgeBefore(beforeMs: Long)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 마감된 운행만 지운다. 진행 중인 운행은 서비스가 쓰고 있으므로 남긴다. */
    @Query("DELETE FROM trips WHERE ended_at IS NOT NULL")
    suspend fun deleteFinished()

    /**
     * 종료되지 않은 채 남은 운행.
     *
     * 제조사 절전 정책이 프로세스를 죽이면 마감 코드가 돌지 못해 `ended_at`이 비어 있고,
     * 이력에 "진행 중"으로 영원히 남는다.
     */
    @Query("SELECT * FROM trips WHERE ended_at IS NULL AND id != :keepId ORDER BY started_at")
    suspend fun openTripsExcept(keepId: Long): List<TripEntity>
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE trip_id = :tripId ORDER BY occurred_at DESC")
    fun byTrip(tripId: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE occurred_at >= :sinceMs ORDER BY occurred_at DESC")
    suspend fun since(sinceMs: Long): List<EventEntity>

    @Query(
        "SELECT type, COUNT(*) AS total, SUM(CASE WHEN warned THEN 1 ELSE 0 END) AS warned " +
            "FROM events WHERE trip_id = :tripId AND suppress_reason != 'ABSORBED_BY_UTURN' " +
            "GROUP BY type"
    )
    fun countsByTrip(tripId: Long): Flow<List<TypeCount>>

    @Query(
        "SELECT type, COUNT(*) AS total, SUM(CASE WHEN warned THEN 1 ELSE 0 END) AS warned " +
            "FROM events WHERE occurred_at >= :sinceMs AND suppress_reason != 'ABSORBED_BY_UTURN' " +
            "GROUP BY type"
    )
    fun countsSince(sinceMs: Long): Flow<List<TypeCount>>

    /**
     * 급U턴에 흡수된 급좌우회전 기록을 정정한다.
     * 경고는 이미 나갔으므로 warned는 건드리지 않고 집계에서만 제외되도록 사유를 바꾼다.
     */
    @Query(
        "UPDATE events SET suppress_reason = 'ABSORBED_BY_UTURN' " +
            "WHERE trip_id = :tripId AND occurred_at = :occurredAtMs AND type = 'SHARP_TURN'"
    )
    suspend fun markAbsorbed(tripId: Long, occurredAtMs: Long)

    @Query("SELECT COUNT(*) FROM events WHERE trip_id = :tripId")
    suspend fun countForTrip(tripId: Long): Int

    /** 마감되지 못한 운행의 종료 시각을 추정할 때 쓴다. */
    @Query("SELECT MAX(occurred_at) FROM events WHERE trip_id = :tripId")
    suspend fun lastOccurredAt(tripId: Long): Long?

    @Query("DELETE FROM events WHERE trip_id = :tripId")
    suspend fun deleteForTrip(tripId: Long)

    @Query(
        "DELETE FROM events WHERE trip_id IN " +
            "(SELECT id FROM trips WHERE ended_at IS NOT NULL)"
    )
    suspend fun deleteForFinishedTrips()

    @Query("DELETE FROM events WHERE occurred_at < :beforeMs")
    suspend fun purgeBefore(beforeMs: Long)
}

@Dao
interface SpeedZoneDao {
    @Insert
    suspend fun insert(zone: SpeedZoneEntity): Long

    @Update
    suspend fun update(zone: SpeedZoneEntity)

    @Delete
    suspend fun delete(zone: SpeedZoneEntity)

    @Query("SELECT * FROM speed_zones ORDER BY name")
    fun all(): Flow<List<SpeedZoneEntity>>

    @Query("SELECT * FROM speed_zones")
    suspend fun allOnce(): List<SpeedZoneEntity>
}
