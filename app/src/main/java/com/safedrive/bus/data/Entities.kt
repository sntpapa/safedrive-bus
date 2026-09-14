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
    /** 제한속도 구간 매칭 실패로 과속 판정을 보류한 프레임 수 */
    @ColumnInfo(name = "unmatched_limit_samples") val unmatchedLimitSamples: Long = 0,
    /**
     * 과속 판정을 시도한 전체 프레임 수.
     *
     * 보류 횟수만으로는 아무 판단을 할 수 없다. 프레임 카운터라 초당 30회 넘게
     * 오르고(실측: 6시간 37분 운행에 765,570), 기사도 개발자도 그 숫자의 크기를
     * 해석할 수 없었다. 전체 수와 함께 저장해 **비율**로 보여 준다.
     */
    @ColumnInfo(name = "limit_sample_total", defaultValue = "0") val limitSampleTotal: Long = 0
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
    /** 판정 창의 평균 IMU 가감속. 교차검증에 실제로 쓴 값. */
    @ColumnInfo(name = "mean_kmh_per_sec", defaultValue = "0") val meanKmhPerSec: Float = 0f,
    /** 판정 창의 수평 가속도 크기 피크. 정렬 없을 때의 대체 검증에 쓴 값. */
    @ColumnInfo(name = "horiz_peak_mps2", defaultValue = "0") val horizPeakMps2: Float = 0f,
    /** 판정 창의 저역통과 수평 가속도 크기 피크. 정렬 전 대체 검증에 실제로 쓴 값. */
    @ColumnInfo(name = "horiz_lpf_peak_mps2", defaultValue = "0") val horizLpfPeakMps2: Float = 0f,
    /** 판정 시점 전방축 부호 신뢰 여부. */
    @ColumnInfo(name = "sign_trusted", defaultValue = "0") val signTrusted: Boolean = false,
    /** 판정 시점 전방축 부호 일치율. 표본이 없으면 null. */
    @ColumnInfo(name = "sign_agreement") val signAgreement: Float? = null,
    @ColumnInfo(name = "speed_accuracy_mps") val speedAccuracyMps: Float? = null,
    @ColumnInfo(name = "turn_angle_deg") val turnAngleDeg: Float,
    @ColumnInfo(name = "turn_direction") val turnDirection: String,
    @ColumnInfo(name = "threshold_value") val thresholdValue: Float,
    @ColumnInfo(name = "speed_limit_kmh") val speedLimitKmh: Double?,
    /** 매칭된 링크의 도로명. 과속 오탐이 오매칭인지 가리는 단서다. */
    @ColumnInfo(name = "road_name") val roadName: String? = null,
    /** 매칭된 링크까지의 거리 [m]. */
    @ColumnInfo(name = "match_distance_m") val matchDistanceM: Double? = null,
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
