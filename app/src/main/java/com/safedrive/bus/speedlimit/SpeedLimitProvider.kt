package com.safedrive.bus.speedlimit

import com.safedrive.bus.data.SpeedZoneDao
import com.safedrive.bus.data.SpeedZoneEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 구간 제한속도 조회.
 *
 * 지금은 사용자가 직접 등록한 구간만 쓴다. 외부 제한속도 데이터를 붙일 때는
 * 이 인터페이스의 다른 구현체로 갈아끼우면 되고 판정 로직은 손대지 않는다.
 */
interface SpeedLimitProvider {
    /** @return 제한속도 [km/h]. 매칭 실패 시 null이며, 이 경우 과속 판정을 보류한다. */
    fun limitAt(latitude: Double, longitude: Double): Double?

    /** 화면에 표시할 출처 이름. */
    val sourceName: String
}

/**
 * 사용자가 등록한 원형 구간 기반 구현체.
 *
 * 구간이 겹치면 반경이 작은 쪽(더 구체적인 구간)을 우선한다.
 * 조회는 센서 스레드에서 매 GPS fix마다 일어나므로 DB를 직접 치지 않고
 * 메모리 스냅샷을 본다. 스냅샷 갱신은 서비스가 별도로 호출한다.
 */
class ManualSpeedLimitProvider(private val dao: SpeedZoneDao) : SpeedLimitProvider {

    @Volatile
    private var zones: List<SpeedZoneEntity> = emptyList()

    override val sourceName: String get() = "사용자 등록 구간 ${zones.size}개"

    val zoneCount: Int get() = zones.size

    suspend fun refresh() {
        zones = dao.allOnce()
    }

    override fun limitAt(latitude: Double, longitude: Double): Double? {
        var best: SpeedZoneEntity? = null
        for (z in zones) {
            if (haversineM(latitude, longitude, z.latitude, z.longitude) <= z.radiusM) {
                if (best == null || z.radiusM < best.radiusM) best = z
            }
        }
        return best?.limitKmh
    }

    private fun haversineM(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
