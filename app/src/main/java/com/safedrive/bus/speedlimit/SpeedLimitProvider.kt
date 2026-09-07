package com.safedrive.bus.speedlimit

import com.safedrive.bus.data.SpeedZoneDao
import com.safedrive.bus.data.SpeedZoneEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 제한속도 매칭 결과. */
data class SpeedLimitMatch(
    val limitKmh: Double,
    /** 화면에 표시할 출처 한 줄 */
    val source: String,
    val roadName: String? = null,
    /** 매칭된 도로까지의 거리 [m]. 사용자 등록 구간은 중심으로부터의 거리. */
    val distanceM: Double = 0.0,
    /** 단속카메라 기준값을 쓴 경우 true. 노드링크 기본값이면 false. */
    val fromCamera: Boolean = false,
    /**
     * 어린이보호구역 근처인데 제한속도가 40 이상으로 잡힌 구간.
     * 데이터가 실제 규제를 반영하지 못했을 수 있으므로 과속 판정을 보류한다.
     */
    val schoolSuspect: Boolean = false
)

/**
 * 구간 제한속도 조회.
 *
 * 구현체를 갈아끼워도 판정 로직은 손대지 않는다.
 */
interface SpeedLimitProvider {
    /**
     * @param bearingDeg GPS 진행방위. 없으면 null.
     * @param speedKmh 현재 속도. 저속에서는 방위를 신뢰하지 않는다.
     * @return 매칭 실패 시 null. 이 경우 과속 판정을 보류한다.
     */
    fun matchAt(
        latitude: Double,
        longitude: Double,
        bearingDeg: Float?,
        speedKmh: Float
    ): SpeedLimitMatch?

    val sourceName: String
}

/**
 * 여러 출처를 우선순위대로 묻는다.
 *
 * 사용자가 직접 등록한 구간이 공공데이터보다 앞선다. 데이터에 없는 임시 규제나
 * 잘못된 값을 기사가 직접 바로잡을 수 있어야 하기 때문이다.
 */
class CompositeSpeedLimitProvider(
    private val providers: List<SpeedLimitProvider>
) : SpeedLimitProvider {

    override val sourceName: String
        get() = providers.joinToString(" > ") { it.sourceName }

    override fun matchAt(
        latitude: Double,
        longitude: Double,
        bearingDeg: Float?,
        speedKmh: Float
    ): SpeedLimitMatch? {
        for (p in providers) {
            p.matchAt(latitude, longitude, bearingDeg, speedKmh)?.let { return it }
        }
        return null
    }
}

/**
 * 사용자가 등록한 원형 구간.
 *
 * 공공데이터에 없는 예외 구간을 기사가 직접 보완하는 용도다.
 * 조회는 GPS fix마다 일어나므로 DB를 직접 치지 않고 메모리 스냅샷을 본다.
 */
class ManualSpeedLimitProvider(private val dao: SpeedZoneDao) : SpeedLimitProvider {

    @Volatile
    private var zones: List<SpeedZoneEntity> = emptyList()

    override val sourceName: String get() = "직접 등록 ${zones.size}개"

    val zoneCount: Int get() = zones.size

    suspend fun refresh() {
        zones = dao.allOnce()
    }

    override fun matchAt(
        latitude: Double,
        longitude: Double,
        bearingDeg: Float?,
        speedKmh: Float
    ): SpeedLimitMatch? {
        var best: SpeedZoneEntity? = null
        var bestDist = 0.0
        for (z in zones) {
            val d = haversineM(latitude, longitude, z.latitude, z.longitude)
            if (d <= z.radiusM && (best == null || z.radiusM < best.radiusM)) {
                best = z
                bestDist = d
            }
        }
        val z = best ?: return null
        return SpeedLimitMatch(
            limitKmh = z.limitKmh,
            source = "직접 등록 · ${z.name}",
            roadName = z.name,
            distanceM = bestDist
        )
    }
}

internal fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
