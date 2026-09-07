package com.safedrive.bus.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 운행 1회. 서비스 시작 시 만들고 종료 시 마감한다. */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at") val endedAtMs: Long? = null,
    @ColumnInfo(name = "distance_m") val distanceM: Double = 0.0,
    /** 실제로 센서 데이터가 없었던 시간 */
    @ColumnInfo(name = "data_gap_ms") val dataGapMs: Long = 0,
    /** 데이터는 있었으나 전달이 멈춰 있던 시간 */
    @ColumnInfo(name = "stall_ms") val stallMs: Long = 0,
    @ColumnInfo(name = "gap_count") val gapCount: Int = 0,
    /** 제한속도 구간 매칭 실패로 과속 판정을 보류한 샘플 수 */
    @ColumnInfo(name = "unmatched_limit_samples") val unmatchedLimitSamples: Long = 0
)

/**
 * 판정 이벤트 1건.
 *
 * 게이트가 막혀 경고가 나가지 않은 건도 저장한다. `warned` 와 `suppress_reason` 으로 구분한다.
 * 임계값을 함께 저장해 두면 기준이 바뀌어도 과거 기록을 재해석할 수 있다.
 */
@Entity(
    tableName = "events",
    indices = [Index("trip_id"), Index("occurred_at")]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trip_id") val tripId: Long,
    @ColumnInfo(name = "occurred_at") val occurredAtMs: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "speed_kmh") val speedKmh: Float,
    /** 실제로 판정에 쓴 값. 가감속은 1초 창 속도 변화량, 회전은 누적각, 과속은 초과분. */
    @ColumnInfo(name = "judged_value", defaultValue = "0") val judgedValue: Float = 0f,
    @ColumnInfo(name = "peak_kmh_per_sec") val peakKmhPerSec: Float,
    @ColumnInfo(name = "speed_accuracy_mps") val speedAccuracyMps: Float? = null,
    @ColumnInfo(name = "turn_angle_deg") val turnAngleDeg: Float,
    @ColumnInfo(name = "turn_direction") val turnDirection: String,
    @ColumnInfo(name = "threshold_value") val thresholdValue: Float,
    @ColumnInfo(name = "speed_limit_kmh") val speedLimitKmh: Double?,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "gps_accuracy_m") val gpsAccuracyM: Float,
    @ColumnInfo(name = "gate_alignment") val gateAlignment: Boolean,
    @ColumnInfo(name = "gate_gps") val gateGps: Boolean,
    @ColumnInfo(name = "gate_continuity") val gateContinuity: Boolean,
    @ColumnInfo(name = "gate_mount") val gateMount: Boolean,
    @ColumnInfo(name = "warned") val warned: Boolean,
    @ColumnInfo(name = "suppress_reason") val suppressReason: String
)

/**
 * 사용자가 직접 등록하는 구간 제한속도.
 *
 * 원형 지오펜스(중심 좌표 + 반경)로 단순화했다. 노선 폴리라인을 받으려면
 * SpeedLimitProvider 구현만 교체하면 된다.
 */
@Entity(tableName = "speed_zones")
data class SpeedZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "radius_m") val radiusM: Double,
    @ColumnInfo(name = "limit_kmh") val limitKmh: Double
)
