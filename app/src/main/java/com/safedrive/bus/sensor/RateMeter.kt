package com.safedrive.bus.sensor

import com.safedrive.bus.core.Constants

/**
 * 실측 샘플링률 계산기.
 *
 * 중요: 도착 시각(System clock)이 아니라 SensorEvent.timestamp(이벤트 발생 시각)를 쓴다.
 * 배칭을 켜면 이벤트가 1초 단위로 몰려서 도착하므로 도착 시각으로 계산하면 값이 무의미하다.
 */
class RateMeter(private val windowNs: Long = Constants.RATE_WINDOW_NS) {
    private val ts = ArrayDeque<Long>()
    var totalEvents: Long = 0L
        private set
    var lastEventTsNs: Long = 0L
        private set

    fun onEvent(eventTsNs: Long) {
        totalEvents++
        lastEventTsNs = eventTsNs
        ts.addLast(eventTsNs)
        while (ts.size > 1 && eventTsNs - ts.first() > windowNs) ts.removeFirst()
    }

    /** 윈도우 내 실측 Hz. 샘플이 부족하면 0. */
    val hz: Double
        get() {
            if (ts.size < 2) return 0.0
            val spanSec = (ts.last() - ts.first()) / 1e9
            return if (spanSec <= 0.0) 0.0 else (ts.size - 1) / spanSec
        }

    fun reset() { ts.clear() }
}
