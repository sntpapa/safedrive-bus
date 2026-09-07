package com.safedrive.bus.filter

import com.safedrive.bus.core.Vec3

/**
 * 1차 IIR 저역통과 필터.
 *
 * 가변 dt를 받는 형태로 구현했다. 센서 배칭 때문에 도착 간격이 불규칙하므로
 * 고정 계수 필터를 쓰면 차단주파수가 흔들린다. 이벤트 타임스탬프 기반 dt를 사용한다.
 */
class LowPass1P(private val cutoffHz: Double) {
    private var y = Double.NaN
    private val rc = 1.0 / (2.0 * Math.PI * cutoffHz)

    fun update(x: Double, dtSec: Double): Double {
        if (y.isNaN()) { y = x; return y }
        // dt가 비정상(끊김 직후)이면 필터 상태를 신뢰할 수 없으므로 리셋한다.
        if (dtSec <= 0.0 || dtSec > 1.0) { y = x; return y }
        val a = dtSec / (dtSec + rc)
        y += a * (x - y)
        return y
    }

    fun reset() { y = Double.NaN }
    val initialized: Boolean get() = !y.isNaN()
    val value: Double get() = if (y.isNaN()) 0.0 else y
}

class LowPass1PVec3(cutoffHz: Double) {
    private val fx = LowPass1P(cutoffHz)
    private val fy = LowPass1P(cutoffHz)
    private val fz = LowPass1P(cutoffHz)

    fun update(v: Vec3, dtSec: Double): Vec3 = Vec3(
        fx.update(v.x.toDouble(), dtSec).toFloat(),
        fy.update(v.y.toDouble(), dtSec).toFloat(),
        fz.update(v.z.toDouble(), dtSec).toFloat()
    )

    fun reset() { fx.reset(); fy.reset(); fz.reset() }
    val initialized: Boolean get() = fx.initialized
    val value: Vec3 get() = Vec3(fx.value.toFloat(), fy.value.toFloat(), fz.value.toFloat())
}

/**
 * 이동 중앙값 필터. 과속방지턱/포트홀에서 나오는 1~2 샘플짜리 단발 스파이크 제거용.
 * LPF만으로는 스파이크가 통과 대역으로 번져 나가므로 LPF 앞단에 둔다.
 */
class MedianWindow(private val taps: Int) {
    init { require(taps % 2 == 1) { "taps must be odd" } }
    private val buf = DoubleArray(taps)
    private var count = 0
    private var idx = 0
    private val scratch = DoubleArray(taps)

    fun update(x: Double): Double {
        buf[idx] = x
        idx = (idx + 1) % taps
        if (count < taps) count++
        System.arraycopy(buf, 0, scratch, 0, count)
        java.util.Arrays.sort(scratch, 0, count)
        return scratch[count / 2]
    }

    fun reset() { count = 0; idx = 0 }
}

/** 스파이크 제거 + 저역통과를 묶은 스칼라 파이프라인. */
class SpikeSuppressedLowPass(taps: Int, cutoffHz: Double) {
    private val median = MedianWindow(taps)
    private val lpf = LowPass1P(cutoffHz)

    fun update(x: Double, dtSec: Double): Double = lpf.update(median.update(x), dtSec)
    fun reset() { median.reset(); lpf.reset() }
    val value: Double get() = lpf.value
}
