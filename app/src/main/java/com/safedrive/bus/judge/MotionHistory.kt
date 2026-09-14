package com.safedrive.bus.judge

import com.safedrive.bus.core.Constants
import kotlin.math.abs
import kotlin.math.max

/**
 * 판정에 필요한 최근 이력 링버퍼.
 *
 * 50Hz * 7초 = 350 샘플 수준이라 통째로 들고 있어도 부담이 없다.
 * 모든 시각은 SensorEvent.timestamp(ns) 기준 단조 증가 값을 쓴다.
 */
class MotionHistory {

    private class Sample(
        @JvmField val ts: Long,
        @JvmField val speedKmh: Float,
        @JvmField val longKmhPerSec: Float,
        @JvmField val verticalMps2: Float,
        @JvmField val yawRateDps: Float,
        /** 중력 방향 성분을 뺀 수평 가속도 크기. 좌표계 정렬 없이 얻는다. */
        @JvmField val horizontalMps2: Float,
        /** 수평 가속도 벡터를 저역통과한 뒤의 크기. 진동이 상쇄된 값. */
        @JvmField val horizontalLpfMps2: Float,
        /** 이 시점의 GPS 속도가 정지 잡음 억제로 0으로 눌렸는지. */
        @JvmField val speedSuppressed: Boolean
    )

    private val samples = ArrayDeque<Sample>()

    fun clear() = samples.clear()

    val size: Int get() = samples.size

    fun add(
        ts: Long,
        speedKmh: Float,
        longKmhPerSec: Float,
        verticalMps2: Float,
        yawRateDps: Float,
        horizontalMps2: Float,
        horizontalLpfMps2: Float,
        speedSuppressed: Boolean
    ) {
        samples.addLast(
            Sample(
                ts, speedKmh, longKmhPerSec, verticalMps2, yawRateDps,
                horizontalMps2, horizontalLpfMps2, speedSuppressed
            )
        )
        val cutoff = ts - Constants.HISTORY_RETENTION_MS * 1_000_000L
        while (samples.isNotEmpty() && samples.first().ts < cutoff) samples.removeFirst()
    }

    /** 창 전체를 채울 만큼 이력이 쌓였는지. 부족하면 판정하지 않는다. */
    fun covers(windowMs: Long): Boolean {
        if (samples.size < 2) return false
        val span = samples.last().ts - samples.first().ts
        return span >= windowMs * 1_000_000L
    }

    /** 주어진 시각의 속도. 두 샘플 사이면 선형보간. 범위 밖이면 가장 가까운 값. */
    fun speedAt(ts: Long): Float {
        if (samples.isEmpty()) return 0f
        if (ts <= samples.first().ts) return samples.first().speedKmh
        if (ts >= samples.last().ts) return samples.last().speedKmh
        var prev = samples.first()
        for (s in samples) {
            if (s.ts >= ts) {
                val span = (s.ts - prev.ts).toFloat()
                val t = if (span <= 0f) 0f else (ts - prev.ts) / span
                return prev.speedKmh + (s.speedKmh - prev.speedKmh) * t
            }
            prev = s
        }
        return samples.last().speedKmh
    }

    /** [from, to] 구간의 요레이트 적분값(도). + = 좌회전 누적. */
    fun yawIntegralDeg(from: Long, to: Long): Float {
        var acc = 0f
        var prev: Sample? = null
        for (s in samples) {
            val p = prev
            if (p != null && s.ts > from && p.ts < to) {
                val dt = (s.ts - p.ts) / 1e9f
                if (dt > 0f && dt < 0.5f) {
                    acc += (s.yawRateDps + p.yawRateDps) * 0.5f * dt
                }
            }
            prev = s
        }
        return acc
    }

    /** [from, to] 구간의 최저 속도. */
    fun minSpeed(from: Long, to: Long): Float {
        var m = Float.MAX_VALUE
        for (s in samples) if (s.ts in from..to) m = minOf(m, s.speedKmh)
        return if (m == Float.MAX_VALUE) 0f else m
    }

    /** [from, to] 구간에서 절대값이 가장 큰 종방향 가감속(부호 유지). */
    fun peakLongitudinal(from: Long, to: Long): Float {
        var best = 0f
        for (s in samples) {
            if (s.ts in from..to && abs(s.longKmhPerSec) > abs(best)) best = s.longKmhPerSec
        }
        return best
    }

    /**
     * [from, to] 구간의 수평 가속도 크기 피크 [m/s^2].
     *
     * 좌표계 정렬이 필요 없다. 종방향 가속의 상한이므로, 이 값이 GPS 판정값보다
     * 한참 작으면 GPS 쪽이 틀린 것이다.
     */
    fun peakHorizontal(from: Long, to: Long): Float {
        var best = 0f
        for (s in samples) if (s.ts in from..to) best = max(best, s.horizontalMps2)
        return best
    }

    /**
     * [from, to] 구간의 저역통과 수평 가속도 크기 피크 [m/s²].
     *
     * 원시 크기 피크는 진동에 부풀려져 대체 검증이 거의 걸리지 않았다(실측 2026-09-14:
     * 원시 피크가 IMU 종가속 평균의 2.4~18.4배). 필터가 선형이므로 종방향 성분의 상한이라는
     * 성질은 그대로 유지된다.
     */
    fun peakHorizontalLpf(from: Long, to: Long): Float {
        var best = 0f
        for (s in samples) if (s.ts in from..to) best = max(best, s.horizontalLpfMps2)
        return best
    }

    /**
     * [from, to] 구간의 종방향 가감속 **평균** [km/h/s].
     *
     * 교차검증에 피크(절대값 최대)를 쓰면 노면 진동 스파이크가 진짜 신호를 덮는다.
     * 실측(2026-09-13)에서 정렬 완료 구간 IMU 피크 크기의 중앙값이 0.55~0.62 m/s²인데
     * 최대는 2.9 m/s²까지 튀었고, 급가속 이벤트의 47~51%에서 부호가 반대로 나왔다.
     *
     * 평균은 진동이 상쇄된다. GPS 판정값도 1초 창의 평균 변화율이므로 같은 것끼리
     * 비교하게 된다는 점에서도 맞다.
     */
    fun meanLongitudinal(from: Long, to: Long): Float {
        var sum = 0f
        var n = 0
        for (s in samples) if (s.ts in from..to) { sum += s.longKmhPerSec; n++ }
        return if (n == 0) 0f else sum / n
    }

    /**
     * [from, to] 구간에 정지 잡음 억제로 속도가 0으로 눌린 샘플이 있었는지.
     *
     * 눌린 구간에서 벗어나는 순간 도플러 지연까지 겹쳐 속도가 한꺼번에 올라온다.
     * 그 1초 차분은 실제 가속이 아니라 억제가 풀린 계단이다.
     */
    fun hadSuppressedSpeed(from: Long, to: Long): Boolean {
        for (s in samples) if (s.ts in from..to && s.speedSuppressed) return true
        return false
    }

    /** [from, to] 구간의 수직 가속도 절대 피크. 노면 충격 판단에 쓴다. */
    fun peakVerticalAbs(from: Long, to: Long): Float {
        var best = 0f
        for (s in samples) if (s.ts in from..to) best = max(best, abs(s.verticalMps2))
        return best
    }

    fun lastTs(): Long = samples.lastOrNull()?.ts ?: 0L
}
