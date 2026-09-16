package com.safedrive.bus.judge

import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.DrivingStandards
import com.safedrive.bus.core.EventType

/**
 * 그림자 판정 결과 1건. 경고는 내지 않고 기록만 한다.
 */
data class ShadowEvent(
    val wallMs: Long,
    val type: EventType,
    /** 융합 속도 [km/h] */
    val speedKmh: Float,
    /** 융합 속도의 1초 변화량 [km/h/s]. 이 값으로 판정했다. */
    val judgedValue: Float,
    val threshold: Float,
    /** 같은 순간 GPS 1초 차분 [km/h/s]. 지금 방식과 얼마나 다른지 보려고 남긴다. */
    val gpsValue: Float,
    /** 추정한 가속도 편향 [m/s²]. 도로 경사와 센서 오차가 여기로 흡수된다. */
    val biasMps2: Float,
    val latitude: Double,
    val longitude: Double
)

/**
 * GPS·IMU 융합 판정의 **그림자 실행**. 경고를 내지 않고 결과만 남긴다.
 *
 * 왜 그림자로 돌리나
 *  지금 방식(GPS 1초 차분)은 오탐이 많지만, 융합 방식으로 바꾸면 반대로 미탐이 생길 수 있다.
 *  실측 이벤트를 IMU 평균만으로 다시 판정해 보니 기준을 넘는 건이 한 건도 없었다
 *  (2026-09-16, 144번 36km). GPS 지연 때문에 IMU 창이 실제 제동을 놓쳤을 가능성이 크지만,
 *  확인 없이 바꾸면 "경고가 거의 안 나가는 앱"이 된다. 그래서 기사에게 나가는 경고는
 *  지금 방식 그대로 두고, 새 방식은 같은 운행에서 계산만 해 나란히 기록한다.
 *
 * 무엇이 다른가
 *  - GPS 속도를 1초마다 덮어쓰는 기준값이 아니라 **지연과 잡음을 가진 관측값**으로 쓴다.
 *  - 속도 변화는 IMU 종가속도 적분으로 만든다. 도플러 지연도, 정지 잡음 억제 계단도 없다.
 *  - GPS와 IMU의 차이를 가속도 편향으로 흡수한다. 도로 경사와 센서 오차가 여기로 들어간다.
 *
 * 한계
 *  정렬이 끝나야 종방향을 알 수 있다. 정렬 전에는 상태만 GPS로 맞춰 두고 판정하지 않는다.
 *  그래서 이 방식은 정렬 품질에 지금보다 더 크게 의존한다.
 */
class ShadowJudge {

    private class Sample(@JvmField val ts: Long, @JvmField val vMps: Float)

    private val history = ArrayDeque<Sample>()
    private val fired = ArrayList<ShadowEvent>()
    private val lastFiredWallMs = HashMap<String, Long>()

    /** 융합 속도 추정값 [m/s] */
    private var vHat = 0f

    /** 가속도 편향 추정값 [m/s²]. 경사로와 센서 오차. */
    private var biasMps2 = 0f

    private var lastJudgeNs = 0L

    /** GPS 보정을 한 번이라도 받았는지. 받기 전에는 적분값을 믿을 수 없다. */
    private var corrected = false

    fun reset() {
        history.clear()
        fired.clear()
        lastFiredWallMs.clear()
        vHat = 0f
        biasMps2 = 0f
        lastJudgeNs = 0L
        corrected = false
    }

    fun events(): List<ShadowEvent> = fired.toList()

    val count: Int get() = fired.size

    /**
     * 센서 프레임마다 호출한다.
     *
     * @param longitudinalMps2 차량 좌표계 종가속도. 정렬 전이면 null.
     * @param gpsIsNewFix 이번 프레임에서 새 GPS fix가 들어왔는지
     * @param gpsValueKmhPerSec 같은 순간 GPS 1초 차분. 기록 비교용이며 판정에는 쓰지 않는다.
     */
    fun feed(
        timestampNs: Long,
        dtSec: Double,
        longitudinalMps2: Float?,
        gpsSpeedMps: Float?,
        gpsIsNewFix: Boolean,
        wallMs: Long,
        gpsValueKmhPerSec: Float,
        latitude: Double,
        longitude: Double
    ) {
        if (longitudinalMps2 == null) {
            // 정렬 전. 종방향을 모르므로 판정하지 않는다. 상태만 GPS에 맞춰 둔다.
            if (gpsSpeedMps != null) vHat = gpsSpeedMps
            history.clear()
            biasMps2 = 0f
            corrected = false
            return
        }

        val dt = if (dtSec > 0.0 && dtSec < 1.0) dtSec.toFloat() else 0.02f
        vHat = (vHat + (longitudinalMps2 - biasMps2) * dt).coerceAtLeast(0f)
        history.addLast(Sample(timestampNs, vHat))
        val cutoff = timestampNs - RETAIN_MS * 1_000_000L
        while (history.isNotEmpty() && history.first().ts < cutoff) history.removeFirst()

        if (gpsIsNewFix && gpsSpeedMps != null) {
            // GPS는 0.5~1초 늦다. 그 시점의 추정값과 비교해야 같은 순간을 견주는 것이 된다.
            val delayedNs = timestampNs - Constants.SHADOW_GPS_LAG_MS * 1_000_000L
            val innovation = gpsSpeedMps - speedAt(delayedNs)
            vHat = (vHat + Constants.SHADOW_GAIN_SPEED * innovation).coerceAtLeast(0f)
            biasMps2 = (biasMps2 - Constants.SHADOW_GAIN_BIAS * innovation)
                .coerceIn(-Constants.SHADOW_MAX_BIAS_MPS2, Constants.SHADOW_MAX_BIAS_MPS2)
            corrected = true
        }

        if (!corrected) return
        if (lastJudgeNs != 0L && timestampNs - lastJudgeNs < JUDGE_INTERVAL_MS * 1_000_000L) return
        lastJudgeNs = timestampNs
        judge(timestampNs, wallMs, gpsValueKmhPerSec, latitude, longitude)
    }

    /** 주어진 시각의 융합 속도 [m/s]. 두 샘플 사이면 선형보간. */
    private fun speedAt(ts: Long): Float {
        if (history.isEmpty()) return vHat
        if (ts <= history.first().ts) return history.first().vMps
        if (ts >= history.last().ts) return history.last().vMps
        var prev = history.first()
        for (s in history) {
            if (s.ts >= ts) {
                val span = (s.ts - prev.ts).toFloat()
                val t = if (span <= 0f) 0f else (ts - prev.ts) / span
                return prev.vMps + (s.vMps - prev.vMps) * t
            }
            prev = s
        }
        return history.last().vMps
    }

    /**
     * 판정 규칙은 본 판정기와 **똑같이** 맞춘다. 규칙이 다르면 두 방식을 비교하는 의미가 없다.
     * 값이 바뀌면 이쪽도 함께 고쳐야 한다.
     */
    private fun judge(
        timestampNs: Long,
        wallMs: Long,
        gpsValue: Float,
        latitude: Double,
        longitude: Double
    ) {
        val from = timestampNs - Constants.JUDGE_WINDOW_MS * 1_000_000L
        if (history.isEmpty() || history.first().ts > from) return

        val v0 = speedAt(from) * 3.6f
        val v1 = vHat * 3.6f
        if (maxOf(v0, v1) < Constants.JUDGE_IDLE_FLOOR_KMH) return

        val dv = v1 - v0
        if (dv > 0f) {
            if (v0 <= DrivingStandards.HARSH_START_MAX_INITIAL_KMH) {
                val th = DrivingStandards.HARSH_START_THRESHOLD_KMH_PER_SEC.toFloat()
                if (dv >= th) fire(EventType.HARSH_START, wallMs, v1, dv, th, gpsValue, latitude, longitude)
            } else {
                accelThreshold(v0)?.let { th ->
                    if (dv >= th) {
                        fire(EventType.HARSH_ACCEL, wallMs, v1, dv, th, gpsValue, latitude, longitude)
                    }
                }
            }
        } else {
            val decel = -dv
            if (v1 <= DrivingStandards.HARSH_STOP_FINAL_SPEED_KMH) {
                val th = DrivingStandards.HARSH_STOP_THRESHOLD_KMH_PER_SEC.toFloat()
                if (decel >= th) fire(EventType.HARSH_STOP, wallMs, v1, dv, th, gpsValue, latitude, longitude)
            } else if (v1 >= DrivingStandards.HARSH_DECEL_MIN_SPEED_KMH) {
                val th = decelThreshold(v0)
                if (decel >= th) fire(EventType.HARSH_DECEL, wallMs, v1, dv, th, gpsValue, latitude, longitude)
            }
        }
    }

    private fun fire(
        type: EventType,
        wallMs: Long,
        speedKmh: Float,
        judgedValue: Float,
        threshold: Float,
        gpsValue: Float,
        latitude: Double,
        longitude: Double
    ) {
        val group = when (type) {
            EventType.HARSH_DECEL, EventType.HARSH_STOP -> "DECEL"
            else -> "ACCEL"
        }
        val last = lastFiredWallMs[group] ?: 0L
        if (last != 0L && wallMs - last < Constants.WARNING_DEBOUNCE_MS) return
        lastFiredWallMs[group] = wallMs
        if (fired.size >= MAX_EVENTS) return
        fired.add(
            ShadowEvent(
                wallMs = wallMs,
                type = type,
                speedKmh = speedKmh,
                judgedValue = judgedValue,
                threshold = threshold,
                gpsValue = gpsValue,
                biasMps2 = biasMps2,
                latitude = latitude,
                longitude = longitude
            )
        )
    }

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

    private companion object {
        /** 판정 주기. 매 프레임 돌릴 필요가 없다. */
        const val JUDGE_INTERVAL_MS = 100L

        /** 속도 이력 보관 시간. 판정 창(1초)과 지연 보정(0.7초)을 덮을 만큼. */
        const val RETAIN_MS = 4000L

        /** 메모리 보호. 한 운행에서 이보다 많이 나오면 어차피 방식이 잘못된 것이다. */
        const val MAX_EVENTS = 2000
    }
}
