package com.safedrive.bus.core

/**
 * 한국교통안전공단 위험운전행동 기준(버스) 상수 테이블.
 *
 * 이 값들은 첨부된 기준표에서 그대로 옮긴 것이며 임의로 조정하지 않는다.
 * 1단계에서는 판정 로직을 구현하지 않으므로 이 파일은 상수 정의만 담는다.
 *
 * 제외 항목(구현하지 않음): 급진로변경, 급앞지르기, 연속운전
 */
object DrivingStandards {

    /** 과속: 도로 제한속도 + 이 값을 초과 (km/h) */
    const val OVERSPEED_MARGIN_KMH: Double = 20.0

    /** 장기과속: 과속 상태가 이 시간 이상 지속 (ms) */
    const val LONG_OVERSPEED_DURATION_MS: Long = 3 * 60 * 1000L

    /**
     * 급가속: 속도 구간별 초당 가속 임계값 (km/h/s).
     * 6~10 km/h -> 8, 11~20 km/h -> 7, 21 km/h 초과 -> 6
     */
    data class SpeedBand(val minKmh: Double, val maxKmh: Double, val thresholdKmhPerSec: Double)

    val HARSH_ACCEL_BANDS: List<SpeedBand> = listOf(
        SpeedBand(6.0, 10.0, 8.0),
        SpeedBand(11.0, 20.0, 7.0),
        SpeedBand(21.0, Double.MAX_VALUE, 6.0)
    )

    /** 급출발: 이 속도 이하에서 출발 (km/h) */
    const val HARSH_START_MAX_INITIAL_KMH: Double = 5.0
    /** 급출발: 초당 가속 임계값 (km/h/s) */
    const val HARSH_START_THRESHOLD_KMH_PER_SEC: Double = 8.0

    /**
     * 급감속: 속도 구간별 초당 감속 임계값 (km/h/s).
     * 30 km/h 이하 -> 9, 50 km/h 이하 -> 10, 50 km/h 초과 -> 12
     */
    val HARSH_DECEL_BANDS: List<SpeedBand> = listOf(
        SpeedBand(0.0, 30.0, 9.0),
        SpeedBand(30.0, 50.0, 10.0),
        SpeedBand(50.0, Double.MAX_VALUE, 12.0)
    )

    /** 급감속 성립 최소 속도 (km/h) */
    const val HARSH_DECEL_MIN_SPEED_KMH: Double = 6.0

    /** 급정지: 초당 감속 임계값 (km/h/s) */
    const val HARSH_STOP_THRESHOLD_KMH_PER_SEC: Double = 9.0
    /** 급정지: 도달 속도 (km/h 이하) */
    const val HARSH_STOP_FINAL_SPEED_KMH: Double = 5.0

    /** 급좌우회전: 최소 속도 (km/h) */
    const val SHARP_TURN_MIN_SPEED_KMH: Double = 25.0
    /** 급좌우회전: 판정 창 (ms) */
    const val SHARP_TURN_WINDOW_MS: Long = 3000L
    /** 급좌우회전: 누적 회전각 범위 (deg) */
    const val SHARP_TURN_MIN_DEG: Double = 60.0
    const val SHARP_TURN_MAX_DEG: Double = 160.0

    /** 급U턴: 최소 속도 (km/h) */
    const val SHARP_UTURN_MIN_SPEED_KMH: Double = 20.0
    /** 급U턴: 판정 창 (ms) */
    const val SHARP_UTURN_WINDOW_MS: Long = 6000L
    /** 급U턴: 누적 회전각 범위 (deg) */
    const val SHARP_UTURN_MIN_DEG: Double = 160.0
    const val SHARP_UTURN_MAX_DEG: Double = 180.0

    /** 구간 제한속도 매칭 실패 시 사용할 기본값. 과속 판정에는 쓰지 않고 표시용으로만 쓴다. */
    const val DEFAULT_SPEED_LIMIT_KMH: Double = 50.0
}
