package com.safedrive.bus.judge

import com.safedrive.bus.core.EventType
import com.safedrive.bus.core.SuppressReason
import com.safedrive.bus.core.TurnDirection

/**
 * 판정 결과 1건.
 *
 * 게이트가 막혀 경고가 나가지 않은 경우에도 이 객체는 만들어져 저장된다.
 * `warned = false` 와 `suppressReason` 으로 구분한다. 한 번의 운행에서
 * "무엇이 몇 번 걸렸고 무엇이 왜 막혔는지"를 모두 남기기 위한 설계다.
 */
data class DrivingEvent(
    val type: EventType,
    val wallMs: Long,
    /** 발생 시점 속도 [km/h] */
    val speedKmh: Float,
    /** 판정 창의 최대 가감속 [km/h/s]. 가속 +, 감속 -. */
    val peakKmhPerSec: Float,
    /** 회전 유형일 때 누적 회전각(도). 그 외 0. */
    val turnAngleDeg: Float,
    val turnDirection: TurnDirection,
    /**
     * 적용된 임계값. 기준이 바뀌었을 때 과거 기록을 재해석할 수 있도록 함께 저장한다.
     * 단위는 유형에 따라 다르다. 가감속 유형은 km/h/s, 회전 유형은 도, 과속 유형은 km/h.
     */
    val thresholdValue: Float,
    val speedLimitKmh: Double?,
    val latitude: Double,
    val longitude: Double,
    val gpsAccuracyM: Float,
    /** 발생 시점 4개 게이트 통과 여부 */
    val gateAlignment: Boolean,
    val gateGps: Boolean,
    val gateContinuity: Boolean,
    val gateMount: Boolean,
    /** 실제로 경고(TTS/진동)를 내보냈는지 */
    val warned: Boolean,
    val suppressReason: SuppressReason
) {
    val gatesAllPassed: Boolean
        get() = gateAlignment && gateGps && gateContinuity && gateMount
}
