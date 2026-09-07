package com.safedrive.bus.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import java.io.File

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
        var diagnostic by remember { mutableStateOf(prefs.diagnosticRecording) }
        var keepAwake by remember { mutableStateOf(prefs.keepAwake) }
        var reviewOpen by remember { mutableStateOf(false) }
        var reviewRange by remember { mutableStateOf(ReviewRange.SEGMENT) }

        // 앱을 열면 바로 수집이 돌게 한다. 기사가 버튼을 찾아 누를 이유가 없다.
        // 권한이 없으면 시작하지 않는다. API 34+ 에서 location 타입 FGS를 권한 없이
        // 시작하면 SecurityException으로 즉시 종료되기 때문이다.
        LaunchedEffect(autoStart, state.serviceRunning, permissionTick) {
            if (autoStart && !state.serviceRunning && Permissions.hasFineLocation(this@MainActivity)) {
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
        var exportedFile by remember { mutableStateOf<File?>(null) }

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
                            canShare = exportedFile != null,
                            onSelectTrip = { selectedTripId = it },
                            onExportCsv = {
                                scope.launch {
                                    val f = withContext(Dispatchers.IO) {
                                        CsvExporter.exportRecent(applicationContext, repo)
                                    }
                                    exportedFile = f
                                    exportMessage = if (f == null) {
                                        "내보내기에 실패했습니다."
                                    } else {
                                        "저장됨: ${f.absolutePath}"
                                    }
                                }
                            },
                            onShare = {
                                exportedFile?.let { f ->
                                    val intent = CsvExporter.shareIntent(applicationContext, f)
                                    startActivity(Intent.createChooser(intent, "CSV 공유"))
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
                            onStartService = {
                                if (!Permissions.hasFineLocation(this@MainActivity)) {
                                    foregroundPermissionLauncher
                                        .launch(Permissions.foregroundRequest())
                                } else {
                                    DrivingService.start(this@MainActivity)
                                }
                            },
                            onStopService = { DrivingService.stop(this@MainActivity) },
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
