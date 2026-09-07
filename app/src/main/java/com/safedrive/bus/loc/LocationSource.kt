package com.safedrive.bus.loc

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.safedrive.bus.core.Constants

data class GpsSample(
    val latitude: Double,
    val longitude: Double,
    /** 수평 정확도 [m]. 없으면 Float.MAX_VALUE */
    val accuracyM: Float,
    /** 절대 속도 [m/s]. 없으면 null */
    val speedMps: Float?,
    /** 속도 정확도 [m/s]. API 26+ 에서만 제공. */
    val speedAccuracyMps: Float?,
    /** 진행 방위 [deg, 0=북]. 도로 링크 방위와 비교해 반대 차선을 걸러낸다. 없으면 null. */
    val bearingDeg: Float?,
    /** elapsedRealtime 기준 수신 시각 [ms] */
    val elapsedMs: Long,
    val wallMs: Long
) {
    fun ageMs(): Long = SystemClock.elapsedRealtime() - elapsedMs
    fun isFresh(): Boolean = ageMs() <= Constants.GPS_MAX_AGE_MS
}

/**
 * 1Hz 위치 수집.
 *
 * 주의: API 31부터 사용자가 "대략 위치"만 허용할 수 있고, 그 경우 accuracy가 수백 m가 되어
 * GPS 품질 게이트가 상시 차단된다. 정밀 위치 부여 여부는 서비스 시작 전에 별도로 검사한다.
 */
class LocationSource(
    context: Context,
    private val onSample: (GpsSample) -> Unit
) {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (callback != null) return true
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            Constants.LOCATION_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(Constants.LOCATION_INTERVAL_MS)
            .setMaxUpdateDelayMillis(Constants.LOCATION_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onSample(it.toSample()) }
            }
        }
        callback = cb
        return try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
            true
        } catch (se: SecurityException) {
            callback = null
            false
        }
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    private fun Location.toSample(): GpsSample {
        // Location.elapsedRealtimeNanos는 위치가 "측정된" 시각이다. 콜백 도착 시각이 아니라
        // 이 값을 써야 지연을 정확히 반영할 수 있다.
        val elapsed = elapsedRealtimeNanos / 1_000_000L
        return GpsSample(
            latitude = latitude,
            longitude = longitude,
            accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
            speedMps = if (hasSpeed()) speed else null,
            // minSdk 26 이므로 hasSpeedAccuracy()는 항상 사용할 수 있다.
            speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
            bearingDeg = if (hasBearing()) bearing else null,
            elapsedMs = elapsed,
            wallMs = System.currentTimeMillis()
        )
    }
}
