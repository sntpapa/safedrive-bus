package com.safedrive.bus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safedrive.bus.alert.AlertState
import com.safedrive.bus.core.EventType

/**
 * 경고 화면.
 *
 * 화면은 보조 수단이다. 주 수단은 TTS 음성과 진동이며, 화면이 꺼져 있어도 경고는 나간다.
 * 그래서 여기에는 배경색과 큰 글자 한 줄만 둔다. 기사가 읽어야만 내용을 알 수 있는
 * 설계는 만들지 않는다.
 */
@Composable
fun AlertOverlay(alert: AlertState) {
    val type = alert.type ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundFor(type)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                alert.headline,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                alert.detail,
                fontSize = 22.sp,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 감속 계열은 빨강, 나머지는 주황. 색만 봐도 종류가 갈리게 한다. */
private fun backgroundFor(type: EventType): Color = when (type) {
    EventType.HARSH_DECEL, EventType.HARSH_STOP,
    EventType.OVERSPEED, EventType.LONG_OVERSPEED -> Color(0xFFA32D2D)

    else -> Color(0xFF854F0B)
}
