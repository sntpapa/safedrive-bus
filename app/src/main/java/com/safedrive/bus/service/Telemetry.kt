package com.safedrive.bus.service

import com.safedrive.bus.align.AlignmentSnapshot
import com.safedrive.bus.alert.AlertState
import com.safedrive.bus.core.EventType
import com.safedrive.bus.diag.GapSummary
import com.safedrive.bus.fusion.MotionSnapshot
import com.safedrive.bus.gate.GateSnapshot
import com.safedrive.bus.judge.JudgeStats
import com.safedrive.bus.judge.ReviewState
import com.safedrive.bus.sensor.SensorHealth
import com.safedrive.bus.speedlimit.SpeedLimitMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TelemetrySnapshot(
    val serviceRunning: Boolean = false,
    val startedAtWallMs: Long = 0L,
    val tripId: Long = 0L,
    val motion: MotionSnapshot = MotionSnapshot(),
    val alignment: AlignmentSnapshot = AlignmentSnapshot(),
    val health: SensorHealth = SensorHealth(),
    val gates: GateSnapshot = GateSnapshot.INITIAL,
    val gaps: GapSummary = GapSummary(0, 0, 0, 0, false, false, emptyList()),
    /** 1단계 검증용: 종가속도와 GPS 속도변화율의 차이 [km/h/s] */
    val longitudinalErrorKmhPerSec: Float? = null,
    val diagnosticRecording: Boolean = false,
    val lastError: String = "",

    /** 현재 화면에 띄울 경고. 화면은 보조 수단이므로 없을 때가 정상이다. */
    val alert: AlertState = AlertState(),
    /** TTS 사용 가능 여부. 불가하면 진동만 나간다. */
    val ttsAvailable: Boolean = false,
    /** 과속 판정에 실제로 쓰는 제한속도. null이면 판정 보류. */
    val speedLimitKmh: Double? = null,
    /** 매칭된 도로 정보. 진단 화면 표시용. */
    val speedLimitMatch: SpeedLimitMatch? = null,
    /** 도로 데이터(표준노드링크) 사용 가능 여부 */
    val roadDataReady: Boolean = false,
    val roadDataSource: String = "",
    val speedZoneCount: Int = 0,
    val judge: JudgeStats = JudgeStats(),
    /** 이번 운행의 유형별 누적 건수 (경고 발생 + 보류 모두 포함) */
    val eventCounts: Map<EventType, Int> = emptyMap(),
    /** 이번 운행에서 실제로 경고가 나간 건수 */
    val warnedCounts: Map<EventType, Int> = emptyMap(),
    /** 정차 리뷰 상태. 정지 3초가 지나면 화면이 자동으로 리뷰로 바뀐다. */
    val review: ReviewState = ReviewState(),
    /** 마지막 경고 이후 경과 시간 [ms]. 경고가 없었으면 운행 시작부터. */
    val noWarnDurationMs: Long = 0L,
    /** 마지막 경고 이후 주행한 거리 [m]. */
    val noWarnDistanceM: Double = 0.0,
    /**
     * 이번 운행에서 수집 서비스가 되살아난 횟수.
     * 0이 아니면 제조사 절전 정책이 서비스를 종료시키고 있다는 뜻이다.
     */
    val sessionRestartCount: Int = 0
) {
    val totalEvents: Int get() = eventCounts.values.sum()
    val totalWarned: Int get() = warnedCounts.values.sum()
}

/**
 * 서비스와 UI 사이의 단일 상태 채널.
 *
 * 서비스가 프로세스 내에서 상태를 갱신하고 UI는 읽기만 한다.
 */
object Telemetry {
    private val _state = MutableStateFlow(TelemetrySnapshot())
    val state: StateFlow<TelemetrySnapshot> = _state.asStateFlow()

    fun update(block: (TelemetrySnapshot) -> TelemetrySnapshot) {
        _state.value = block(_state.value)
    }

    fun reset() {
        _state.value = TelemetrySnapshot()
    }
}
