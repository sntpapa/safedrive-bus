package com.safedrive.bus.data

import android.content.Context
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.EventType
import com.safedrive.bus.judge.DrivingEvent
import kotlinx.coroutines.flow.Flow

/** 운행 요약 1건. 리포트 화면과 CSV가 함께 쓴다. */
data class TripSummary(
    val trip: TripEntity,
    val counts: List<TypeCount>
) {
    val distanceKm: Double get() = trip.distanceM / 1000.0
    val durationMs: Long
        get() = (trip.endedAtMs ?: System.currentTimeMillis()) - trip.startedAtMs

    fun countOf(type: EventType): Int = counts.firstOrNull { it.type == type.name }?.total ?: 0
    fun warnedOf(type: EventType): Int = counts.firstOrNull { it.type == type.name }?.warned ?: 0

    /** 100km당 환산 건수. 주행거리가 너무 짧으면 의미가 없으므로 null을 준다. */
    fun per100km(type: EventType): Double? {
        if (distanceKm < MIN_DISTANCE_FOR_RATE_KM) return null
        return countOf(type) * 100.0 / distanceKm
    }

    val totalEvents: Int get() = counts.sumOf { it.total }
    val totalWarned: Int get() = counts.sumOf { it.warned }

    private companion object {
        /** 이 거리 미만에서는 100km 환산이 과장되므로 표시하지 않는다. */
        const val MIN_DISTANCE_FOR_RATE_KM = 1.0
    }
}

class TripRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val tripDao = db.tripDao()
    private val eventDao = db.eventDao()
    val speedZoneDao: SpeedZoneDao = db.speedZoneDao()

    suspend fun startTrip(startedAtMs: Long): Long =
        tripDao.insert(TripEntity(startedAtMs = startedAtMs))

    suspend fun finishTrip(
        tripId: Long,
        endedAtMs: Long,
        distanceM: Double,
        dataGapMs: Long,
        stallMs: Long,
        gapCount: Int,
        unmatchedLimitSamples: Long
    ) {
        val trip = tripDao.byId(tripId) ?: return

        // 이력만 확인하려고 앱을 열었다 나간 경우까지 운행으로 남으면 이력이 지저분해진다.
        // 사실상 움직이지 않았고 걸린 항목도 없으면 기록을 지운다.
        if (distanceM < TRIVIAL_TRIP_DISTANCE_M && eventDao.countForTrip(tripId) == 0) {
            tripDao.deleteById(tripId)
            return
        }

        tripDao.update(
            trip.copy(
                endedAtMs = endedAtMs,
                distanceM = distanceM,
                dataGapMs = dataGapMs,
                stallMs = stallMs,
                gapCount = gapCount,
                unmatchedLimitSamples = unmatchedLimitSamples
            )
        )
    }

    suspend fun record(tripId: Long, e: DrivingEvent): Long = eventDao.insert(
        EventEntity(
            tripId = tripId,
            occurredAtMs = e.wallMs,
            type = e.type.name,
            speedKmh = e.speedKmh,
            judgedValue = e.judgedValue,
            peakKmhPerSec = e.peakKmhPerSec,
            speedAccuracyMps = e.speedAccuracyMps,
            turnAngleDeg = e.turnAngleDeg,
            turnDirection = e.turnDirection.name,
            thresholdValue = e.thresholdValue,
            speedLimitKmh = e.speedLimitKmh,
            latitude = e.latitude,
            longitude = e.longitude,
            gpsAccuracyM = e.gpsAccuracyM,
            gateAlignment = e.gateAlignment,
            gateGps = e.gateGps,
            gateContinuity = e.gateContinuity,
            gateMount = e.gateMount,
            warned = e.warned,
            suppressReason = e.suppressReason.name
        )
    )

    suspend fun markAbsorbedByUturn(tripId: Long, occurredAtMs: Long) =
        eventDao.markAbsorbed(tripId, occurredAtMs)

    fun recentTrips(): Flow<List<TripEntity>> =
        tripDao.recent(System.currentTimeMillis() - historyWindowMs())

    fun eventsOf(tripId: Long): Flow<List<EventEntity>> = eventDao.byTrip(tripId)

    fun countsOf(tripId: Long): Flow<List<TypeCount>> = eventDao.countsByTrip(tripId)

    fun countsRecent(): Flow<List<TypeCount>> =
        eventDao.countsSince(System.currentTimeMillis() - historyWindowMs())

    suspend fun latestTrip(): TripEntity? = tripDao.latest()

    /** 아직 마감되지 않은 운행인지. 서비스 재시작 시 이어받을 대상을 고른다. */
    suspend fun isTripOpen(tripId: Long): Boolean =
        tripDao.byId(tripId)?.endedAtMs == null

    suspend fun eventsSince(sinceMs: Long): List<EventEntity> = eventDao.since(sinceMs)

    /** 보관 기간이 지난 기록을 지운다. 서비스 시작 때마다 호출한다. */
    suspend fun purgeOld() {
        val cutoff = System.currentTimeMillis() - historyWindowMs()
        eventDao.purgeBefore(cutoff)
        tripDao.purgeBefore(cutoff)
    }

    private fun historyWindowMs(): Long = Constants.HISTORY_DAYS * 24L * 60 * 60 * 1000

    private companion object {
        /** 이 거리 미만이면 실제 운행으로 보지 않는다. */
        const val TRIVIAL_TRIP_DISTANCE_M = 100.0
    }
}
