package com.safedrive.bus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safedrive.bus.core.EventType
import com.safedrive.bus.service.TelemetrySnapshot

/**
 * 기사용 주행 화면.
 *
 * 여기에는 기사가 이해하고 쓸 수 있는 것만 둔다.
 * 좌표계 보정, 센서 상태, 게이트 상세 같은 개발자용 수치는 진단 탭으로 보냈다.
 *
 * 다만 "감지 중 / 보정 중 / 경고 보류" 한 줄은 반드시 남긴다. 이게 없으면 조용한 이유가
 * 운전을 잘해서인지 앱이 못 잡고 있어서인지 구분할 수 없어 앱을 믿을 근거가 사라진다.
 */
@Composable
fun DriveScreen(
    state: TelemetrySnapshot,
    reviewManuallyOpen: Boolean,
    reviewRange: ReviewRange,
    onToggleReview: () -> Unit,
    onReviewRangeChange: (ReviewRange) -> Unit
) {
    val autoOpen = state.serviceRunning && state.review.stopped
    val showReview = autoOpen || reviewManuallyOpen

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.lastError.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BlockRed.copy(alpha = 0.15f))
            ) {
                Text(
                    state.lastError,
                    modifier = Modifier.padding(12.dp),
                    color = BlockRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        NoWarningBanner(state)

        // 속도 카드는 항상 같은 자리에 둔다. 리뷰 패널은 그 아래로 펼쳐지므로
        // 닫기 버튼 위치가 매번 달라지지 않는다.
        SpeedCard(state, onClick = onToggleReview)

        if (showReview) {
            ReviewPanel(
                review = state.review,
                range = reviewRange,
                autoOpened = autoOpen,
                onRangeChange = onReviewRangeChange,
                onClose = onToggleReview
            )
        }

        EventTiles(state)

        Spacer(Modifier.height(24.dp))
    }
}

// ----------------------------------------------------------------------

@Composable
private fun NoWarningBanner(state: TelemetrySnapshot) {
    if (!state.serviceRunning) return
    val hasAny = state.totalWarned > 0
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (hasAny) "마지막 경고 이후" else "경고 없이",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatDuration(state.noWarnDurationMs),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = PassGreen
                )
            }
            Text(
                "%.1f km".format(state.noWarnDistanceM / 1000.0),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SpeedCard(state: TelemetrySnapshot, onClick: () -> Unit) {
    val m = state.motion
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (state.serviceRunning) "%.0f".format(m.fusedSpeedKmh) else "—",
                    fontSize = 60.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (state.serviceRunning && m.speedTrusted) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    }
                )
                Text(
                    " km/h",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Spacer(Modifier.weight(1f))
                StatusPill(state)
            }
            Text(
                "이 영역을 누르면 최근 경고를 되돌아볼 수 있습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 지금 감지가 되고 있는지 세 단어로. 자세한 이유는 진단 탭에 있다. */
@Composable
private fun StatusPill(state: TelemetrySnapshot) {
    val (label, color) = statusOf(state)
    // 색 위에 같은 색 글씨를 얹으면 대비가 부족하다. 배경은 아주 옅게, 글씨는 원색으로 둔다.
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun statusOf(state: TelemetrySnapshot): Pair<String, Color> = when {
    !state.serviceRunning -> "정지됨" to BlockRed
    !state.alignment.aligned ->
        "보정 중 %.0f%%".format(state.alignment.overallProgress * 100) to WarnAmber

    state.gates.warningAllowed -> "감지 중" to PassGreen
    !state.gates.gpsQuality.passed -> "경고 보류 · GPS" to BlockRed
    !state.gates.mountStability.passed -> "경고 보류 · 거치" to BlockRed
    !state.gates.sensorContinuity.passed -> "경고 보류 · 센서" to BlockRed
    else -> "경고 보류" to BlockRed
}

// ----------------------------------------------------------------------

/** 8개 유형을 기사가 아는 6개 묶음으로 보여 준다. */
private data class TileSpec(val label: String, val types: List<EventType>, val danger: Boolean)

private val TILES = listOf(
    TileSpec("급감속", listOf(EventType.HARSH_DECEL), true),
    TileSpec("급정지", listOf(EventType.HARSH_STOP), true),
    TileSpec("급가속", listOf(EventType.HARSH_ACCEL), false),
    TileSpec("급출발", listOf(EventType.HARSH_START), false),
    TileSpec("급회전", listOf(EventType.SHARP_TURN, EventType.SHARP_UTURN), false),
    TileSpec("과속", listOf(EventType.OVERSPEED, EventType.LONG_OVERSPEED), true)
)

@Composable
private fun EventTiles(state: TelemetrySnapshot) {
    val km = state.motion.distanceM / 1000.0
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "이번 운행",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "%.1f km · %s".format(km, formatDuration(state.gaps.sessionDurationMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TILES.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { spec ->
                        EventTile(spec, state, km, Modifier.weight(1f))
                    }
                }
            }

            val suppressed = state.totalEvents - state.totalWarned
            if (suppressed > 0) {
                Text(
                    "%d건은 측정을 신뢰할 수 없어 경고 없이 기록만 했습니다.".format(suppressed),
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber
                )
            }
        }
    }
}

@Composable
private fun EventTile(
    spec: TileSpec,
    state: TelemetrySnapshot,
    km: Double,
    modifier: Modifier = Modifier
) {
    val count = spec.types.sumOf { state.eventCounts[it] ?: 0 }
    val active = count > 0
    val color = when {
        !active -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        spec.danger -> BlockRed
        else -> WarnAmber
    }
    Box(
        modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (active) 1f else 0.4f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column {
            Text(spec.label, style = MaterialTheme.typography.bodyMedium, color = color)
            Text(
                count.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (active) MaterialTheme.colorScheme.onSurface else color
            )
            Text(
                // 1km 미만에서 100km 환산은 과장되므로 표시하지 않는다.
                if (active && km >= 1.0) "%.1f /100km".format(count * 100.0 / km) else "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
