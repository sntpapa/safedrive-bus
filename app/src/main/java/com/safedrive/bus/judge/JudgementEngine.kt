package com.safedrive.bus.judge

import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.DrivingStandards
import com.safedrive.bus.core.EventType
import com.safedrive.bus.core.SuppressReason
import com.safedrive.bus.core.TurnDirection
import com.safedrive.bus.gate.GateSnapshot
import kotlin.math.abs

/** 한 프레임의 판정 입력. 서비스가 조립해 넘긴다. */
data class JudgeInput(
    val timestampNs: Long,
    val wallMs: Long,
    /** 융합 속도 [km/h] */
    val speedKmh: Float,
    /** IMU 종방향 가감속 [km/h/s]. 피크 기록과 보조 확인용. */
    val longKmhPerSec: Float,
    /** 차량 수직 가속도 [m/s^2]. 노면 충격 판단용. */
    val verticalMps2: Float,
    /** 요레이트 [deg/s]. + = 좌회전 */
    val yawRateDps: Float,
    val latitude: Double,
    val longitude: Double,
    val gpsAccuracyM: Float,
    /** GPS 속도정확도 [m/s]. 저속 구간 오탐을 가려내기 위해 이벤트에 함께 남긴다. */
    val speedAccuracyMps: Float?,
    /** 현재 위치의 구간 제한속도. 매칭 실패 시 null. */
    val speedLimitKmh: Double?,
    val gates: GateSnapshot,
    /** 회전벡터 기반 중력 제거를 쓰고 있는지. false면 경사로 보정 신뢰 불가. */
    val pitchReliable: Boolean,
    /**
     * 차량 좌표계 정렬이 끝났는지.
     *
     * 회전 판정은 요레이트가 필요하므로 정렬이 있어야 한다.
     * 반면 가감속·과속 판정은 1초 창의 GPS 속도 변화량으로 하므로 정렬과 무관하다.
     */
    val vehicleFrameReady: Boolean
)

/** 판정기가 밖으로 알리는 부수 정보. */
data class JudgeStats(
    /** 제한속도 구간 매칭에 실패해 과속 판정을 보류한 횟수 */
    val unmatchedLimitSamples: Long = 0L,
    /** 현재 과속 상태인지 */
    val overspeedActive: Boolean = false,
    /** 과속 상태 지속 시간 [ms] */
    val overspeedDurationMs: Long = 0L
)

/**
 * 8개 유형 판정기.
 *
 * 판정 신호 선택
 * -------------
 * 기준표가 "초당 N km/h"로 정의돼 있으므로 **1초 창의 속도 변화량**을 1차 신호로 쓴다.
 * IMU 종가속도의 순간 피크는 1초 평균보다 훨씬 크게 나오기 때문에(요철 하나에 10 km/h/s 이상)
 * 그것을 임계값에 직접 대면 과탐이 심해진다. IMU 값은 피크 기록과 경계값 보류 판단에만 쓴다.
 *
 * 기준표 해석 (판단이 갈릴 수 있는 부분을 명시한다)
 * -----------------------------------------------
 *  - 급가속 속도 구간(6~10 / 11~20 / 21초과)에 10~11, 20~21 구멍이 있다.
 *    연속 처리를 위해 (6~10], (10~20], (20~) 로 확장 해석했다.
 *  - 속도 구간은 **판정 창 시작 시점 속도**로 고른다.
 *  - 급감속의 "속도가 6.0km/h 이상" 조건은 **판정 시점(창 끝) 속도**로 본다.
 *    이 해석에 따라 급감속과 급정지는 서로 배타적이 된다.
 *  - 회전 유형의 속도 조건은 **창 진입 시점 속도**로 본다.
 *
 * 게이트 처리
 * ----------
 *  - 게이트가 막혀도 판정은 계속하고 이벤트를 기록한다. 경고만 내보내지 않는다.
 *  - 게이트는 유형별로 필요한 것만 본다. 가감속·과속은 GPS 품질과 센서 연속성,
 *    회전은 4개 전부. 판정 신호가 쓰지 않는 게이트까지 요구하면 경고가 부당하게 막힌다.
 *  - 예외: GPS 품질 게이트가 막히면 과속/장기과속은 판정 자체를 보류한다(기준표 지시).
 */
class JudgementEngine(
    private val onEvent: (DrivingEvent) -> Unit,
    /** 급U턴에 흡수된 직전 급좌우회전 기록을 정정하기 위한 콜백. 인자는 그 이벤트의 wallMs. */
    private val onAbsorbedByUturn: (Long) -> Unit
) {

    private val history = MotionHistory()
    /**
     * 디바운스는 유형이 아니라 계열 단위로 건다.
     *
     * 정류소 진입 제동 한 번에서 급감속과 급정지가 따로 잡혀 한 사건이 2~3건으로
     * 세지고 있었다(실측: 3초 사이에 급감속-급감속-급정지).
     */
    private val lastFiredWallMs = HashMap<String, Long>()

    private var unmatchedLimitSamples = 0L
    private var overspeedSinceWallMs = 0L
    private var overspeedReported = false
    private var longOverspeedReported = false
    private var overspeedClearSinceWallMs = 0L

    private var lastSharpTurnWallMs = 0L
    private var lastSharpTurnDirection = TurnDirection.NONE

    /** 직전 종방향 이벤트의 시각과 방향(+가속 / -감속). 상호 배제 판단에 쓴다. */
    private var lastLongitudinalWallMs = 0L
    private var lastLongitudinalSign = 0

    fun reset() {
        history.clear()
        lastFiredWallMs.clear()
        unmatchedLimitSamples = 0L
        overspeedSinceWallMs = 0L
        overspeedReported = false
        longOverspeedReported = false
        overspeedClearSinceWallMs = 0L
        lastSharpTurnWallMs = 0L
        lastSharpTurnDirection = TurnDirection.NONE
        lastLongitudinalWallMs = 0L
        lastLongitudinalSign = 0
    }

    fun stats(): JudgeStats = JudgeStats(
        unmatchedLimitSamples = unmatchedLimitSamples,
        overspeedActive = overspeedSinceWallMs != 0L,
        overspeedDurationMs = if (overspeedSinceWallMs == 0L) 0L
        else System.currentTimeMillis() - overspeedSinceWallMs
    )

    fun process(i: JudgeInput) {
        history.add(
            ts = i.timestampNs,
            speedKmh = i.speedKmh,
            longKmhPerSec = i.longKmhPerSec,
            verticalMps2 = i.verticalMps2,
            yawRateDps = i.yawRateDps
        )
        // 가감속과 과속은 GPS 속도만으로 판정하므로 좌표계 정렬을 기다리지 않는다.
        judgeLongitudinal(i)
        judgeSpeedLimit(i)
        // 회전은 요레이트가 필요하다. 정렬이 끝나야 차량 기준 회전각을 알 수 있다.
        if (i.vehicleFrameReady) judgeTurn(i)
    }

    // ------------------------------------------------------------------
    // 급가속 / 급출발 / 급감속 / 급정지
    // ------------------------------------------------------------------

    private fun judgeLongitudinal(i: JudgeInput) {
        if (!history.covers(Constants.JUDGE_WINDOW_MS)) return
        val from = i.timestampNs - Constants.JUDGE_WINDOW_MS * 1_000_000L
        val v0 = history.speedAt(from)
        val v1 = i.speedKmh

        // 정차 필터. 정류소 정지/신호 대기 중 남은 GNSS 잡음으로 가짜 이벤트를 만들지 않는다.
        if (maxOf(v0, v1) < Constants.JUDGE_IDLE_FLOOR_KMH) return

        val dv = v1 - v0 // 1초 창이므로 km/h/s 와 같은 값

        if (dv > 0f) {
            if (v0 <= DrivingStandards.HARSH_START_MAX_INITIAL_KMH) {
                val th = DrivingStandards.HARSH_START_THRESHOLD_KMH_PER_SEC.toFloat()
                if (dv >= th) fire(EventType.HARSH_START, i, from, dv, th)
            } else {
                accelThreshold(v0)?.let { th ->
                    if (dv >= th) fire(EventType.HARSH_ACCEL, i, from, dv, th)
                }
            }
        } else {
            val decel = -dv
            if (v1 <= DrivingStandards.HARSH_STOP_FINAL_SPEED_KMH) {
                val th = DrivingStandards.HARSH_STOP_THRESHOLD_KMH_PER_SEC.toFloat()
                if (decel >= th) fire(EventType.HARSH_STOP, i, from, dv, th)
            } else if (v1 >= DrivingStandards.HARSH_DECEL_MIN_SPEED_KMH) {
                val th = decelThreshold(v0)
                if (decel >= th) fire(EventType.HARSH_DECEL, i, from, dv, th)
            }
        }
    }

    /** 같은 사건으로 볼 유형 묶음. 디바운스는 이 단위로 건다. */
    private fun debounceGroup(type: EventType): String = when (type) {
        EventType.HARSH_DECEL, EventType.HARSH_STOP -> "DECEL"
        EventType.HARSH_ACCEL, EventType.HARSH_START -> "ACCEL"
        EventType.SHARP_TURN, EventType.SHARP_UTURN -> "TURN"
        else -> type.name
    }

    /** 종방향 이벤트의 방향. 가속 +1, 감속 -1, 그 외 0. */
    private fun longitudinalSign(type: EventType): Int = when (type) {
        EventType.HARSH_ACCEL, EventType.HARSH_START -> 1
        EventType.HARSH_DECEL, EventType.HARSH_STOP -> -1
        else -> 0
    }

    /** 급가속 속도대역별 임계값. 6km/h 미만은 급출발 영역이라 null. */
    private fun accelThreshold(startKmh: Float): Float? = when {
        startKmh < 6.0f -> null
        startKmh <= 10.0f -> 8.0f
        startKmh <= 20.0f -> 7.0f
        else -> 6.0f
    }

    private fun decelThreshold(startKmh: Float): Float = when {
        startKmh <= 30.0f -> 9.0f
        startKmh <= 50.0f -> 10.0f
        else -> 12.0f
    }

    // ------------------------------------------------------------------
    // 급좌우회전 / 급U턴
    // ------------------------------------------------------------------

    private fun judgeTurn(i: JudgeInput) {
        val now = i.timestampNs

        // 급U턴을 먼저 본다. 성립하면 직전 급좌우회전을 흡수한다.
        if (history.covers(Constants.TURN_WINDOW_LONG_MS)) {
            val from = now - Constants.TURN_WINDOW_LONG_MS * 1_000_000L
            val signed = history.yawIntegralDeg(from, now)
            val angle = abs(signed)
            val entry = history.speedAt(from)
            if (angle >= DrivingStandards.SHARP_UTURN_MIN_DEG &&
                angle <= DrivingStandards.SHARP_UTURN_MAX_DEG &&
                entry >= DrivingStandards.SHARP_UTURN_MIN_SPEED_KMH
            ) {
                val dir = if (signed >= 0f) TurnDirection.LEFT else TurnDirection.RIGHT
                val fired = fire(
                    type = EventType.SHARP_UTURN,
                    i = i,
                    windowFromNs = from,
                    magnitude = signed,
                    threshold = DrivingStandards.SHARP_UTURN_MIN_DEG.toFloat(),
                    turnAngle = signed,
                    turnDirection = dir,
                    applyBorderline = false
                )
                if (fired && lastSharpTurnWallMs != 0L &&
                    i.wallMs - lastSharpTurnWallMs <= Constants.TURN_WINDOW_LONG_MS &&
                    lastSharpTurnDirection == dir
                ) {
                    // 같은 U턴을 급좌우회전으로도 한 번 세면 이중 계상이 된다.
                    // 이미 기록된 급좌우회전을 "U턴에 포함됨"으로 정정한다.
                    onAbsorbedByUturn(lastSharpTurnWallMs)
                    lastSharpTurnWallMs = 0L
                }
                return
            }
        }

        if (history.covers(Constants.TURN_WINDOW_SHORT_MS)) {
            val from = now - Constants.TURN_WINDOW_SHORT_MS * 1_000_000L
            val signed = history.yawIntegralDeg(from, now)
            val angle = abs(signed)
            val entry = history.speedAt(from)
            if (angle >= DrivingStandards.SHARP_TURN_MIN_DEG &&
                angle < DrivingStandards.SHARP_TURN_MAX_DEG &&
                entry >= DrivingStandards.SHARP_TURN_MIN_SPEED_KMH
            ) {
                val dir = if (signed >= 0f) TurnDirection.LEFT else TurnDirection.RIGHT
                val fired = fire(
                    type = EventType.SHARP_TURN,
                    i = i,
                    windowFromNs = from,
                    magnitude = signed,
                    threshold = DrivingStandards.SHARP_TURN_MIN_DEG.toFloat(),
                    turnAngle = signed,
                    turnDirection = dir,
                    applyBorderline = false
                )
                if (fired) {
                    lastSharpTurnWallMs = i.wallMs
                    lastSharpTurnDirection = dir
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 과속 / 장기과속
    // ------------------------------------------------------------------

    private fun judgeSpeedLimit(i: JudgeInput) {
        // 기준표 지시: GPS 품질이 임계값을 벗어나면 속도 기반 판정을 중단한다.
        if (!i.gates.gpsQuality.passed) {
            clearOverspeed()
            return
        }
        val limit = i.speedLimitKmh
        if (limit == null) {
            // 구간 매칭 실패. 추정으로 경고하지 않는다. 보류 횟수만 남긴다.
            unmatchedLimitSamples++
            clearOverspeed()
            return
        }

        val trigger = limit + DrivingStandards.OVERSPEED_MARGIN_KMH
        if (i.speedKmh > trigger) {
            overspeedClearSinceWallMs = 0L
            if (overspeedSinceWallMs == 0L) overspeedSinceWallMs = i.wallMs
            if (!overspeedReported) {
                overspeedReported = true
                fire(
                    type = EventType.OVERSPEED,
                    i = i,
                    windowFromNs = i.timestampNs,
                    magnitude = i.speedKmh - limit.toFloat(),
                    threshold = DrivingStandards.OVERSPEED_MARGIN_KMH.toFloat(),
                    applyBorderline = false
                )
            }
            if (!longOverspeedReported &&
                i.wallMs - overspeedSinceWallMs >= DrivingStandards.LONG_OVERSPEED_DURATION_MS
            ) {
                longOverspeedReported = true
                fire(
                    type = EventType.LONG_OVERSPEED,
                    i = i,
                    windowFromNs = i.timestampNs,
                    magnitude = i.speedKmh - limit.toFloat(),
                    threshold = DrivingStandards.OVERSPEED_MARGIN_KMH.toFloat(),
                    applyBorderline = false
                )
            }
        } else if (i.speedKmh <= trigger - Constants.OVERSPEED_CLEAR_MARGIN_KMH) {
            // 히스테리시스. 임계값 근처에서 상태가 떨리며 이벤트가 반복되는 것을 막는다.
            if (overspeedClearSinceWallMs == 0L) {
                overspeedClearSinceWallMs = i.wallMs
            } else if (i.wallMs - overspeedClearSinceWallMs >= Constants.OVERSPEED_CLEAR_MS) {
                clearOverspeed()
            }
        }
    }

    private fun clearOverspeed() {
        overspeedSinceWallMs = 0L
        overspeedReported = false
        longOverspeedReported = false
        overspeedClearSinceWallMs = 0L
    }

    // ------------------------------------------------------------------

    /** @return 이벤트를 기록했으면 true, 디바운스로 버렸으면 false */
    private fun fire(
        type: EventType,
        i: JudgeInput,
        windowFromNs: Long,
        magnitude: Float,
        threshold: Float,
        turnAngle: Float = 0f,
        turnDirection: TurnDirection = TurnDirection.NONE,
        applyBorderline: Boolean = true
    ): Boolean {
        val group = debounceGroup(type)
        val last = lastFiredWallMs[group] ?: 0L
        if (last != 0L && i.wallMs - last < Constants.WARNING_DEBOUNCE_MS) return false
        lastFiredWallMs[group] = i.wallMs

        val excessRatio = if (threshold <= 0f) 1f else (abs(magnitude) - threshold) / threshold
        val borderline = applyBorderline && excessRatio < Constants.BORDERLINE_MARGIN_RATIO
        val shock = applyBorderline &&
            history.peakVerticalAbs(windowFromNs, i.timestampNs) > Constants.VERTICAL_SHOCK_MPS2

        // 가속 계열과 감속 계열이 연달아 나오면 물리적으로 불가능한 조합이다.
        val sign = longitudinalSign(type)
        val conflicting = sign != 0 && lastLongitudinalSign != 0 &&
            sign != lastLongitudinalSign &&
            i.wallMs - lastLongitudinalWallMs < Constants.CONFLICT_WINDOW_MS

        // 차량이 낼 수 없는 값이면 판정이 아니라 측정 오류다.
        val implausible = sign != 0 && when {
            sign > 0 -> magnitude > Constants.PLAUSIBLE_MAX_ACCEL_KMH_PER_SEC
            else -> -magnitude > Constants.PLAUSIBLE_MAX_DECEL_KMH_PER_SEC
        }

        // GPS 속도 잡음보다 충분히 크지 않으면 판정값을 신뢰할 수 없다.
        val sigma = i.speedAccuracyMps?.takeIf { it > 0f }?.let { it * 1.4142f * 3.6f }
        val lowSnr = sign != 0 && sigma != null &&
            abs(magnitude) < sigma * Constants.MIN_JUDGE_SNR

        val reason = when {
            implausible -> SuppressReason.IMPLAUSIBLE
            lowSnr -> SuppressReason.LOW_SNR
            conflicting -> SuppressReason.CONFLICTING_DIRECTION
            borderline && !i.pitchReliable -> SuppressReason.BORDERLINE_PITCH
            borderline && shock -> SuppressReason.BORDERLINE_SHOCK
            !i.gates.allowsWarningFor(type) -> SuppressReason.GATE_BLOCKED
            else -> SuppressReason.NONE
        }

        if (sign != 0) {
            lastLongitudinalWallMs = i.wallMs
            lastLongitudinalSign = sign
        }

        onEvent(
            DrivingEvent(
                type = type,
                wallMs = i.wallMs,
                speedKmh = i.speedKmh,
                judgedValue = magnitude,
                peakKmhPerSec = history.peakLongitudinal(windowFromNs, i.timestampNs),
                speedAccuracyMps = i.speedAccuracyMps,
                turnAngleDeg = turnAngle,
                turnDirection = turnDirection,
                thresholdValue = threshold,
                speedLimitKmh = i.speedLimitKmh,
                latitude = i.latitude,
                longitude = i.longitude,
                gpsAccuracyM = i.gpsAccuracyM,
                gateAlignment = i.gates.alignment.passed,
                gateGps = i.gates.gpsQuality.passed,
                gateContinuity = i.gates.sensorContinuity.passed,
                gateMount = i.gates.mountStability.passed,
                warned = reason == SuppressReason.NONE,
                suppressReason = reason
            )
        )
        return true
    }
}
