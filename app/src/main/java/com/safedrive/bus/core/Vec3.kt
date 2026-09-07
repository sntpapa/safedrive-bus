package com.safedrive.bus.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** 3차원 벡터. 센서 콜백에서 초당 수백 번 생성되므로 가볍게 유지한다. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    val norm: Float get() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val n = norm
        return if (n < 1e-6f) ZERO else Vec3(x / n, y / n, z / n)
    }

    /** this 에서 axis 방향 성분을 제거한 벡터(axis 는 단위벡터여야 한다). */
    fun rejectFrom(axis: Vec3): Vec3 = this - axis * (this dot axis)

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)

        fun of(a: FloatArray) = Vec3(a[0], a[1], a[2])

        /** 주어진 단위벡터에 수직인 임의의 단위벡터 하나를 만든다. */
        fun anyPerpendicular(u: Vec3): Vec3 {
            // u와 가장 정렬이 덜 된 기본 축을 골라 외적하면 수치적으로 안정적이다.
            val seed = if (abs(u.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
            return (seed - u * (seed dot u)).normalized()
        }
    }
}

/** 두 벡터 사이 각도(도). */
fun angleBetweenDeg(a: Vec3, b: Vec3): Float {
    val na = a.normalized()
    val nb = b.normalized()
    val c = (na dot nb).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(c.toDouble())).toFloat()
}

fun Double.msToKmh(): Double = this * 3.6
fun Float.mpsToKmh(): Float = this * 3.6f
