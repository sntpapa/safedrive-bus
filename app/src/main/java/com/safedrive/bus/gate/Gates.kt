package com.safedrive.bus.gate

import com.safedrive.bus.align.AlignmentSnapshot
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.EventType
import com.safedrive.bus.diag.GapSummary
import com.safedrive.bus.fusion.MotionSnapshot
import com.safedrive.bus.sensor.SensorHealth

enum class GateId { ALIGNMENT, GPS_QUALITY, SENSOR_CONTINUITY, MOUNT_STABILITY }

data class Gate(
    val id: GateId,
    val passed: Boolean,
    /** 사람이 읽는 현재 상태 한 줄. 디버그 화면에 그대로 표시한다. */
    val detail: String
)

/**
 * 4개 게이트의 단일 상태 객체.
 *
 * 경고 발생 여부는 앱이 포그라운드인지 백그라운드인지와 무관하다.
 * 오직 "지금 센서 데이터를 신뢰할 수 있는가"만 본다.
 * 하나라도 막히면 판정은 계속하되 경고를 내지 않는다(2단계에서 사용).
 */
data class GateSnapshot(
    val alignment: Gate,
    val gpsQuality: Gate,
    val sensorContinuity: Gate,
    val mountStability: Gate
) {
    val all: List<Gate> get() = listOf(alignment, gpsQuality, sensorContinuity, mountStability)

    /** 모든 게이트 통과. 회전 유형처럼 IMU 정렬이 필요한 판정의 경고 조건이다. */
    val warningAllowed: Boolean get() = all.all { it.passed }

    /** 속도 기반 판정(과속/장기과속)에 필요한 게이트만 본다. */
    val speedJudgementAllowed: Boolean get() = gpsQuality.passed && sensorContinuity.passed

    /**
     * 유형별로 실제 필요한 게이트만 본다.
     *
     * 가감속과 과속은 1초 창의 GPS 속도 변화량으로 판정하므로 좌표계 정렬이나 거치 상태와
     * 무관하다. 그런 유형까지 정렬을 기다리게 하면 보정이 막힌 동안 경고가 전혀 나가지
     * 않는다. 반면 회전은 요레이트를 쓰므로 4개 게이트가 모두 필요하다.
     */
    fun allowsWarningFor(type: EventType): Boolean = when (type) {
        EventType.SHARP_TURN, EventType.SHARP_UTURN -> warningAllowed
        else -> speedJudgementAllowed
    }

    /** 유형별로 무엇이 막고 있는지. 기록에 남길 사유 문자열. */
    fun blockedReasonFor(type: EventType): String {
        val needed = when (type) {
            EventType.SHARP_TURN, EventType.SHARP_UTURN -> all
            else -> listOf(gpsQuality, sensorContinuity)
        }
        return needed.filter { !it.passed }.joinToString(" · ") { it.detail }
    }

    val blockedReasons: List<String> get() = all.filter { !it.passed }.map { it.detail }

    companion object {
        val INITIAL = GateSnapshot(
            alignment = Gate(GateId.ALIGNMENT, false, "보정 대기"),
            gpsQuality = Gate(GateId.GPS_QUALITY, false, "GPS 대기"),
            sensorContinuity = Gate(GateId.SENSOR_CONTINUITY, false, "센서 대기"),
            mountStability = Gate(GateId.MOUNT_STABILITY, false, "거치 확인 대기")
        )
    }
}

object GateEvaluator {

    fun evaluate(
        alignment: AlignmentSnapshot,
        motion: MotionSnapshot,
        health: SensorHealth,
        gaps: GapSummary,
        serviceRunning: Boolean
    ): GateSnapshot {
        if (!serviceRunning) return GateSnapshot.INITIAL

        // 1) 좌표계 보정
        val alignGate = Gate(
            id = GateId.ALIGNMENT,
            passed = alignment.aligned,
            detail = when (alignment.state) {
                com.safedrive.bus.align.AlignmentState.ALIGNED -> "정렬 완료"
                com.safedrive.bus.align.AlignmentState.WAITING_STATIONARY -> "보정 중 · 정차 대기"
                com.safedrive.bus.align.AlignmentState.CAPTURING_GRAVITY ->
                    "보정 중 · 중력 %.0f%%".format(alignment.gravityProgress * 100)
                com.safedrive.bus.align.AlignmentState.COLLECTING_FORWARD ->
                    "보정 중 · 전방축 %d/%d 샘플, %d/%d 구간".format(
                        alignment.forwardSamples, Constants.FWD_MIN_SAMPLES,
                        alignment.forwardSegments, Constants.FWD_MIN_SEGMENTS
                    )
            }
        )

        // 2) GPS 품질
        val accOk = motion.gpsAccuracyM <= Constants.GPS_ACCURACY_MAX_M
        val ageOk = motion.gpsAgeMs <= Constants.GPS_MAX_AGE_MS
        val gpsGate = Gate(
            id = GateId.GPS_QUALITY,
            passed = accOk && ageOk,
            detail = when {
                motion.gpsAccuracyM == Float.MAX_VALUE -> "GPS 수신 없음"
                !ageOk -> "GPS 지연 %.1f초".format(motion.gpsAgeMs / 1000.0)
                !accOk -> "정확도 %.0fm (기준 %.0fm)".format(
                    motion.gpsAccuracyM, Constants.GPS_ACCURACY_MAX_M
                )
                else -> "정확도 %.0fm".format(motion.gpsAccuracyM)
            }
        )

        // 3) 센서 연속성: 실측 샘플링률 + 최근 끊김 + 복구 유예
        val rateOk = health.accelHz >= Constants.MIN_ACCEPTABLE_HZ &&
            health.gyroHz >= Constants.MIN_ACCEPTABLE_HZ
        val graceActive = gaps.graceActive
        val continuityGate = Gate(
            id = GateId.SENSOR_CONTINUITY,
            passed = rateOk && !gaps.inGap && !graceActive,
            detail = when {
                gaps.inGap -> "센서 끊김 진행 중"
                graceActive -> "끊김 복구 유예 중"
                !rateOk -> "샘플링률 저하 %.0f/%.0fHz (기준 %.0fHz)".format(
                    health.accelHz, health.gyroHz, Constants.MIN_ACCEPTABLE_HZ
                )
                else -> "%.0fHz / %.0fHz".format(health.accelHz, health.gyroHz)
            }
        )

        // 4) 거치 안정성
        val mountGate = Gate(
            id = GateId.MOUNT_STABILITY,
            passed = alignment.mountStable && alignment.aligned,
            detail = when {
                !alignment.mountStable ->
                    "재보정 필요 · " + alignment.lastInvalidationReason.ifEmpty { "거치 변화 감지" }
                !alignment.aligned -> "보정 전"
                else -> "편차 %.0f° / 변화율 %.0f°/s".format(
                    alignment.mountDeviationDeg, alignment.mountRateDps
                )
            }
        )

        return GateSnapshot(alignGate, gpsGate, continuityGate, mountGate)
    }
}
