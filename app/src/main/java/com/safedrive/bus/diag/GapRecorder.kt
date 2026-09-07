package com.safedrive.bus.diag

import android.os.SystemClock
import com.safedrive.bus.core.Constants

enum class GapKind {
    /** 연속한 센서 이벤트 타임스탬프 사이가 비었다. 센서 HAL 레벨의 데이터 손실. */
    DATA_GAP,

    /** 이벤트는 이어져 있으나 전달이 멈췄다. 서비스 정지/Doze/제조사 최적화 의심 구간. */
    DELIVERY_STALL
}

data class GapRecord(
    val kind: GapKind,
    /** 벽시계 기준 시작/종료 (표시용) */
    val startWallMs: Long,
    val endWallMs: Long,
    val durationMs: Long
)

/**
 * 센서 끊김 구간 기록기.
 *
 * 두 가지 끊김을 구분해서 잡는다.
 *  - DATA_GAP: 프레임 타임스탬프가 3초 이상 점프. 배칭 때문에 "도착 간격"으로는 잡을 수 없다.
 *  - DELIVERY_STALL: 배치 지연 + 3초가 지나도 아무 프레임도 오지 않음. 서비스가 죽었거나
 *    제조사 배터리 최적화가 개입한 경우.
 *
 * 스레드: onFrame은 센서 스레드, tick은 서비스 코루틴에서 호출되므로 synchronized로 보호한다.
 */
class GapRecorder {

    private val lock = Any()
    private val gaps = ArrayList<GapRecord>()

    private var sessionStartElapsedMs = 0L
    private var lastFrameTsNs = 0L
    private var lastArrivalElapsedMs = 0L
    private var stallOpenedAtElapsedMs = 0L

    /** 끊김 복구 후 이 시각까지는 경고를 유예한다(elapsedRealtime 기준). */
    @Volatile
    var graceUntilElapsedMs: Long = 0L
        private set

    /**
     * 실제로 센서 데이터가 없었던 시간. "감지 중단"의 정의는 이것이다.
     * 전달 정지(DELIVERY_STALL)와 합산하지 않는다. wake-up 센서 + FIFO 환경에서는
     * 전달이 멈춰도 데이터는 버퍼에 남아 있다가 나중에 도착하므로, 두 값을 더하면
     * 같은 구간을 두 번 세게 된다.
     */
    @Volatile
    var totalDataGapMs: Long = 0L
        private set

    /** 데이터는 남아 있었지만 전달이 멈춰 있던 시간. 실시간 경고 지연의 지표다. */
    @Volatile
    var totalStallMs: Long = 0L
        private set

    @Volatile
    var inGap: Boolean = false
        private set

    fun start() = synchronized(lock) {
        gaps.clear()
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        lastFrameTsNs = 0L
        lastArrivalElapsedMs = sessionStartElapsedMs
        stallOpenedAtElapsedMs = 0L
        totalDataGapMs = 0L
        totalStallMs = 0L
        inGap = false
        // 시작 직후에는 센서/GPS가 안정되지 않았으므로 유예를 준다.
        graceUntilElapsedMs = sessionStartElapsedMs + Constants.POST_GAP_GRACE_MS
    }

    /** 정합된 프레임이 나올 때마다 호출. */
    fun onFrame(timestampNs: Long) = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()

        // 1) 이벤트 타임스탬프 상의 공백 = 실제 데이터 손실
        var dataGapMs = 0L
        if (lastFrameTsNs != 0L) {
            val gapMs = (timestampNs - lastFrameTsNs) / 1_000_000L
            if (gapMs >= Constants.SENSOR_GAP_MS) {
                dataGapMs = gapMs
                totalDataGapMs += gapMs
                record(GapKind.DATA_GAP, now - gapMs, now, gapMs)
            }
        }
        lastFrameTsNs = timestampNs

        // 2) 전달 정지 구간이 열려 있었다면 닫는다.
        //    같은 구간에서 데이터 손실이 이미 잡혔다면 그만큼은 빼고 센다.
        if (stallOpenedAtElapsedMs != 0L) {
            val dur = now - stallOpenedAtElapsedMs
            totalStallMs += (dur - dataGapMs).coerceAtLeast(0L)
            record(GapKind.DELIVERY_STALL, stallOpenedAtElapsedMs, now, dur)
            stallOpenedAtElapsedMs = 0L
        }
        lastArrivalElapsedMs = now
        inGap = false
    }

    /** 워치독에서 주기적으로 호출. 프레임이 안 오는 상황은 onFrame으로 잡을 수 없다. */
    fun tick() = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        val batchMs = Constants.SENSOR_BATCH_LATENCY_US / 1000L
        val silence = now - lastArrivalElapsedMs
        if (silence >= batchMs + Constants.SENSOR_GAP_MS) {
            if (stallOpenedAtElapsedMs == 0L) {
                // 침묵이 시작된 시점을 소급해 기록 시작점으로 잡는다.
                stallOpenedAtElapsedMs = lastArrivalElapsedMs
            }
            inGap = true
        }
    }

    private fun record(kind: GapKind, startElapsed: Long, endElapsed: Long, durationMs: Long) {
        graceUntilElapsedMs = endElapsed + Constants.POST_GAP_GRACE_MS
        if (gaps.size < Constants.MAX_GAP_RECORDS) {
            val wallNow = System.currentTimeMillis()
            val elapsedNow = SystemClock.elapsedRealtime()
            gaps.add(
                GapRecord(
                    kind = kind,
                    startWallMs = wallNow - (elapsedNow - startElapsed),
                    endWallMs = wallNow - (elapsedNow - endElapsed),
                    durationMs = durationMs
                )
            )
        }
    }

    fun snapshot(): GapSummary = synchronized(lock) {
        val elapsed = if (sessionStartElapsedMs == 0L) 0L
        else SystemClock.elapsedRealtime() - sessionStartElapsedMs
        GapSummary(
            sessionDurationMs = elapsed,
            totalDataGapMs = totalDataGapMs,
            totalStallMs = totalStallMs,
            gapCount = gaps.size,
            inGap = inGap,
            graceActive = SystemClock.elapsedRealtime() < graceUntilElapsedMs,
            records = ArrayList(gaps)
        )
    }
}

data class GapSummary(
    val sessionDurationMs: Long,
    /** 실제 데이터가 없었던 시간. 운행 요약의 "감지 중단"은 이 값을 쓴다. */
    val totalDataGapMs: Long,
    /** 데이터는 남아 있었으나 전달이 멈춰 있던 시간. */
    val totalStallMs: Long,
    val gapCount: Int,
    val inGap: Boolean,
    val graceActive: Boolean,
    val records: List<GapRecord>
)
