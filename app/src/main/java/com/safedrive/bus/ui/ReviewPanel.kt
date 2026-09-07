package com.safedrive.bus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safedrive.bus.core.EventType
import com.safedrive.bus.core.SuppressReason
import com.safedrive.bus.judge.DrivingEvent
import com.safedrive.bus.judge.ReviewState
import kotlin.math.abs

/** 되돌아볼 범위. */
enum class ReviewRange(val label: String) {
    SEGMENT("방금 구간"),
    M1("1분"),
    M3("3분"),
    M5("5분")
}

/**
 * 정차 리뷰 패널.
 *
 * 정지 3초가 지나면 자동으로 뜨고, 출발하면 사라진다.
 * 주행 중에도 속도 영역을 누르면 수동으로 열 수 있다.
 */
@Composable
fun ReviewPanel(
    review: ReviewState,
    range: ReviewRange,
    autoOpened: Boolean,
    onRangeChange: (ReviewRange) -> Unit,
    onClose: () -> Unit
) {
    val now = System.currentTimeMillis()
    val events = when (range) {
        ReviewRange.SEGMENT -> review.sinceDeparture(now)
        ReviewRange.M1 -> review.within(1, now)
        ReviewRange.M3 -> review.within(3, now)
        ReviewRange.M5 -> review.within(5, now)
    }.filter { it.suppressReason != SuppressReason.ABSORBED_BY_UTURN }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (autoOpened) "정차 중 · 방금 운행 되돌아보기" else "최근 경고 되돌아보기",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (range == ReviewRange.SEGMENT) {
                            "직전 정차 이후 " + formatSpan(review.segmentDurationMs(now))
                        } else {
                            "최근 " + range.label
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onClose) { Text("닫기") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewRange.entries.forEach { r ->
                    RangeChip(r.label, r == range) { onRangeChange(r) }
                }
            }

            if (events.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 10.dp)
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(PassGreen, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "이 구간에 걸린 항목 없음",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                events.take(8).forEach { e -> ReviewRow(e, now) }
                if (events.size > 8) {
                    Text(
                        "외 %d건".format(events.size - 8),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (autoOpened) {
                Text(
                    "출발하면 자동으로 주행 화면으로 돌아갑니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReviewRow(e: DrivingEvent, nowMs: Long) {
    val color = severityColor(e)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.type.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    magnitudeText(e),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                buildString {
                    append(relativeTime(nowMs - e.wallMs))
                    append(" · ")
                    append("%.0f km/h".format(e.speedKmh))
                    if (!e.warned) {
                        append(" · 경고 안 나감(")
                        append(e.suppressReason.label)
                        append(")")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (e.warned) "경고" else "기록만",
            style = MaterialTheme.typography.labelSmall,
            color = if (e.warned) color else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun severityColor(e: DrivingEvent): Color = when (e.type) {
    EventType.HARSH_DECEL, EventType.HARSH_STOP,
    EventType.OVERSPEED, EventType.LONG_OVERSPEED -> BlockRed

    else -> WarnAmber
}

private fun magnitudeText(e: DrivingEvent): String = when (e.type) {
    EventType.SHARP_TURN, EventType.SHARP_UTURN ->
        "%s %.0f°".format(e.turnDirection.label, abs(e.turnAngleDeg))

    EventType.OVERSPEED, EventType.LONG_OVERSPEED ->
        e.speedLimitKmh?.let { "제한 %.0f 초과".format(it) } ?: "-"

    else -> "%.1f km/h/s".format(abs(e.peakKmhPerSec))
}

private fun relativeTime(agoMs: Long): String {
    val sec = agoMs / 1000
    return when {
        sec < 60 -> "%d초 전".format(sec)
        sec < 3600 -> "%d분 %d초 전".format(sec / 60, sec % 60)
        else -> "%d시간 전".format(sec / 3600)
    }
}

private fun formatSpan(ms: Long): String {
    if (ms <= 0) return "-"
    val sec = ms / 1000
    return if (sec < 60) "%d초".format(sec) else "%d분 %d초".format(sec / 60, sec % 60)
}
