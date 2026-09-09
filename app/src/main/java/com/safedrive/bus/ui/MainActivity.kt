package com.safedrive.bus.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safedrive.bus.core.Constants
import com.safedrive.bus.data.CsvExporter
import com.safedrive.bus.data.SpeedZoneEntity
import com.safedrive.bus.data.TripRepository
import com.safedrive.bus.data.TripSummary
import com.safedrive.bus.service.DrivingService
import com.safedrive.bus.service.Telemetry
import com.safedrive.bus.util.AppPrefs
import com.safedrive.bus.util.BatteryOptimization
import com.safedrive.bus.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri

/**
 * 뒤로가기로 앱을 닫을 때의 확인.
 *
 * 뒤로가기는 "이 작업을 끝낸다"는 뜻으로 쓴다. 그래서 여기서 닫으면 기록도 함께 멈춘다.
 * 잠시 다른 앱을 보는 것은 홈 버튼이나 전화 수신처럼 앱이 뒤로 물러나는 경우이고,
 * 그때는 서비스가 그대로 돌기 때문에 이 확인이 뜨지 않는다.
 *
 * 대신 제스처 내비게이션에서 뒤로가기는 화면 가장자리 스와이프라 운전 중 스치기 쉽다.
 * 그래서 기록 시간과 경고 건수를 함께 보여 준다. "3시간 12분 기록 중"이 보이면
 * 아직 운행 중이라는 것을 그 자리에서 알아채고 취소할 수 있다.
 */
@Composable
private fun ExitDialog(
    serviceRunning: Boolean,
    tripDurationMs: Long,
    warnedCount: Int,
    onDismiss: () -> Unit,
    onCloseAndStop: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("앱을 종료할까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (serviceRunning) {
                    ExitTripStat(tripDurationMs, warnedCount)
                    Text(
                        "운행도 함께 종료됩니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "휴게 중이거나 잠시 다른 앱을 볼 때는 홈 버튼을 누르세요. 같은 운행으로 이어집니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "진행 중인 운행이 없습니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onCloseAndStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text("확인", maxLines = 1) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("취소", maxLines = 1) }
        }
    )
}

/**
 * 5분 이상 정차했을 때의 운행 종료 확인.
 *
 * 종점 회차인지 긴 신호 대기인지는 앱이 구분할 수 없다. 그래서 자동으로 끊지 않고
 * 묻는다. 잘못 끊으면 그 뒤 구간이 별개 운행으로 갈리고, 잘못 이어가면 퇴근 후에도
 * GPS가 계속 돈다. 판단할 수 있는 것은 기사뿐이다.
 */
@Composable
private fun IdleEndDialog(
    tripDurationMs: Long,
    idleMs: Long,
    warnedCount: Int,
    onContinue: () -> Unit,
    onEndTrip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text("운행을 종료할까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        ExitStatItem("운행 시간", formatDuration(tripDurationMs))
                        ExitStatItem("정차", formatDuration(idleMs))
                        ExitStatItem("경고", "${warnedCount}건")
                    }
                }
                Text(
                    "종료하면 여기까지가 운행 기록 한 건으로 남습니다.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "휴게 중이거나 회차 대기라면 계속 운행을 고르세요. 같은 운행으로 이어집니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onEndTrip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text("운행 종료", maxLines = 1) }
        },
        dismissButton = {
            OutlinedButton(onClick = onContinue) { Text("계속 운행", maxLines = 1) }
        }
    )
}

/** 지금 진행 중인 운행. 오터치로 뜬 팝업을 알아채는 근거가 된다. */
@Composable
private fun ExitTripStat(tripDurationMs: Long, warnedCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            ExitStatItem("운행 시간", formatDuration(tripDurationMs))
            ExitStatItem("경고", "${warnedCount}건")
        }
    }
}

@Composable
private fun ExitStatItem(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

private enum class Tab(val label: String) {
    DRIVE("주행"),
    HISTORY("이력"),
    SETTINGS("설정"),
    DIAGNOSTIC("진단")
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPrefs

    private val foregroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val backgroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35(안드로이드 15)부터 edge-to-edge가 강제된다. 명시적으로 선언하고
        // 루트에서 safeDrawing 인셋만큼 여백을 준다.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        // 앱을 새로 여는 것은 운행하겠다는 뜻이므로 직전의 정지 표시를 푼다.
        // 뒤로가기로 닫으면 수집도 함께 멈추므로, 표시를 그보다 오래 들고 있을 이유가 없다.
        prefs.userStopped = false

        setContent {
            SafeDriveTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Root()
                }
            }
        }
    }

    @Composable
    private fun Root() {
        var onboardingDone by remember { mutableStateOf(prefs.onboardingDone) }
        var permissionTick by remember { mutableStateOf(0) }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) permissionTick++
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (!onboardingDone) {
            val fine = remember(permissionTick) { Permissions.hasFineLocation(this) }
            val background = remember(permissionTick) { Permissions.hasBackgroundLocation(this) }
            val notification = remember(permissionTick) { Permissions.hasNotification(this) }
            val batteryOk = remember(permissionTick) { BatteryOptimization.isIgnoring(this) }
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                OnboardingScreen(
                    fineLocationGranted = fine,
                    backgroundLocationGranted = background,
                    notificationGranted = notification,
                    batteryOptimizationIgnored = batteryOk,
                    onRequestForegroundPermissions = {
                        foregroundPermissionLauncher.launch(Permissions.foregroundRequest())
                    },
                    onRequestBackgroundLocation = {
                        // API 30+ 는 시스템이 인앱 요청을 거부하고 설정 화면으로만 허용한다.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            BatteryOptimization.openAppDetails(this@MainActivity)
                        } else {
                            backgroundPermissionLauncher.launch(Permissions.backgroundRequest())
                        }
                    },
                    onOpenBatterySettings = { BatteryOptimization.openSettings(this@MainActivity) },
                    onOpenAppDetails = { BatteryOptimization.openAppDetails(this@MainActivity) },
                    onDone = {
                        prefs.onboardingDone = true
                        onboardingDone = true
                    }
                )
            }
            return
        }

        MainShell(permissionTick)
    }

    @Composable
    private fun MainShell(permissionTick: Int) {
        val scope = rememberCoroutineScope()
        val repo = remember { TripRepository(applicationContext) }
        val state by Telemetry.state.collectAsStateWithLifecycle()

        var tab by remember { mutableStateOf(Tab.DRIVE) }
        var autoStart by remember { mutableStateOf(prefs.autoStart) }
        var textScale by remember { mutableStateOf(prefs.textScale) }
        var diagnostic by remember { mutableStateOf(prefs.diagnosticRecording) }
        var keepAwake by remember { mutableStateOf(prefs.keepAwake) }
        var reviewOpen by remember { mutableStateOf(false) }
        var reviewRange by remember { mutableStateOf(ReviewRange.SEGMENT) }

        // 앱을 열면 바로 수집이 돌게 한다. 기사가 버튼을 찾아 누를 이유가 없다.
        // 권한이 없으면 시작하지 않는다. API 34+ 에서 location 타입 FGS를 권한 없이
        // 시작하면 SecurityException으로 즉시 종료되기 때문이다.
        LaunchedEffect(autoStart, state.serviceRunning, permissionTick) {
            if (autoStart && !state.serviceRunning && !prefs.userStopped &&
                Permissions.hasFineLocation(this@MainActivity)
            ) {
                DrivingService.start(this@MainActivity)
            }
        }

        // 시작은 주행 화면과 설정 두 곳에서 부른다. 권한 처리를 두 번 쓰지 않는다.
        val startService = {
            prefs.userStopped = false
            if (!Permissions.hasFineLocation(this@MainActivity)) {
                foregroundPermissionLauncher.launch(Permissions.foregroundRequest())
            } else {
                DrivingService.start(this@MainActivity)
            }
        }

        val zones by repo.speedZoneDao.all().collectAsStateWithLifecycle(emptyList())
        val trips by repo.recentTrips().collectAsStateWithLifecycle(emptyList())

        var selectedTripId by remember { mutableLongStateOf(0L) }
        val effectiveTripId = if (selectedTripId != 0L) selectedTripId
        else trips.firstOrNull()?.id ?: 0L
        val counts by remember(effectiveTripId) {
            repo.countsOf(effectiveTripId)
        }.collectAsStateWithLifecycle(emptyList())
        val summary: TripSummary? = trips.firstOrNull { it.id == effectiveTripId }
            ?.let { TripSummary(it, counts) }

        var exportMessage by remember { mutableStateOf("") }
        var exportedUri by remember { mutableStateOf<Uri?>(null) }
        var confirmExit by remember { mutableStateOf(false) }

        // 뒤로가기로 바로 닫히면 운행 중 실수로 화면을 잃는다.
        // 다른 탭에 있으면 주행 탭으로 먼저 돌아가고, 주행 탭에서만 확인을 묻는다.
        BackHandler {
            if (tab != Tab.DRIVE) tab = Tab.DRIVE else confirmExit = true
        }

        // 5분 넘게 움직이지 않으면 운행을 끝낼지 묻는다. 버튼을 상시 노출하는 대신
        // 필요한 순간에만 나타나게 한다. 종점 회차인지 신호 대기인지는 앱이 알 수 없으므로
        // 자동으로 끊지 않고 반드시 기사에게 확인한다.
        //
        // "계속 운행"을 고르면 그 정차 동안에는 다시 묻지 않는다. 출발했다가 다시 5분
        // 넘게 서면 그때 새로 묻는다.
        //
        // 아무것도 고르지 않은 채 차가 다시 움직이면 팝업은 저절로 닫히고 운행이 이어진다.
        // 출발로 `review.stopped`가 풀려 idleMs가 0이 되기 때문이다. 이 경로에서는
        // 서비스를 건드리지 않으므로 같은 운행으로 계속 기록된다. 휴게를 마치고 그냥
        // 출발하는 것이 가장 흔한 경우이므로, 그때 아무 조작도 요구하지 않는다.
        var idlePromptAnsweredFor by remember { mutableLongStateOf(0L) }
        val stoppedSince = state.review.stoppedSinceWallMs
        val idleMs = if (state.serviceRunning && state.review.stopped && stoppedSince != 0L) {
            System.currentTimeMillis() - stoppedSince
        } else {
            0L
        }
        if (idleMs >= Constants.IDLE_END_PROMPT_MS && stoppedSince != idlePromptAnsweredFor) {
            IdleEndDialog(
                tripDurationMs = state.tripDurationMs,
                idleMs = idleMs,
                warnedCount = state.totalWarned,
                onContinue = { idlePromptAnsweredFor = stoppedSince },
                onEndTrip = {
                    idlePromptAnsweredFor = stoppedSince
                    prefs.userStopped = true
                    DrivingService.stop(this@MainActivity)
                }
            )
        }

        if (confirmExit) {
            ExitDialog(
                serviceRunning = state.serviceRunning,
                tripDurationMs = state.tripDurationMs,
                warnedCount = state.totalWarned,
                onDismiss = { confirmExit = false },
                onCloseAndStop = {
                    confirmExit = false
                    prefs.userStopped = true
                    DrivingService.stop(this@MainActivity)
                    finish()
                }
            )
        }

        // 시스템 글꼴 배율 위에 앱 배율을 곱한다. 기기 설정을 무시하지 않으면서
        // 줄바꿈이 생기는 기기에서 사용자가 직접 줄일 수 있게 한다.
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, base.fontScale * textScale)
        ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.DRIVE -> DriveScreen(
                            state = state,
                            reviewManuallyOpen = reviewOpen,
                            reviewRange = reviewRange,
                            onToggleReview = { reviewOpen = !reviewOpen },
                            onReviewRangeChange = { reviewRange = it }
                        )

                        Tab.HISTORY -> HistoryScreen(
                            trips = trips,
                            selectedTripId = effectiveTripId,
                            selectedSummary = summary,
                            exportMessage = exportMessage,
                            canShare = exportedUri != null,
                            onSelectTrip = { selectedTripId = it },
                            onDeleteTrip = { id ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { repo.deleteTrip(id) }
                                    if (selectedTripId == id) selectedTripId = 0L
                                }
                            },
                            onDeleteAll = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { repo.deleteFinishedTrips() }
                                    selectedTripId = 0L
                                    exportMessage = ""
                                    exportedUri = null
                                }
                            },
                            onExportCsv = {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        CsvExporter.exportRecent(applicationContext, repo)
                                    }
                                    exportedUri = r?.uri
                                    exportMessage = when {
                                        r == null -> "내보내기에 실패했습니다."
                                        r.fallbackReason != null ->
                                            r.fallbackReason + " · " + r.displayPath
                                        else -> "저장됨: ${r.displayPath}"
                                    }
                                }
                            },
                            onShare = {
                                exportedUri?.let { u ->
                                    startActivity(
                                        Intent.createChooser(CsvExporter.shareIntent(u), "CSV 공유")
                                    )
                                }
                            }
                        )

                        Tab.SETTINGS -> SettingsScreen(
                            state = state,
                            autoStart = autoStart,
                            diagnosticRecording = diagnostic,
                            keepAwake = keepAwake,
                            zones = zones,
                            onToggleAutoStart = {
                                prefs.autoStart = it
                                autoStart = it
                            },
                            onToggleDiagnostic = {
                                prefs.diagnosticRecording = it
                                diagnostic = it
                            },
                            onToggleKeepAwake = {
                                prefs.keepAwake = it
                                keepAwake = it
                            },
                            textScale = textScale,
                            onTextScaleChange = {
                                prefs.textScale = it
                                textScale = it
                            },
                            onStartService = startService,
                            // 정지 표시를 남기지 않으면 자동 시작이 곧바로 다시 켠다.
                            // 주행 화면·알림과 달리 이 경로에만 표시가 빠져 있었다.
                            onStopService = {
                                prefs.userStopped = true
                                DrivingService.stop(this@MainActivity)
                            },
                            onAddZone = { name, lat, lon, radius, limit ->
                                scope.launch(Dispatchers.IO) {
                                    repo.speedZoneDao.insert(
                                        SpeedZoneEntity(
                                            name = name,
                                            latitude = lat,
                                            longitude = lon,
                                            radiusM = radius,
                                            limitKmh = limit
                                        )
                                    )
                                }
                            },
                            onDeleteZone = { z ->
                                scope.launch(Dispatchers.IO) { repo.speedZoneDao.delete(z) }
                            }
                        )

                        Tab.DIAGNOSTIC -> DiagnosticScreen(state)
                    }
                }

                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {},
                            label = { Text(t.label) }
                        )
                    }
                }
            }

            // 경고 화면은 인셋 밖까지 덮는다. 화면은 보조 수단이지만 떴을 때는 확실히 보여야 한다.
            if (state.alert.isActive()) {
                AlertOverlay(state.alert)
            }
        }
        }
    }
}
