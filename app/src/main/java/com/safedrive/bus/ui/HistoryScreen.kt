package com.safedrive.bus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.EventType
import com.safedrive.bus.data.TripEntity
import com.safedrive.bus.data.TripSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFormat = SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREA)

/**
 * 최근 30일 이력과 운행 요약.
 *
 * 게이트가 막혀 경고가 나가지 않은 건도 함께 센다. 그래야 "왜 조용했는지"를 알 수 있다.
 */
@Composable
fun HistoryScreen(
    trips: List<TripEntity>,
    selectedTripId: Long,
    selectedSummary: TripSummary?,
    exportMessage: String,
    canShare: Boolean,
    onSelectTrip: (Long) -> Unit,
    onExportCsv: () -> Unit,
    onShare: () -> Unit,
    onDeleteTrip: (Long) -> Unit,
    onDeleteAll: () -> Unit
) {
    // 삭제는 되돌릴 수 없으므로 반드시 확인을 거친다.
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteTrip by remember { mutableLongStateOf(0L) }

    if (confirmDeleteAll) {
        DeleteDialog(
            title = "이력을 모두 지울까요?",
            body = "지금까지의 운행과 이벤트가 전부 삭제됩니다. 되돌릴 수 없습니다. " +
                "진행 중인 운행은 남습니다.",
            onDismiss = { confirmDeleteAll = false },
            onConfirm = {
                confirmDeleteAll = false
                onDeleteAll()
            }
        )
    }
    if (confirmDeleteTrip != 0L) {
        val target = confirmDeleteTrip
        DeleteDialog(
            title = "이 운행을 지울까요?",
            body = "이 운행과 여기에 기록된 이벤트가 삭제됩니다. 되돌릴 수 없습니다.",
            onDismiss = { confirmDeleteTrip = 0L },
            onConfirm = {
                confirmDeleteTrip = 0L
                onDeleteTrip(target)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "운행 이력",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onExportCsv) { Text("CSV 내보내기") }
        }
        if (trips.isNotEmpty()) {
            // 내보내기 옆이 아니라 아래 한 줄로 둔다. 삭제가 내보내기와 나란히 있으면
            // 잘못 누르기 쉽다.
            OutlinedButton(
                onClick = { confirmDeleteAll = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("이력 모두 삭제") }
        }
        if (canShare) {
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text("내보낸 파일 공유")
            }
        }
        Text(
            "최근 %d일 · %d회 운행".format(Constants.HISTORY_DAYS, trips.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (exportMessage.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    exportMessage,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (selectedSummary != null) {
            SummaryCard(selectedSummary)
            // 진행 중인 운행은 서비스가 쓰고 있으므로 삭제 버튼을 내지 않는다.
            if (selectedSummary.trip.endedAtMs != null) {
                OutlinedButton(
                    onClick = { confirmDeleteTrip = selectedSummary.trip.id },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("이 운행 삭제") }
            }
        }

        if (trips.isEmpty()) {
            Card {
                Text(
                    "아직 기록된 운행이 없습니다. 수집을 시작하면 여기에 쌓입니다.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            trips.forEach { t ->
                TripRow(t, selected = t.id == selectedTripId) { onSelectTrip(t.id) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 삭제 확인. 지우는 대상과 되돌릴 수 없다는 사실을 함께 밝힌다. */
@Composable
private fun DeleteDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text("삭제", maxLines = 1) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("취소", maxLines = 1) }
        }
    )
}

@Composable
private fun TripRow(trip: TripEntity, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                dayFormat.format(Date(trip.startedAtMs)),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            val end = trip.endedAtMs
            Text(
                buildString {
                    append("%.1f km".format(trip.distanceM / 1000.0))
                    append(" · ")
                    append(
                        if (end == null) "진행 중"
                        else formatDuration(end - trip.startedAtMs)
                    )
                    if (trip.dataGapMs > 0) {
                        append(" · 감지 중단 ")
                        append(formatDuration(trip.dataGapMs))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryCard(s: TripSummary) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(dayFormat.format(Date(s.trip.startedAtMs)) + " 운행 요약")

            KeyValue("주행거리", "%.2f km".format(s.distanceKm))
            KeyValue("운행시간", formatDuration(s.durationMs))
            KeyValue(
                "감지 중단",
                formatDuration(s.trip.dataGapMs) + " · %d구간".format(s.trip.gapCount),
                valueColor = if (s.trip.dataGapMs > 0) WarnAmber else PassGreen
            )
            KeyValue("전달 지연", formatDuration(s.trip.stallMs))
            // 프레임 카운터를 그대로 보여 주면 "765,570 샘플" 같은 수가 뜨는데
            // 기사도 개발자도 그 크기를 해석할 수 없다. 시도 대비 비율로 보여 준다.
            val limitTotal = s.trip.limitSampleTotal
            KeyValue(
                "과속 판정 보류",
                if (limitTotal <= 0L) "-"
                else "%.0f%% 구간".format(100.0 * s.trip.unmatchedLimitSamples / limitTotal),
                valueColor = if (s.trip.unmatchedLimitSamples > 0) WarnAmber else null
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "유형별 건수 (100km 환산)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            if (s.totalEvents == 0) {
                Text(
                    "걸린 항목이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PassGreen
                )
            } else {
                EventType.entries.forEach { t ->
                    val n = s.countOf(t)
                    if (n > 0) {
                        val warned = s.warnedOf(t)
                        val rate = s.per100km(t)
                        KeyValue(
                            t.label,
                            buildString {
                                append("%d건".format(n))
                                if (rate != null) append(" · %.1f/100km".format(rate))
                                if (warned != n) append(" · 경고 %d".format(warned))
                            }
                        )
                    }
                }
                KeyValue(
                    "합계",
                    "%d건 · 경고 %d건".format(s.totalEvents, s.totalWarned),
                    valueColor = WarnAmber
                )
            }

            if (s.distanceKm < 1.0) {
                Text(
                    "주행거리가 1km 미만이라 100km 환산은 표시하지 않습니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                "이 값은 한국교통안전공단 eTAS의 공식 판정이 아니라 휴대폰 센서로 계산한 " +
                    "추정치입니다. 차량 DTG 단말과 결과가 다를 수 있습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = WarnAmber
            )
        }
    }
}
