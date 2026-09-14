package com.safedrive.bus.data

import android.content.Context
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.EventType
import com.safedrive.bus.judge.DrivingEvent
import kotlinx.coroutines.flow.Flow

/** 운행 요약 1건. 리포트 화면과 CSV가 함께 쓴다. */
data class TripSummary(
    val trip: TripEntity,
    val counts: List<TypeCount>,
    /**
     * 진행 중인 운행의 실시간 주행거리 [m]. 끝난 운행이면 null.
     *
     * `distance_m`은 마감할 때만 기록되므로, 진행 중인 운행의 요약에서는 거리가 항상
     * 0으로 보였다. 100km 환산도 "주행거리가 1km 미만"이라며 사라졌다.
     * 실측에서 주행 화면은 21.8km인데 요약은 0.00km로 나왔다.
     */
    val liveDistanceM: Double? = null
) {
    val distanceKm: Double get() = (liveDistanceM ?: trip.distanceM) / 1000.0
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
        unmatchedLimitSamples: Long,
        limitSampleTotal: Long
    ) {
        val trip = tripDao.byId(tripId) ?: return

        // 이력만 확인하려고 앱을 열었다 나간 경우까지 운행으로 남으면 이력이 지저분해진다.
        // 사실상 움직이지 않았고 걸린 항목도 없으면 기록을 지운다.
        if (isTrivial(distanceM, endedAtMs - trip.startedAtMs, tripId)) {
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
                unmatchedLimitSamples = unmatchedLimitSamples,
                limitSampleTotal = limitSampleTotal
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
            meanKmhPerSec = e.meanKmhPerSec,
            horizPeakMps2 = e.horizontalPeakMps2,
            horizLpfPeakMps2 = e.horizontalLpfPeakMps2,
            signTrusted = e.forwardSignTrusted,
            signAgreement = e.forwardSignAgreement.takeUnless { it.isNaN() },
            speedAccuracyMps = e.speedAccuracyMps,
            turnAngleDeg = e.turnAngleDeg,
            turnDirection = e.turnDirection.name,
            thresholdValue = e.thresholdValue,
            speedLimitKmh = e.speedLimitKmh,
            roadName = e.roadName,
            matchDistanceM = e.matchDistanceM,
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

    /** 빈 운행으로 지워졌으면 null. */
    suspend fun tripById(tripId: Long): TripEntity? = tripDao.byId(tripId)

    suspend fun eventListOf(tripId: Long): List<EventEntity> = eventDao.listByTrip(tripId)

    /**
     * 남길 가치가 없는 운행인지.
     *
     * 거리만 보면 GPS 잡음으로 100~500m가 찍힌 짧은 실행이 살아남는다. 실제 이력에
     * `0.5 km · 1분 2초`, `0.3 km · 13분 30초` 같은 건이 그렇게 쌓였다.
     * 걸린 항목이 하나라도 있으면 짧아도 남긴다. 그게 이 앱의 존재 이유이기 때문이다.
     */
    private suspend fun isTrivial(distanceM: Double, durationMs: Long, tripId: Long): Boolean {
        if (eventDao.countForTrip(tripId) > 0) return false
        if (distanceM < TRIVIAL_TRIP_DISTANCE_M) return true
        return distanceM < SHORT_TRIP_DISTANCE_M && durationMs < SHORT_TRIP_DURATION_MS
    }

    /**
     * 종료되지 않은 채 남은 과거 운행을 마감한다. 서비스 시작 때 호출한다.
     *
     * 절전 정책이 프로세스를 죽이면 `stopCollection()`이 돌지 못해 운행이 열린 채 남고,
     * 빈 운행 삭제도 마감 시점에만 돌기 때문에 `0.0 km · 진행 중`이 영원히 쌓인다.
     * 이어받을 운행(`keepTripId`)만 남기고 나머지를 닫는다.
     *
     * 종료 시각은 마지막 이벤트 시각을 쓴다. 없으면 시작 시각으로 둔다.
     * 지금 시각을 쓰면 며칠 전 죽은 운행이 "5일 12시간"으로 표시된다.
     */
    suspend fun closeOrphanTrips(keepTripId: Long) {
        for (trip in tripDao.openTripsExcept(keepTripId)) {
            if (isTrivial(trip.distanceM, 0L, trip.id)) {
                tripDao.deleteById(trip.id)
                continue
            }
            val lastEvent = eventDao.lastOccurredAt(trip.id)
            tripDao.update(trip.copy(endedAtMs = lastEvent ?: trip.startedAtMs))
        }
    }

    /**
     * 운행 하나와 그 이벤트를 지운다.
     *
     * 진행 중인 운행은 서비스가 계속 쓰고 있으므로 지우지 않는다. 지우면 이후 판정이
     * 존재하지 않는 운행에 기록된다.
     */
    suspend fun deleteTrip(tripId: Long): Boolean {
        val trip = tripDao.byId(tripId) ?: return false
        if (trip.endedAtMs == null) return false
        eventDao.deleteForTrip(tripId)
        tripDao.deleteById(tripId)
        return true
    }

    /** 마감된 운행을 전부 지운다. 진행 중인 운행은 남는다. */
    suspend fun deleteFinishedTrips() {
        eventDao.deleteForFinishedTrips()
        tripDao.deleteFinished()
    }

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

        /** 이 거리·시간을 모두 밑돌고 걸린 항목도 없으면 실제 운행으로 보지 않는다. */
        const val SHORT_TRIP_DISTANCE_M = 800.0
        const val SHORT_TRIP_DURATION_MS = 3 * 60 * 1000L
    }
}
