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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.safedrive.bus.core.Constants
import com.safedrive.bus.data.SpeedZoneEntity
import com.safedrive.bus.service.TelemetrySnapshot

/**
 * 설정 탭.
 *
 * 주행 화면에는 시작/정지 버튼을 두지 않는다. 운전 중 오터치로 수집이 끊기면
 * 그 구간이 통째로 사라지기 때문이다. 정지는 상단바 알림과 이 화면에서만 할 수 있다.
 */
@Composable
fun SettingsScreen(
    state: TelemetrySnapshot,
    autoStart: Boolean,
    diagnosticRecording: Boolean,
    keepAwake: Boolean,
    zones: List<SpeedZoneEntity>,
    onToggleAutoStart: (Boolean) -> Unit,
    onToggleDiagnostic: (Boolean) -> Unit,
    onToggleKeepAwake: (Boolean) -> Unit,
    textScale: Float,
    onTextScaleChange: (Float) -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onAddZone: (name: String, lat: Double, lon: Double, radiusM: Double, limitKmh: Double) -> Unit,
    onDeleteZone: (SpeedZoneEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("설정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("수집 상태")
                        Text(
                            if (state.serviceRunning) "동작 중" else "정지됨",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.serviceRunning) PassGreen else BlockRed
                        )
                    }
                    if (state.serviceRunning) {
                        Button(
                            onClick = onStopService,
                            colors = ButtonDefaults.buttonColors(containerColor = BlockRed)
                        ) { Text("정지") }
                    } else {
                        Button(onClick = onStartService) { Text("시작") }
                    }
                }
                Text(
                    "상단바 알림의 ‘정지’로도 멈출 수 있습니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("수집 설정")
                ToggleRow(
                    title = "앱을 열면 자동으로 시작",
                    subtitle = "끄면 이 화면에서 직접 시작해야 합니다.",
                    checked = autoStart,
                    onChange = onToggleAutoStart
                )
                ToggleRow(
                    title = "진단 모드 (자이로 원시값 저장)",
                    subtitle = "평상시에는 저장하지 않습니다. 켜면 기기 내부 CSV로만 남습니다.",
                    checked = diagnosticRecording,
                    onChange = onToggleDiagnostic
                )
                ToggleRow(
                    title = "화면 꺼짐 시 수집 강제 유지",
                    subtitle = "wake-up 센서로 충분하면 꺼두세요. 켜면 배터리 소모가 늘어납니다.",
                    checked = keepAwake,
                    onChange = onToggleKeepAwake
                )
                if (state.serviceRunning) {
                    Text(
                        "진단 모드와 화면 꺼짐 설정은 수집을 다시 시작해야 적용됩니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber
                    )
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("글자 크기")
                Text(
                    "기본값은 대부분의 기기에서 문구가 한 줄에 들어가도록 맞춰 두었습니다. " +
                        "화면이 작거나 시스템 글꼴이 크면 줄바꿈이 생길 수 있으니 그때 줄이세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TEXT_SCALES.forEach { (label, value) ->
                        ScaleChip(
                            label = label,
                            selected = kotlin.math.abs(textScale - value) < 0.01f,
                            modifier = Modifier.weight(1f)
                        ) { onTextScaleChange(value) }
                    }
                }
            }
        }

        SpeedZoneSection(
            zones = zones,
            roadDataReady = state.roadDataReady,
            roadDataSource = state.roadDataSource,
            currentLatitude = state.motion.latitude,
            currentLongitude = state.motion.longitude,
            hasFix = state.motion.latitude != 0.0 || state.motion.longitude != 0.0,
            onAdd = onAddZone,
            onDelete = onDeleteZone
        )

        DisclaimerCard()
        Spacer(Modifier.height(24.dp))
    }
}

private val TEXT_SCALES = listOf(
    "아주 작게" to 0.80f,
    "작게" to 0.90f,
    "보통" to 1.00f,
    "크게" to 1.15f
)

@Composable
private fun ScaleChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 노선별 구간 제한속도 등록.
 *
 * 원형 지오펜스(중심 좌표 + 반경)로 단순화했다. 기사가 해당 구간에 서 있을 때
 * 현재 위치를 그대로 중심으로 찍는 방식이다.
 * 외부 제한속도 데이터를 붙일 때는 SpeedLimitProvider 구현만 교체하면 된다.
 */
@Composable
private fun SpeedZoneSection(
    zones: List<SpeedZoneEntity>,
    roadDataReady: Boolean,
    roadDataSource: String,
    currentLatitude: Double,
    currentLongitude: Double,
    hasFix: Boolean,
    onAdd: (name: String, lat: Double, lon: Double, radiusM: Double, limitKmh: Double) -> Unit,
    onDelete: (SpeedZoneEntity) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("50") }
    var radius by remember {
        mutableStateOf(Constants.SPEED_ZONE_DEFAULT_RADIUS_M.toInt().toString())
    }
    var error by remember { mutableStateOf("") }
    // 도로 데이터가 대부분을 자동으로 채우므로 등록 폼은 평소에 접어 둔다.
    var expanded by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("구간 제한속도 · 직접 등록 %d개".format(zones.size))
            Text(
                if (roadDataReady) {
                    "제한속도는 표준노드링크와 무인교통단속카메라 데이터로 자동 적용됩니다. " +
                        "여기 등록한 구간은 그보다 우선합니다. 데이터가 틀린 곳만 보완하세요."
                } else {
                    "도로 데이터를 불러오지 못했습니다. 여기 등록한 구간에서만 과속을 판정합니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (roadDataReady) MaterialTheme.colorScheme.onSurfaceVariant else BlockRed
            )
            KeyValue(
                "도로 데이터",
                if (roadDataReady) roadDataSource else "없음",
                valueColor = if (roadDataReady) PassGreen else BlockRed
            )

            if (zones.isNotEmpty()) {
                zones.forEach { z ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(z.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "%.0f km/h · 반경 %.0fm".format(z.limitKmh, z.radiusM),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onDelete(z) }) {
                            Text("삭제", color = BlockRed, maxLines = 1)
                        }
                    }
                }
            }

            if (!expanded) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("데이터가 틀린 구간 직접 등록", maxLines = 1)
                }
                return@Column
            }

            KeyValue(
                "현재 좌표",
                if (hasFix) "%.5f, %.5f".format(currentLatitude, currentLongitude)
                else "GPS 수신 대기",
                valueColor = if (hasFix) null else WarnAmber
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("구간 이름") },
                placeholder = { Text("예: 시청앞 사거리") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it.filter(Char::isDigit).take(3) },
                    label = { Text("제한속도 km/h") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = radius,
                    onValueChange = { radius = it.filter(Char::isDigit).take(5) },
                    label = { Text("반경 m") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            if (error.isNotEmpty()) {
                Text(error, color = BlockRed, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    val l = limit.toDoubleOrNull()
                    val r = radius.toDoubleOrNull()
                    error = when {
                        !hasFix -> "GPS 위치를 먼저 받아야 합니다."
                        name.isBlank() -> "구간 이름을 입력하세요."
                        l == null || l <= 0 -> "제한속도를 입력하세요."
                        r == null || r <= 0 -> "반경을 입력하세요."
                        else -> ""
                    }
                    if (error.isEmpty() && l != null && r != null) {
                        onAdd(name.trim(), currentLatitude, currentLongitude, r, l)
                        name = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("현재 위치에 등록")
            }

            Text(
                "구간이 겹치면 반경이 작은 쪽이 우선합니다. 변경은 다음 수집 시작부터 적용됩니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { expanded = false }) { Text("접기", maxLines = 1) }
        }
    }
}
