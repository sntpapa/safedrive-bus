package com.safedrive.bus.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 최초 실행 안내.
 *
 * 참고용 고지는 프롬프트 요구사항이며 리포트 화면에도 반복 표시한다.
 */
@Composable
fun OnboardingScreen(
    fineLocationGranted: Boolean,
    backgroundLocationGranted: Boolean,
    notificationGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    onRequestForegroundPermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "SafeDrive 시작 전 확인",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        DisclaimerCard()

        StepCard(
            index = 1,
            title = "위치 권한",
            done = fineLocationGranted,
            body = "속도와 위치 정확도를 얻는 데 필요합니다. " +
                "‘정확한 위치’를 허용해야 합니다. ‘대략적인 위치’만 허용하면 " +
                "정확도가 수백 m로 나와 판정이 계속 보류됩니다.",
            actionLabel = "권한 요청",
            onAction = onRequestForegroundPermissions
        )

        StepCard(
            index = 2,
            title = "위치 항상 허용",
            done = backgroundLocationGranted,
            body = "화면을 끄거나 앱을 내려도 수집이 이어지도록 ‘항상 허용’을 부여합니다. " +
                "시스템 설정 화면에서 직접 선택해야 하는 기기가 있습니다.",
            actionLabel = "설정 열기",
            onAction = onRequestBackgroundLocation
        )

        StepCard(
            index = 3,
            title = "알림 권한",
            done = notificationGranted,
            body = "수집 중 상태를 알림으로 표시합니다. 거부해도 수집은 되지만 " +
                "동작 여부를 확인하기 어렵습니다.",
            actionLabel = "권한 요청",
            onAction = onRequestForegroundPermissions
        )

        StepCard(
            index = 4,
            title = "배터리 최적화 제외",
            done = batteryOptimizationIgnored,
            body = "8시간 운행 중 수집이 끊기는 가장 큰 원인입니다. " +
                "제조사 절전 정책이 서비스를 종료시키면 그 구간은 기록되지 않습니다.\n\n" +
                "삼성 단말은 아래 두 곳을 모두 확인하세요.\n" +
                "· 설정 > 배터리 > 백그라운드 사용 제한 > 절전 앱 목록에서 제외\n" +
                "· 앱 정보 > 배터리 > ‘제한 없음’ 선택",
            actionLabel = "배터리 설정",
            onAction = onOpenBatterySettings,
            secondaryLabel = "앱 정보 열기",
            onSecondary = onOpenAppDetails
        )

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("확인하고 시작")
        }

        Text(
            "권한을 나중에 부여해도 됩니다. 다만 부여 전에는 해당 게이트가 계속 막힌 상태로 표시됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun DisclaimerCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "참고용 고지",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = WarnAmber
            )
            Text(
                "이 앱의 측정값과 판정은 한국교통안전공단 eTAS의 공식 판정이 아니라 " +
                    "휴대폰 센서로 계산한 추정치입니다. 차량 DTG 단말과 결과가 다를 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "자기점검 용도로만 사용하십시오. 회사의 감시·평가 도구가 아닙니다. " +
                    "모든 데이터는 이 기기 안에만 저장되며 외부로 전송되지 않습니다.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    done: Boolean,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$index. $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (done) "완료" else "필요",
                    color = if (done) PassGreen else BlockRed,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
                if (secondaryLabel != null && onSecondary != null) {
                    OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
                }
            }
        }
    }
}
