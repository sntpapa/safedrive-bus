package com.safedrive.bus.align

import android.os.SystemClock
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.Vec3
import com.safedrive.bus.core.angleBetweenDeg
import com.safedrive.bus.filter.LowPass1P
import com.safedrive.bus.sensor.SensorFrame
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

enum class AlignmentState {
    /** 정차 구간을 기다리는 중. 중력 방향을 아직 확정하지 못했다. */
    WAITING_STATIONARY,

    /** 정차 중 가속도를 누적해 중력(=차량 상하축)을 확정하는 중. */
    CAPTURING_GRAVITY,

    /** 직진 가감속 구간을 모아 전방 축을 추정하는 중. */
    COLLECTING_FORWARD,

    /** 정렬 완료. 센서값을 차량 좌표계로 변환할 수 있다. */
    ALIGNED
}

data class AlignmentSnapshot(
    val state: AlignmentState = AlignmentState.WAITING_STATIONARY,
    /** 중력 확정 진행률 0..1 */
    val gravityProgress: Float = 0f,
    /** 전방 축 진행률 0..1 (샘플/구간/품질 중 가장 뒤처진 항목 기준) */
    val forwardProgress: Float = 0f,
    val forwardSamples: Int = 0,
    val forwardSegments: Int = 0,
    val eigenRatio: Double = 0.0,
    val angleStdDeg: Double = Double.NaN,
    /** 보정 시점 대비 현재 중력 방향 편차(도) */
    val mountDeviationDeg: Float = 0f,
    /** 중력 방향 변화율(도/초). 거치 이탈 1차 지표. */
    val mountRateDps: Float = 0f,
    val mountStable: Boolean = true,
    /** 마지막으로 정렬이 깨진 이유. UI 표시용. */
    val lastInvalidationReason: String = "",
    val invalidationCount: Int = 0
) {
    val aligned: Boolean get() = state == AlignmentState.ALIGNED
    /** 전체 진행률 0..1 (중력 40% + 전방 60% 가중) */
    val overallProgress: Float
        get() = if (aligned) 1f else (gravityProgress * 0.4f + forwardProgress * 0.6f).coerceIn(0f, 1f)
}

/** 차량 좌표계로 변환된 한 프레임의 운동량. */
data class VehicleMotion(
    /** 종방향 가속도 [m/s^2]. + = 가속, - = 감속 */
    val longitudinal: Float,
    /** 횡방향 가속도 [m/s^2]. + = 좌측 */
    val lateral: Float,
    /** 수직 가속도 [m/s^2]. + = 위 */
    val vertical: Float,
    /** 요레이트 [deg/s]. + = 좌회전 */
    val yawRateDps: Float
)

/**
 * 휴대폰 좌표계 -> 차량 좌표계 정렬.
 *
 * 1) 아래 축: 정차 구간의 가속도 평균으로 중력 방향을 확정한다.
 *    가속도계는 정지 시 "위쪽"으로 +g를 읽으므로 평균 벡터 자체가 차량 상하축이 된다.
 *    이 축은 폰이 거치대에 강결합되어 있는 한 도로 경사와 무관하게 일정하다.
 *
 * 2) 전방 축: 직진 가감속 구간에서 수평면에 투영한 선형가속도를 모으고,
 *    GPS 속도 증감 부호로 방향을 정렬한 뒤 주성분(PCA)을 전방 축으로 삼는다.
 *    단위벡터의 산포행렬을 쓰기 때문에 큰 가속 한 번이 결과를 지배하지 않는다.
 *
 * 3) 거치 이탈 감지: 절대 각도만 쓰면 도로 경사(도심 최대 약 7도)와 제동 시 노즈다이브를
 *    이탈로 오인한다. 중력 방향 "변화율"을 1차 지표로, 절대 편차를 2차 지표로 쓴다.
 */
class FrameAligner {

    private var state = AlignmentState.WAITING_STATIONARY

    // --- 중력 확정 ---
    private var gravitySum = Vec3.ZERO
    private var gravityMagSum = 0.0
    private var gravityMagSqSum = 0.0
    private var gravityCount = 0
    private var gravityStartElapsedMs = 0L

    // --- 축 ---
    private var vehicleUp: Vec3? = null
    private var vehicleForward: Vec3? = null
    private var vehicleLeft: Vec3? = null
    private var basisE1: Vec3? = null
    private var basisE2: Vec3? = null

    // --- 전방 축 추정 누적 (단위벡터 기반, O(1) 메모리) ---
    private var sxx = 0.0
    private var sxy = 0.0
    private var syy = 0.0
    private var sumUx = 0.0
    private var sumUy = 0.0
    private var fwdCount = 0
    private var fwdSegments = 0
    private var lastFwdSampleElapsedMs = 0L
    private var eigenRatio = 0.0
    private var angleStdDeg = Double.NaN

    // --- 거치 안정성 ---
    private var prevGravityUnit: Vec3? = null
    private val mountRateLpf = LowPass1P(2.0)
    private var rateExceedSinceMs = 0L
    private var deviationExceedSinceMs = 0L
    private var mountDeviationDeg = 0f
    private var mountStable = true

    /**
     * 이탈 판정 후 이 시각까지는 거치 안정성 게이트를 계속 막는다.
     * 한 프레임만 막으면 UI가 깜빡일 뿐 "재보정 상태"라는 사실이 드러나지 않는다.
     */
    private var mountUnstableUntilMs = 0L

    private var invalidationReason = ""
    private var invalidationCount = 0

    fun reset() {
        state = AlignmentState.WAITING_STATIONARY
        clearGravityAccumulator()
        clearForwardAccumulator()
        vehicleUp = null; vehicleForward = null; vehicleLeft = null
        basisE1 = null; basisE2 = null
        prevGravityUnit = null
        mountRateLpf.reset()
        rateExceedSinceMs = 0L
        deviationExceedSinceMs = 0L
        mountDeviationDeg = 0f
        mountStable = true
    }

    private fun mountHeldUnstable(nowMs: Long) = nowMs < mountUnstableUntilMs

    /**
     * 프레임 1개 처리.
     *
     * @param gpsSpeedMps 최근 GPS 속도. 없으면 null.
     * @param gpsAccelMps2 최근 GPS 속도 변화율. 없으면 null.
     * @param gpsFresh 위치가 최신인지(오래된 값으로 정렬을 학습하지 않기 위함)
     * @return 정렬이 끝난 경우 차량 좌표계 운동량, 아니면 null
     */
    fun process(
        frame: SensorFrame,
        gpsSpeedMps: Float?,
        gpsAccelMps2: Float?,
        gpsFresh: Boolean
    ): VehicleMotion? {
        val nowMs = SystemClock.elapsedRealtime()

        updateMountStability(frame, nowMs)
        if (!mountStable || mountHeldUnstable(nowMs)) return null

        when (state) {
            AlignmentState.WAITING_STATIONARY,
            AlignmentState.CAPTURING_GRAVITY -> {
                accumulateGravity(frame, gpsSpeedMps, gpsFresh, nowMs)
                return null
            }

            AlignmentState.COLLECTING_FORWARD -> {
                accumulateForward(frame, gpsAccelMps2, gpsFresh, nowMs)
                return null
            }

            AlignmentState.ALIGNED -> return toVehicleMotion(frame)
        }
    }

    fun snapshot(): AlignmentSnapshot {
        val gravityProgress = when (state) {
            AlignmentState.WAITING_STATIONARY -> 0f
            AlignmentState.CAPTURING_GRAVITY -> {
                val elapsed = SystemClock.elapsedRealtime() - gravityStartElapsedMs
                (elapsed.toFloat() / Constants.GRAVITY_CAPTURE_MS).coerceIn(0f, 1f)
            }
            else -> 1f
        }
        val sampleP = fwdCount.toFloat() / Constants.FWD_MIN_SAMPLES
        val segmentP = fwdSegments.toFloat() / Constants.FWD_MIN_SEGMENTS
        val qualityP = if (eigenRatio <= 0.0) 0f
        else (eigenRatio / Constants.FWD_MIN_EIGEN_RATIO).toFloat()
        val forwardProgress = when (state) {
            AlignmentState.ALIGNED -> 1f
            AlignmentState.COLLECTING_FORWARD ->
                minOf(sampleP, segmentP, qualityP).coerceIn(0f, 1f)
            else -> 0f
        }
        return AlignmentSnapshot(
            state = state,
            gravityProgress = gravityProgress,
            forwardProgress = forwardProgress,
            forwardSamples = fwdCount,
            forwardSegments = fwdSegments,
            eigenRatio = eigenRatio,
            angleStdDeg = angleStdDeg,
            mountDeviationDeg = mountDeviationDeg,
            mountRateDps = mountRateLpf.value.toFloat(),
            mountStable = mountStable && !mountHeldUnstable(SystemClock.elapsedRealtime()),
            lastInvalidationReason = invalidationReason,
            invalidationCount = invalidationCount
        )
    }

    // ------------------------------------------------------------------
    // 1) 중력(차량 상하축)
    // ------------------------------------------------------------------

    private fun accumulateGravity(
        frame: SensorFrame,
        gpsSpeedMps: Float?,
        gpsFresh: Boolean,
        nowMs: Long
    ) {
        val stationary = gpsFresh &&
            gpsSpeedMps != null &&
            gpsSpeedMps < Constants.STATIONARY_SPEED_MPS &&
            abs(frame.accel.norm - Constants.STANDARD_GRAVITY) < Constants.STATIONARY_ACCEL_TOLERANCE

        if (!stationary || frame.degraded) {
            if (gravityCount > 0) clearGravityAccumulator()
            state = AlignmentState.WAITING_STATIONARY
            return
        }

        if (gravityCount == 0) {
            gravityStartElapsedMs = nowMs
            state = AlignmentState.CAPTURING_GRAVITY
        }

        gravitySum += frame.accel
        val mag = frame.accel.norm.toDouble()
        gravityMagSum += mag
        gravityMagSqSum += mag * mag
        gravityCount++

        if (nowMs - gravityStartElapsedMs < Constants.GRAVITY_CAPTURE_MS) return
        if (gravityCount < 10) return

        // 누적 구간의 진동이 크면(공회전 이상, 승객 승하차 흔들림) 신뢰하지 않고 다시 모은다.
        val n = gravityCount.toDouble()
        val mean = gravityMagSum / n
        val variance = (gravityMagSqSum / n) - mean * mean
        val std = if (variance > 0) sqrt(variance) else 0.0
        if (std > Constants.STATIONARY_ACCEL_STD_MAX) {
            clearGravityAccumulator()
            state = AlignmentState.WAITING_STATIONARY
            return
        }

        val up = (gravitySum / gravityCount.toFloat()).normalized()
        if (up.norm < 0.5f) { clearGravityAccumulator(); return }

        vehicleUp = up
        basisE1 = Vec3.anyPerpendicular(up)
        basisE2 = (up cross basisE1!!).normalized()
        prevGravityUnit = up
        mountDeviationDeg = 0f

        clearGravityAccumulator()
        clearForwardAccumulator()
        state = AlignmentState.COLLECTING_FORWARD
    }

    private fun clearGravityAccumulator() {
        gravitySum = Vec3.ZERO
        gravityMagSum = 0.0
        gravityMagSqSum = 0.0
        gravityCount = 0
        gravityStartElapsedMs = 0L
    }

    // ------------------------------------------------------------------
    // 2) 전방 축
    // ------------------------------------------------------------------

    private fun accumulateForward(
        frame: SensorFrame,
        gpsAccelMps2: Float?,
        gpsFresh: Boolean,
        nowMs: Long
    ) {
        val up = vehicleUp ?: return
        val e1 = basisE1 ?: return
        val e2 = basisE2 ?: return

        if (frame.degraded || !gpsFresh || gpsAccelMps2 == null) return

        // 직진 가감속 구간만 채집한다.
        if (abs(gpsAccelMps2) < Constants.FWD_MIN_GPS_ACCEL_MPS2) return
        val yawRateDps = Math.toDegrees((frame.gyro dot up).toDouble()).toFloat()
        if (abs(yawRateDps) > Constants.FWD_MAX_YAW_RATE_DPS) return

        val horiz = frame.linearAccel.rejectFrom(up)
        if (horiz.norm < Constants.FWD_MIN_HORIZ_ACCEL_MPS2) return

        // 감속 구간의 벡터는 뒤를 향하므로 GPS 속도 증감 부호로 뒤집어 정렬한다.
        val sign = if (gpsAccelMps2 >= 0f) 1f else -1f
        val x = (horiz dot e1) * sign
        val y = (horiz dot e2) * sign
        val n = hypot(x.toDouble(), y.toDouble())
        if (n < 1e-6) return
        val ux = x / n
        val uy = y / n

        if (lastFwdSampleElapsedMs == 0L ||
            nowMs - lastFwdSampleElapsedMs > Constants.FWD_SEGMENT_GAP_MS
        ) {
            fwdSegments++
        }
        lastFwdSampleElapsedMs = nowMs

        sxx += ux * ux
        sxy += ux * uy
        syy += uy * uy
        sumUx += ux
        sumUy += uy
        fwdCount++

        if (fwdCount % 20 != 0) return
        evaluateForward(up, e1, e2)
    }

    private fun evaluateForward(up: Vec3, e1: Vec3, e2: Vec3) {
        val n = fwdCount.toDouble()
        val a = sxx / n
        val b = sxy / n
        val c = syy / n

        // 단위벡터의 산포행렬이므로 trace = 1. 고유값 2개를 닫힌 형태로 구한다.
        val tr = a + c
        val det = a * c - b * b
        val disc = tr * tr - 4.0 * det
        if (disc < 0.0) return
        val root = sqrt(disc)
        val l1 = (tr + root) / 2.0
        val l2 = (tr - root) / 2.0
        eigenRatio = if (l2 > 1e-9) l1 / l2 else Double.MAX_VALUE

        // 원형 표준편차: R = |평균 단위벡터|, std = sqrt(-2 ln R)
        val rBar = hypot(sumUx / n, sumUy / n)
        angleStdDeg = if (rBar <= 1e-9) 180.0
        else Math.toDegrees(sqrt(-2.0 * ln(rBar.coerceAtMost(1.0))))

        if (fwdCount < Constants.FWD_MIN_SAMPLES) return
        if (fwdSegments < Constants.FWD_MIN_SEGMENTS) return
        if (eigenRatio < Constants.FWD_MIN_EIGEN_RATIO) return
        if (angleStdDeg.isNaN() || angleStdDeg > Constants.FWD_MAX_ANGLE_STD_DEG) return

        // 주고유벡터
        var vx: Double
        var vy: Double
        if (abs(b) > 1e-9) {
            vx = b
            vy = l1 - a
        } else {
            vx = if (a >= c) 1.0 else 0.0
            vy = if (a >= c) 0.0 else 1.0
        }
        val vn = hypot(vx, vy)
        if (vn < 1e-9) return
        vx /= vn; vy /= vn

        // PCA 고유벡터는 부호 모호성이 있으므로 평균 벡터와 같은 방향으로 맞춘다.
        if (vx * sumUx + vy * sumUy < 0.0) { vx = -vx; vy = -vy }

        val forwardRaw = (e1 * vx.toFloat() + e2 * vy.toFloat())
        val forward = forwardRaw.rejectFrom(up).normalized()
        if (forward.norm < 0.5f) return

        vehicleForward = forward
        vehicleLeft = (up cross forward).normalized()
        state = AlignmentState.ALIGNED
    }

    private fun clearForwardAccumulator() {
        sxx = 0.0; sxy = 0.0; syy = 0.0
        sumUx = 0.0; sumUy = 0.0
        fwdCount = 0
        fwdSegments = 0
        lastFwdSampleElapsedMs = 0L
        eigenRatio = 0.0
        angleStdDeg = Double.NaN
    }

    // ------------------------------------------------------------------
    // 3) 거치 안정성
    // ------------------------------------------------------------------

    private fun updateMountStability(frame: SensorFrame, nowMs: Long) {
        val gUnit = frame.gravity.normalized()
        if (gUnit.norm < 0.5f) return

        val prev = prevGravityUnit
        if (prev != null && frame.dtSec > 0.0 && frame.dtSec < 0.5) {
            val deltaDeg = angleBetweenDeg(prev, gUnit)
            mountRateLpf.update(deltaDeg / frame.dtSec, frame.dtSec)
        }
        prevGravityUnit = gUnit

        val ref = vehicleUp
        if (ref == null) {
            mountStable = true
            return
        }
        mountDeviationDeg = angleBetweenDeg(ref, gUnit)

        // 1차 지표: 중력 방향 변화율. 도로 경사 변화는 통상 5도/초를 넘지 않는다.
        val rate = mountRateLpf.value
        if (rate > Constants.MOUNT_GRAVITY_RATE_DPS) {
            if (rateExceedSinceMs == 0L) rateExceedSinceMs = nowMs
            if (nowMs - rateExceedSinceMs >= Constants.MOUNT_RATE_SUSTAIN_MS) {
                invalidate(nowMs, "중력 방향 급변 %.0f°/s".format(rate))
                return
            }
        } else {
            rateExceedSinceMs = 0L
        }

        // 2차 지표: 절대 편차. 경사 7도 + 노즈다이브 3도를 크게 상회하는 값만 걸린다.
        if (mountDeviationDeg > Constants.MOUNT_DEVIATION_MAX_DEG) {
            if (deviationExceedSinceMs == 0L) deviationExceedSinceMs = nowMs
            if (nowMs - deviationExceedSinceMs >= Constants.MOUNT_DEVIATION_SUSTAIN_MS) {
                invalidate(nowMs, "거치 각도 편차 %.0f°".format(mountDeviationDeg))
                return
            }
        } else {
            deviationExceedSinceMs = 0L
        }

        // 3차 지표: 정상 주행에서 나올 수 없는 각속도. 폰을 손으로 다루는 동작.
        val gyroDps = Math.toDegrees(frame.gyro.norm.toDouble())
        if (gyroDps > Constants.MOUNT_ABSURD_GYRO_DPS) {
            invalidate(nowMs, "비정상 각속도 %.0f°/s".format(gyroDps))
            return
        }

        mountStable = true
    }

    private fun invalidate(nowMs: Long, reason: String) {
        invalidationReason = reason
        invalidationCount++
        reset()
        // reset()은 내부 상태만 되돌린다. 이탈 직후 일정 시간은 게이트를 계속 막아
        // 화면에 "재보정 중"이 확실히 보이도록 한다.
        mountStable = false
        mountUnstableUntilMs = nowMs + MOUNT_UNSTABLE_HOLD_MS
    }

    companion object {
        /** 이탈 판정 후 게이트를 강제로 막아 두는 시간. */
        private const val MOUNT_UNSTABLE_HOLD_MS = 2000L
    }

    // ------------------------------------------------------------------

    private fun toVehicleMotion(frame: SensorFrame): VehicleMotion? {
        val f = vehicleForward ?: return null
        val l = vehicleLeft ?: return null
        val u = vehicleUp ?: return null
        val lin = frame.linearAccel
        return VehicleMotion(
            longitudinal = lin dot f,
            lateral = lin dot l,
            vertical = lin dot u,
            // Android 자이로는 오른손 좌표계이므로 상방 축 기준 양의 회전은 좌회전이다.
            yawRateDps = Math.toDegrees((frame.gyro dot u).toDouble()).toFloat()
        )
    }
}
