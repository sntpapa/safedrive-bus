package com.safedrive.bus.fusion

import android.os.SystemClock
import com.safedrive.bus.align.VehicleMotion
import com.safedrive.bus.core.Constants
import com.safedrive.bus.filter.SpikeSuppressedLowPass
import com.safedrive.bus.loc.GpsSample
import kotlin.math.abs

data class MotionSnapshot(
    /** 융합 속도 [km/h]. GPS 기준값에 종가속도 적분으로 보간한 값. */
    val fusedSpeedKmh: Float = 0f,
    /** GPS 원본 속도 [km/h]. 비교용으로 따로 노출한다. */
    val gpsSpeedKmh: Float = 0f,
    /** GPS 속도 변화율 [km/h/s]. 1Hz 차분값. */
    val gpsAccelKmhPerSec: Float = 0f,
    /** 필터링된 종방향 가속도 [m/s^2]. + = 가속 */
    val longitudinalMps2: Float = 0f,
    /** 종방향 가속도를 km/h/s로 환산한 값. 판정 기준표와 같은 단위. */
    val longitudinalKmhPerSec: Float = 0f,
    /** 필터링된 횡방향 가속도 [m/s^2]. + = 좌측 */
    val lateralMps2: Float = 0f,
    /** 필터링된 요레이트 [deg/s]. + = 좌회전 */
    val yawRateDps: Float = 0f,
    /** 누적 주행거리 [m] */
    val distanceM: Double = 0.0,
    /** GPS fix 이후 경과 시간 [ms] */
    val gpsAgeMs: Long = Long.MAX_VALUE,
    val gpsAccuracyM: Float = Float.MAX_VALUE,
    /** 마지막 위치 (표시/저장용) */
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /** 속도가 GPS 실측인지 적분 보간인지 */
    val speedFromDeadReckoning: Boolean = false,
    /** GPS 품질이 기준을 충족해 속도를 신뢰할 수 있는지. false면 화면에서 흐리게 표시한다. */
    val speedTrusted: Boolean = false,
    /** 이번 fix의 속도가 GNSS 정지 잡음으로 판단되어 0으로 눌렸는지 */
    val gpsSpeedSuppressed: Boolean = false,
    /** GNSS가 보고한 속도 정확도 [m/s]. 없으면 null. */
    val gpsSpeedAccuracyMps: Float? = null,
    /** 경사로 보정 신뢰 가능 여부. 회전벡터 기반 중력이 아니면 false. */
    val pitchCompensationReliable: Boolean = false,
    val stationary: Boolean = true
)

/**
 * GPS 절대속도와 IMU를 융합해 속도/가감속을 만든다.
 *
 * - 속도의 기준값은 항상 GPS다. GPS fix가 올 때마다 적분값을 리셋한다.
 * - fix 사이 구간은 종방향 가속도 적분으로 보간하되, DEAD_RECKON_MAX_MS를 넘으면
 *   적분 드리프트를 신뢰할 수 없으므로 마지막 GPS 값을 유지한다.
 * - 종/횡 가속도에는 중앙값 + 저역통과를 건다. 과속방지턱/포트홀의 단발 스파이크를
 *   제거하지 않으면 급감속 오탐이 그대로 발생한다.
 */
class MotionEstimator {

    private val longFilter =
        SpikeSuppressedLowPass(Constants.MEDIAN_TAPS, Constants.ACCEL_LPF_CUTOFF_HZ)
    private val latFilter =
        SpikeSuppressedLowPass(Constants.MEDIAN_TAPS, Constants.ACCEL_LPF_CUTOFF_HZ)
    private val yawFilter =
        SpikeSuppressedLowPass(Constants.MEDIAN_TAPS, Constants.GYRO_LPF_CUTOFF_HZ)

    private var lastGps: GpsSample? = null
    private var prevGps: GpsSample? = null

    /** GPS 속도 변화율 [m/s^2]. 좌표계 정렬의 부호 결정에 쓰인다. */
    @Volatile
    var gpsAccelMps2: Float? = null
        private set

    @Volatile
    var gpsSpeedMps: Float? = null
        private set

    @Volatile
    var gpsFresh: Boolean = false
        private set

    private var fusedSpeedMps = 0f
    private var speedAnchorElapsedMs = 0L
    private var deadReckoning = false
    private var distanceM = 0.0
    private var lastFrameElapsedMs = 0L

    private var lastLong = 0f
    private var lastLat = 0f
    private var lastYaw = 0f
    private var pitchReliable = false
    private var speedTrusted = false
    private var speedSuppressed = false

    fun reset() {
        longFilter.reset(); latFilter.reset(); yawFilter.reset()
        lastGps = null; prevGps = null
        gpsAccelMps2 = null; gpsSpeedMps = null; gpsFresh = false
        fusedSpeedMps = 0f
        speedAnchorElapsedMs = 0L
        deadReckoning = false
        distanceM = 0.0
        lastFrameElapsedMs = 0L
        lastLong = 0f; lastLat = 0f; lastYaw = 0f
        speedTrusted = false
        speedSuppressed = false
    }

    fun onGps(sample: GpsSample) {
        prevGps = lastGps
        lastGps = sample

        val speed = suppressStationaryNoise(sample)
        speedSuppressed = sample.speedMps != null && speed == 0f && sample.speedMps > 0f
        speedTrusted = sample.accuracyM <= Constants.GPS_ACCURACY_MAX_M && speed != null

        if (speed != null) {
            gpsSpeedMps = speed
            fusedSpeedMps = speed
            speedAnchorElapsedMs = sample.elapsedMs
            deadReckoning = false
        }

        val p = prevGps
        val prevSpeed = p?.let { suppressStationaryNoise(it) }
        if (p != null && speed != null && prevSpeed != null) {
            val dt = (sample.elapsedMs - p.elapsedMs) / 1000.0f
            // 1Hz 차분이므로 dt가 0.5~2초 범위를 벗어나면 신뢰하지 않는다.
            gpsAccelMps2 = if (dt in 0.5f..2.0f) (speed - prevSpeed) / dt else null
        } else {
            gpsAccelMps2 = null
        }
    }

    /**
     * 정차 중인 차량에서도 도플러 GNSS 속도는 0이 아니라 자체 정확도 수준의 잡음을 낸다.
     * 실측에서 정지 상태로 3.8~11.2 km/h가 관측됐고, 이 값을 그대로 쓰면
     *  - 좌표계 보정의 "정차" 조건이 절대 성립하지 않고
     *  - 화면에는 서 있는데 속도가 올라가는 것처럼 보인다.
     *
     * 보고된 속도가 그 속도의 정확도(1시그마)보다 작으면 0과 구분되지 않으므로 0으로 본다.
     */
    private fun suppressStationaryNoise(sample: GpsSample): Float? {
        val raw = sample.speedMps ?: return null
        val sigma = sample.speedAccuracyMps
        val floor = if (sigma != null && sigma > 0f) {
            sigma * Constants.GPS_SPEED_NOISE_FACTOR
        } else {
            Constants.GPS_SPEED_NOISE_FALLBACK_MPS
        }
        return if (raw < floor) 0f else raw
    }

    /**
     * @param motion 정렬이 끝난 경우의 차량 좌표계 운동량. 미정렬이면 null.
     * @param pitchCompensationReliable 회전벡터 기반 중력 제거를 쓰고 있는지
     */
    fun onFrame(
        dtSec: Double,
        motion: VehicleMotion?,
        pitchCompensationReliable: Boolean
    ) {
        pitchReliable = pitchCompensationReliable
        val nowMs = SystemClock.elapsedRealtime()
        lastFrameElapsedMs = nowMs
        gpsFresh = lastGps?.isFresh() == true

        if (motion == null) {
            // 미정렬 상태에서는 종/횡 성분을 정의할 수 없다. 필터를 리셋해
            // 정렬 완료 직후 과거 값이 섞이지 않게 한다.
            longFilter.reset(); latFilter.reset(); yawFilter.reset()
            lastLong = 0f; lastLat = 0f; lastYaw = 0f
        } else {
            val dt = if (dtSec > 0.0 && dtSec < 1.0) dtSec else 0.02
            lastLong = longFilter.update(motion.longitudinal.toDouble(), dt).toFloat()
            lastLat = latFilter.update(motion.lateral.toDouble(), dt).toFloat()
            lastYaw = yawFilter.update(motion.yawRateDps.toDouble(), dt).toFloat()
        }

        // 속도 보간
        val anchorAge = nowMs - speedAnchorElapsedMs
        if (speedAnchorElapsedMs == 0L) {
            deadReckoning = false
        } else if (anchorAge > Constants.DEAD_RECKON_MAX_MS) {
            // 적분 드리프트가 커지는 구간. 마지막 GPS 값을 그대로 유지한다.
            deadReckoning = true
            fusedSpeedMps = gpsSpeedMps ?: fusedSpeedMps
        } else if (motion != null && dtSec > 0.0 && dtSec < 1.0) {
            // GPS fix 직후 몇 프레임은 사실상 실측값이다. 150ms를 넘어야 보간으로 표시한다.
            deadReckoning = anchorAge > DEAD_RECKON_DISPLAY_THRESHOLD_MS
            fusedSpeedMps = (fusedSpeedMps + lastLong * dtSec.toFloat()).coerceAtLeast(0f)
        }

        if (dtSec > 0.0 && dtSec < 1.0) {
            distanceM += fusedSpeedMps * dtSec
        }
    }

    fun snapshot(): MotionSnapshot {
        val g = lastGps
        val stationary = fusedSpeedMps < Constants.STATIONARY_SPEED_MPS
        return MotionSnapshot(
            fusedSpeedKmh = fusedSpeedMps * 3.6f,
            gpsSpeedKmh = (gpsSpeedMps ?: 0f) * 3.6f,
            gpsAccelKmhPerSec = (gpsAccelMps2 ?: 0f) * 3.6f,
            longitudinalMps2 = lastLong,
            longitudinalKmhPerSec = lastLong * 3.6f,
            lateralMps2 = lastLat,
            yawRateDps = lastYaw,
            distanceM = distanceM,
            gpsAgeMs = g?.ageMs() ?: Long.MAX_VALUE,
            gpsAccuracyM = g?.accuracyM ?: Float.MAX_VALUE,
            latitude = g?.latitude ?: 0.0,
            longitude = g?.longitude ?: 0.0,
            speedFromDeadReckoning = deadReckoning,
            speedTrusted = speedTrusted,
            gpsSpeedSuppressed = speedSuppressed,
            gpsSpeedAccuracyMps = g?.speedAccuracyMps,
            pitchCompensationReliable = pitchReliable,
            stationary = stationary
        )
    }

    private companion object {
        const val DEAD_RECKON_DISPLAY_THRESHOLD_MS = 150L
    }

    /**
     * 1단계 완료 기준 검증용 보조값.
     * 종방향 가속도(km/h/s)와 GPS 속도 변화율(km/h/s)의 차이.
     * 목표: 주행 중 ±1.5 km/h/s 이내.
     */
    fun longitudinalVsGpsErrorKmhPerSec(): Float? {
        val ga = gpsAccelMps2 ?: return null
        return abs(lastLong - ga) * 3.6f
    }
}
