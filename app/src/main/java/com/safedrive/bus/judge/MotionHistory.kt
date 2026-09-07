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
        @JvmField val yawRateDps: Float
    )

    private val samples = ArrayDeque<Sample>()

    fun clear() = samples.clear()

    val size: Int get() = samples.size

    fun add(
        ts: Long,
        speedKmh: Float,
        longKmhPerSec: Float,
        verticalMps2: Float,
        yawRateDps: Float
    ) {
        samples.addLast(Sample(ts, speedKmh, longKmhPerSec, verticalMps2, yawRateDps))
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

    /** [from, to] 구간의 수직 가속도 절대 피크. 노면 충격 판단에 쓴다. */
    fun peakVerticalAbs(from: Long, to: Long): Float {
        var best = 0f
        for (s in samples) if (s.ts in from..to) best = max(best, abs(s.verticalMps2))
        return best
    }

    fun lastTs(): Long = samples.lastOrNull()?.ts ?: 0L
}
