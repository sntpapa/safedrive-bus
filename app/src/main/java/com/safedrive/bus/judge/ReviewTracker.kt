package com.safedrive.bus.judge

import com.safedrive.bus.core.Constants

/**
 * 정차 리뷰 상태.
 *
 * 기사가 "방금 그게 경고였나?"를 정류소나 신호 대기 중에 확인할 수 있게 하기 위한 것이다.
 * 운행이 끝나야만 알 수 있으면 자기점검 도구로서 반쪽이 된다.
 */
data class ReviewState(
    /** 3초 이상 완전 정지 상태인지. true면 화면이 자동으로 리뷰로 바뀐다. */
    val stopped: Boolean = false,
    /** 정지가 시작된 시각. 0이면 정지 아님. */
    val stoppedSinceWallMs: Long = 0L,
    /** 직전 정차에서 출발한 시각. "방금 구간"의 시작점. */
    val segmentStartWallMs: Long = 0L,
    /** 최근 10분 이내 이벤트. 최신이 앞. */
    val recent: List<DrivingEvent> = emptyList()
) {
    /** 지정한 분(minute) 안에 들어온 이벤트. U턴에 흡수된 급좌우회전은 제외한다. */
    fun within(minutes: Int, nowMs: Long): List<DrivingEvent> {
        val from = nowMs - minutes * 60_000L
        return recent.filter { it.wallMs >= from }
    }

    /** 직전 출발 이후의 이벤트. 구간 시작점이 없으면 최근 5분으로 대신한다. */
    fun sinceDeparture(nowMs: Long): List<DrivingEvent> {
        val from = if (segmentStartWallMs != 0L) segmentStartWallMs else nowMs - 5 * 60_000L
        return recent.filter { it.wallMs >= from }
    }

    fun segmentDurationMs(nowMs: Long): Long =
        if (segmentStartWallMs == 0L) 0L else nowMs - segmentStartWallMs
}

/**
 * 정지/출발 전환 감지 + 최근 이벤트 보관.
 *
 * 서비스 코루틴에서만 호출한다. 판정기와 달리 센서 스레드에서 돌 필요가 없다.
 */
class ReviewTracker {

    private val recent = ArrayDeque<DrivingEvent>()

    private var stopped = false
    private var stoppedSinceWallMs = 0L
    private var belowSinceWallMs = 0L
    private var segmentStartWallMs = 0L

    fun reset() {
        recent.clear()
        stopped = false
        stoppedSinceWallMs = 0L
        belowSinceWallMs = 0L
        segmentStartWallMs = 0L
    }

    fun add(event: DrivingEvent) {
        recent.addFirst(event)
        prune(event.wallMs)
    }

    /**
     * 속도로 정지/출발을 판정한다.
     *
     * 정지 임계값(2km/h)과 출발 임계값(5km/h)을 다르게 두어 경계에서 상태가 떨리지 않게 한다.
     */
    fun onSpeed(speedKmh: Float, nowMs: Long) {
        prune(nowMs)
        if (speedKmh < Constants.REVIEW_STOP_SPEED_KMH) {
            if (belowSinceWallMs == 0L) belowSinceWallMs = nowMs
            if (!stopped && nowMs - belowSinceWallMs >= Constants.REVIEW_STOP_SUSTAIN_MS) {
                stopped = true
                stoppedSinceWallMs = belowSinceWallMs
            }
        } else {
            belowSinceWallMs = 0L
            if (stopped && speedKmh >= Constants.REVIEW_DEPART_SPEED_KMH) {
                stopped = false
                stoppedSinceWallMs = 0L
                // 여기가 "방금 구간"의 시작점이 된다.
                segmentStartWallMs = nowMs
            }
        }
    }

    fun state(): ReviewState = ReviewState(
        stopped = stopped,
        stoppedSinceWallMs = stoppedSinceWallMs,
        segmentStartWallMs = segmentStartWallMs,
        recent = ArrayList(recent)
    )

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - Constants.REVIEW_RETENTION_MS
        while (recent.isNotEmpty() && recent.last().wallMs < cutoff) recent.removeLast()
        while (recent.size > Constants.REVIEW_MAX_EVENTS) recent.removeLast()
    }
}
