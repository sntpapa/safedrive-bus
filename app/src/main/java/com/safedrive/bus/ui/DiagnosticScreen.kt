package com.safedrive.bus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedrive.bus.core.Constants
import com.safedrive.bus.gate.Gate
import com.safedrive.bus.sensor.GravitySource
import com.safedrive.bus.sensor.SensorInfo
import com.safedrive.bus.service.TelemetrySnapshot

/**
 * 개발자·현장 시험용 진단 화면.
 *
 * 기사에게 필요한 정보가 아니라 "이 앱이 지금 제대로 측정하고 있는가"를 확인하는 화면이다.
 * 주행 탭에서 여기로 옮겨 왔다.
 */
@Composable
fun DiagnosticScreen(state: TelemetrySnapshot) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "진단",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (!state.serviceRunning) {
            Card(
                colors = CardDefaults.cardColors(containerColor = WarnAmber.copy(alpha = 0.15f))
            ) {
                Text(
                    "수집이 정지된 상태입니다. 아래 값은 마지막으로 측정된 값이며 현재 상태가 아닙니다.",
                    modifier = Modifier.padding(12.dp),
                    color = WarnAmber,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // 정지 상태에서 옛 수치가 또렷하게 보이면 현재 값으로 오해하게 된다.
        Column(
            modifier = Modifier.alpha(if (state.serviceRunning) 1f else 0.45f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StationaryCard(state)
            AlignmentCard(state)
            GateCard(state)
            MotionCard(state)
            SpeedLimitCard(state)
            SensorCard(state)
            GapCard(state)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ----------------------------------------------------------------------

/**
 * 정차 판정 진단.
 *
 * 좌표계 보정의 첫 단계는 "정차 3초"인데, 이 조건 중 하나라도 계속 어긋나면
 * 아무리 오래 운행해도 진행률이 0%에서 움직이지 않는다.
 * 무엇이 막고 있는지 추측하지 않도록 조건별 실측값을 그대로 보여 준다.
 */
@Composable
private fun StationaryCard(state: TelemetrySnapshot) {
    val a = state.alignment
    val blocked = a.stationaryBlockedBy
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("정차 판정", Modifier.weight(1f))
                Text(
                    if (blocked.isEmpty()) "조건 충족" else "막힘",
                    color = if (blocked.isEmpty()) PassGreen else BlockRed,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            KeyValue(
                "막는 조건",
                blocked.ifEmpty { "없음" },
                valueColor = if (blocked.isEmpty()) PassGreen else BlockRed
            )
            KeyValue(
                "가속도 크기",
                "%.3f m/s² (기준 %.2f ± %.2f)".format(
                    a.accelMagnitude,
                    Constants.STANDARD_GRAVITY,
                    Constants.STATIONARY_ACCEL_TOLERANCE
                ),
                valueColor = if (kotlin.math.abs(
                        a.accelMagnitude - Constants.STANDARD_GRAVITY
                    ) < Constants.STATIONARY_ACCEL_TOLERANCE
                ) PassGreen else BlockRed
            )
            KeyValue(
                "누적 진동(표준편차)",
                "%.3f (기준 %.2f 이하)".format(a.gravityStdDev, Constants.STATIONARY_ACCEL_STD_MAX),
                valueColor = if (a.gravityStdDev <= Constants.STATIONARY_ACCEL_STD_MAX) {
                    null
                } else {
                    BlockRed
                }
            )
            KeyValue("중력 누적 샘플", "%d개".format(a.gravitySamples))
            KeyValue(
                "정차 속도 기준",
                "%.1f km/h 미만".format(Constants.STATIONARY_SPEED_MPS * 3.6f)
            )
            Text(
                "이 카드가 계속 ‘막힘’이면 보정이 시작되지 않습니다. 막는 조건의 실측값을 보고 " +
                    "Constants.kt의 해당 임계값만 조정하면 됩니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AlignmentCard(state: TelemetrySnapshot) {
    val a = state.alignment
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("좌표계 보정")
            LinearProgressIndicator(
                progress = { a.overallProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = if (a.aligned) PassGreen else WarnAmber
            )
            Text(
                "%s · 진행률 %.0f%%".format(state.gates.alignment.detail, a.overallProgress * 100),
                style = MaterialTheme.typography.bodyMedium
            )
            KeyValue("전방축 샘플", "%d / %d".format(a.forwardSamples, Constants.FWD_MIN_SAMPLES))
            KeyValue("가감속 구간", "%d / %d".format(a.forwardSegments, Constants.FWD_MIN_SEGMENTS))
            KeyValue(
                "방향 집중도(고유값비)",
                if (a.eigenRatio <= 0.0) "-"
                else "%.1f / 기준 %.1f".format(a.eigenRatio, Constants.FWD_MIN_EIGEN_RATIO)
            )
            KeyValue(
                "각도 표준편차",
                if (a.angleStdDeg.isNaN()) "-"
                else "%.1f° / 기준 %.1f°".format(a.angleStdDeg, Constants.FWD_MAX_ANGLE_STD_DEG)
            )
            KeyValue("거치 편차", "%.1f°".format(a.mountDeviationDeg))
            KeyValue("중력 방향 변화율", "%.1f°/s".format(a.mountRateDps))
            if (a.invalidationCount > 0) {
                KeyValue(
                    "재보정 횟수",
                    "%d회 · %s".format(a.invalidationCount, a.lastInvalidationReason),
                    valueColor = WarnAmber
                )
            }
        }
    }
}

@Composable
private fun GateCard(state: TelemetrySnapshot) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("경고 게이트", Modifier.weight(1f))
                Text(
                    if (state.gates.warningAllowed) "경고 가능" else "경고 보류",
                    color = if (state.gates.warningAllowed) PassGreen else BlockRed,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            GateRow("좌표계 보정", state.gates.alignment)
            GateRow("GPS 품질", state.gates.gpsQuality)
            GateRow("센서 연속성", state.gates.sensorContinuity)
            GateRow("거치 안정성", state.gates.mountStability)
            KeyValue(
                "가감속 · 과속 경고",
                if (state.gates.speedJudgementAllowed) "가능" else "보류",
                valueColor = if (state.gates.speedJudgementAllowed) PassGreen else BlockRed
            )
            KeyValue(
                "회전 경고",
                if (state.gates.warningAllowed) "가능" else "보류",
                valueColor = if (state.gates.warningAllowed) PassGreen else BlockRed
            )
            Text(
                "유형별로 필요한 게이트만 봅니다. 가감속·과속은 GPS 품질과 센서 연속성, " +
                    "회전은 4개 전부입니다. 막혀도 판정은 계속되고 이벤트는 기록됩니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GateRow(label: String, gate: Gate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(12.dp)
                .background(if (gate.passed) PassGreen else BlockRed, CircleShape)
        )
        Spacer(Modifier.size(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            gate.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MotionCard(state: TelemetrySnapshot) {
    val m = state.motion
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("차량 좌표계 운동량")
            KeyValue("융합 속도", "%.1f km/h".format(m.fusedSpeedKmh))
            KeyValue(
                "GPS 속도",
                "%.1f km/h · %s".format(
                    m.gpsSpeedKmh,
                    m.gpsSpeedAccuracyMps?.let { "±%.1f m/s".format(it) } ?: "정확도 미제공"
                ),
                valueColor = if (m.speedTrusted) null else BlockRed
            )
            KeyValue(
                "종방향 가속도",
                "%+.2f m/s²  (%+.1f km/h/s)".format(m.longitudinalMps2, m.longitudinalKmhPerSec)
            )
            KeyValue("횡방향 가속도", "%+.2f m/s²".format(m.lateralMps2))
            KeyValue("요레이트(+좌회전)", "%+.1f °/s".format(m.yawRateDps))
            KeyValue("GPS 속도 변화율", "%+.1f km/h/s".format(m.gpsAccelKmhPerSec))
            KeyValue(
                "종가속도 - GPS 차이",
                state.longitudinalErrorKmhPerSec?.let { "%.2f km/h/s (목표 ≤ 1.50)".format(it) }
                    ?: "-",
                valueColor = state.longitudinalErrorKmhPerSec?.let {
                    if (it <= 1.5f) PassGreen else WarnAmber
                }
            )
            KeyValue(
                "경사로 보정",
                if (m.pitchCompensationReliable) "회전벡터 기반 (신뢰)" else "저역통과 대체 (제한적)",
                valueColor = if (m.pitchCompensationReliable) PassGreen else WarnAmber
            )
            KeyValue("주행거리", "%.2f km".format(m.distanceM / 1000.0))
        }
    }
}

@Composable
private fun SpeedLimitCard(state: TelemetrySnapshot) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("제한속도")
            val m = state.speedLimitMatch
            KeyValue(
                "현재 구간",
                state.speedLimitKmh?.let { "%.0f km/h".format(it) } ?: "판정 보류",
                valueColor = if (state.speedLimitKmh == null) WarnAmber else null
            )
            KeyValue(
                "매칭 도로",
                m?.let { "%s · %.0fm".format(it.roadName ?: "이름 없음", it.distanceM) }
                    ?: "매칭된 도로 없음",
                valueColor = if (m == null) WarnAmber else null
            )
            KeyValue(
                "출처",
                m?.source ?: "-",
                valueColor = if (m?.fromCamera == true) PassGreen else null
            )
            if (m?.schoolSuspect == true) {
                KeyValue(
                    "보호구역 의심",
                    "학교 인접 · 판정 보류",
                    valueColor = WarnAmber
                )
            }
            KeyValue(
                "도로 데이터",
                if (state.roadDataReady) state.roadDataSource else "없음",
                valueColor = if (state.roadDataReady) null else BlockRed
            )
            KeyValue("직접 등록 구간", "%d개".format(state.speedZoneCount))
            KeyValue(
                "과속 상태",
                if (state.judge.overspeedActive) {
                    "지속 %d초".format(state.judge.overspeedDurationMs / 1000)
                } else {
                    "없음"
                },
                valueColor = if (state.judge.overspeedActive) BlockRed else null
            )
            KeyValue("매칭 실패 샘플", "%d".format(state.judge.unmatchedLimitSamples))
        }
    }
}

@Composable
private fun SensorCard(state: TelemetrySnapshot) {
    val h = state.health
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("센서 상태")
            KeyValue(
                "실측 샘플링률",
                "가속 %.1fHz / 자이로 %.1fHz / 자세 %.1fHz".format(h.accelHz, h.gyroHz, h.attitudeHz),
                valueColor = if (h.accelHz >= Constants.MIN_ACCEPTABLE_HZ &&
                    h.gyroHz >= Constants.MIN_ACCEPTABLE_HZ
                ) PassGreen else BlockRed
            )
            KeyValue(
                "요청값",
                "%dHz, 배치 %.1f초".format(
                    Constants.TARGET_SENSOR_HZ,
                    Constants.SENSOR_BATCH_LATENCY_US / 1_000_000.0
                )
            )
            SensorInfoRow("가속도계", h.accelInfo)
            SensorInfoRow("자이로스코프", h.gyroInfo)
            SensorInfoRow("자세 센서", h.attitudeInfo)
            KeyValue(
                "중력 산출",
                when (h.gravitySource) {
                    GravitySource.GAME_ROTATION_VECTOR -> "GAME_ROTATION_VECTOR (자기장 미사용)"
                    GravitySource.GRAVITY_SENSOR -> "TYPE_GRAVITY 폴백"
                    GravitySource.ACCEL_LOWPASS -> "가속도계 저역통과 폴백"
                },
                valueColor = if (h.gravitySource == GravitySource.GAME_ROTATION_VECTOR) {
                    PassGreen
                } else {
                    WarnAmber
                }
            )
            KeyValue("정합 대기 큐", "%d 샘플".format(h.pendingAccelSamples))
            KeyValue(
                "정합 오차 프레임",
                "%d 건".format(h.degradedFrames),
                valueColor = if (h.degradedFrames > 0) WarnAmber else null
            )
            KeyValue("GPS 정확도", formatAccuracy(state.motion.gpsAccuracyM))
            KeyValue(
                "GPS 경과",
                if (state.motion.gpsAgeMs == Long.MAX_VALUE) "-"
                else "%.1f초".format(state.motion.gpsAgeMs / 1000.0)
            )
            KeyValue(
                "음성 경고",
                if (state.ttsAvailable) "사용 가능" else "불가 · 진동만",
                valueColor = if (state.ttsAvailable) PassGreen else WarnAmber
            )
            if (h.accelInfo?.isWakeUp == false || h.gyroInfo?.isWakeUp == false) {
                Text(
                    "이 기기에는 wake-up 센서가 없습니다. 화면을 끄면 데이터가 유실될 수 " +
                        "있으니 설정에서 ‘화면 꺼짐 시 수집 강제 유지’를 켜고 감지 중단 " +
                        "시간을 확인하세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber
                )
            }
        }
    }
}

@Composable
private fun SensorInfoRow(label: String, info: SensorInfo?) {
    if (info == null) {
        KeyValue(label, "없음", valueColor = BlockRed)
        return
    }
    val batching = if (info.batchingSupported) {
        "FIFO %d/%d".format(info.fifoReservedEvents, info.fifoMaxEvents)
    } else {
        "FIFO 없음 · 배칭 무효"
    }
    KeyValue(
        label,
        "%s · %s · 최소 %dus".format(
            if (info.isWakeUp) "wake-up" else "non-wakeup",
            batching,
            info.minDelayUs
        ),
        valueColor = if (info.batchingSupported && info.isWakeUp) null else WarnAmber
    )
}

@Composable
private fun GapCard(state: TelemetrySnapshot) {
    val g = state.gaps
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("감지 중단 기록")
            KeyValue(
                "서비스 재시작",
                if (state.sessionRestartCount == 0) "없음"
                else "%d회 · 직전 운행 이어받음".format(state.sessionRestartCount),
                valueColor = if (state.sessionRestartCount == 0) PassGreen else BlockRed
            )
            Text(
                "총 운행 %s 중 %s 감지 중단".format(
                    formatDuration(g.sessionDurationMs),
                    formatDuration(g.totalDataGapMs)
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (g.totalDataGapMs > 0) WarnAmber else PassGreen
            )
            KeyValue(
                "전달 지연 누적",
                formatDuration(g.totalStallMs),
                valueColor = if (g.totalStallMs > 0) WarnAmber else null
            )
            KeyValue("중단 구간", "%d 건".format(g.gapCount))
            KeyValue(
                "현재 상태",
                when {
                    g.inGap -> "끊김 진행 중"
                    g.graceActive -> "복구 유예 중"
                    else -> "정상"
                },
                valueColor = if (g.inGap) BlockRed else if (g.graceActive) WarnAmber else PassGreen
            )
            if (state.sessionRestartCount > 0) {
                Text(
                    "제조사 절전 정책이 수집 서비스를 종료시키고 있습니다. " +
                        "설정에서 배터리 최적화 제외를 다시 확인하세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = BlockRed
                )
            }
            if (g.records.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                g.records.takeLast(5).reversed().forEach { r ->
                    Text(
                        "· %s %s (%s)".format(
                            formatClock(r.startWallMs),
                            formatDuration(r.durationMs),
                            r.kind.name
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 여러 화면이 함께 쓰는 표시 도우미

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatAccuracy(m: Float): String =
    if (m == Float.MAX_VALUE) "-" else "%.1f m".format(m)

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0초"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%d시간 %d분".format(h, m)
        m > 0 -> "%d분 %d초".format(m, s)
        else -> "%d초".format(s)
    }
}

fun formatClock(wallMs: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
    return sdf.format(java.util.Date(wallMs))
}
