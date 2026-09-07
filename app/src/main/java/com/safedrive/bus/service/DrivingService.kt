package com.safedrive.bus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.safedrive.bus.R
import com.safedrive.bus.SafeDriveApp
import com.safedrive.bus.align.AlignmentSnapshot
import com.safedrive.bus.align.FrameAligner
import com.safedrive.bus.alert.AlertManager
import com.safedrive.bus.alert.AlertState
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.EventType
import com.safedrive.bus.data.TripRepository
import com.safedrive.bus.diag.DiagnosticRecorder
import com.safedrive.bus.diag.GapRecorder
import com.safedrive.bus.diag.GapSummary
import com.safedrive.bus.fusion.MotionEstimator
import com.safedrive.bus.fusion.MotionSnapshot
import com.safedrive.bus.gate.GateEvaluator
import com.safedrive.bus.gate.GateSnapshot
import com.safedrive.bus.judge.DrivingEvent
import com.safedrive.bus.judge.JudgeInput
import com.safedrive.bus.judge.JudgementEngine
import com.safedrive.bus.judge.ReviewTracker
import com.safedrive.bus.loc.GpsSample
import com.safedrive.bus.loc.LocationSource
import com.safedrive.bus.sensor.SensorFrame
import com.safedrive.bus.sensor.SensorHealth
import com.safedrive.bus.sensor.SensorSampler
import com.safedrive.bus.speedlimit.CompositeSpeedLimitProvider
import com.safedrive.bus.speedlimit.ManualSpeedLimitProvider
import com.safedrive.bus.speedlimit.NodeLinkSpeedLimitProvider
import com.safedrive.bus.speedlimit.SpeedLimitMatch
import com.safedrive.bus.ui.MainActivity
import com.safedrive.bus.util.AppPrefs
import com.safedrive.bus.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 센서 수집과 판정 전체를 담는 포그라운드 서비스.
 *
 * 센서는 반드시 이 서비스 안에서 등록한다. API 28부터 백그라운드 앱은 연속보고형 센서
 * 이벤트를 받지 못하기 때문에, 서비스 밖에서 등록하면 화면이 꺼지는 순간 데이터가 끊긴다.
 *
 * foregroundServiceType은 location이다. API 35에서 dataSync 등 일부 타입에 6시간 타임아웃이
 * 도입됐는데 location은 대상이 아니므로 8시간 연속 운행에 안전하다.
 *
 * 스레드 배치
 *  - 센서 스레드: 정렬 -> 속도 융합 -> 판정. 경고(TTS/진동)도 지연을 줄이기 위해 여기서 낸다.
 *  - 서비스 코루틴: 워치독, 상태 게시, DB 기록. 판정 결과는 채널로 넘어온다.
 */
class DrivingService : LifecycleService() {

    private lateinit var prefs: AppPrefs
    private lateinit var aligner: FrameAligner
    private lateinit var motion: MotionEstimator
    private lateinit var gaps: GapRecorder
    private lateinit var alerts: AlertManager
    private lateinit var repo: TripRepository
    private lateinit var manualLimits: ManualSpeedLimitProvider
    private lateinit var roadLimits: NodeLinkSpeedLimitProvider
    private lateinit var speedLimits: CompositeSpeedLimitProvider
    private lateinit var judge: JudgementEngine
    private val review = ReviewTracker()

    private var sampler: SensorSampler? = null
    private var location: LocationSource? = null
    private var diagnostics: DiagnosticRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var startedAtWallMs = 0L
    private var lastPublishNs = 0L
    private var lastNotificationMs = 0L

    @Volatile
    private var tripId = 0L

    @Volatile
    private var currentSpeedLimitKmh: Double? = null

    @Volatile
    private var currentMatch: SpeedLimitMatch? = null

    @Volatile
    private var alignSnapshot = AlignmentSnapshot()

    @Volatile
    private var motionSnapshot = MotionSnapshot()

    @Volatile
    private var longitudinalError: Float? = null

    /** 센서 스레드가 읽는 최신 게이트 상태. 판정 결과에 함께 저장된다. */
    @Volatile
    private var gateSnapshot = GateSnapshot.INITIAL

    private val eventChannel = Channel<DrivingEvent>(Channel.UNLIMITED)
    private val absorbChannel = Channel<Long>(Channel.UNLIMITED)

    private val eventCounts = HashMap<EventType, Int>()
    private val warnedCounts = HashMap<EventType, Int>()

    /** 마지막으로 경고가 나간 시각과 그때까지의 주행거리. 무경고 연속 지표의 기준점. */
    @Volatile
    private var lastWarnWallMs = 0L

    @Volatile
    private var lastWarnDistanceM = 0.0

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        aligner = FrameAligner()
        motion = MotionEstimator()
        gaps = GapRecorder()
        alerts = AlertManager(this)
        repo = TripRepository(this)
        // 사용자가 직접 등록한 구간이 공공데이터보다 앞선다.
        // 데이터에 없는 임시 규제나 잘못된 값을 기사가 바로잡을 수 있어야 한다.
        manualLimits = ManualSpeedLimitProvider(repo.speedZoneDao)
        roadLimits = NodeLinkSpeedLimitProvider(this)
        speedLimits = CompositeSpeedLimitProvider(listOf(manualLimits, roadLimits))
        judge = JudgementEngine(
            onEvent = ::onJudgedEvent,
            onAbsorbedByUturn = { absorbChannel.trySend(it) }
        )
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startCollection()
        // 프로세스가 죽어도 시스템이 서비스를 되살리도록 한다.
        // 다만 제조사 최적화로 되살아나지 않는 기기가 있으므로 끊김 기록으로 확인해야 한다.
        return START_STICKY
    }

    override fun onDestroy() {
        stopCollection()
        Telemetry.update { it.copy(serviceRunning = false, alert = AlertState()) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun startCollection() {
        if (sampler != null) return

        // API 34부터 foregroundServiceType=location 서비스를 위치 권한 없이 시작하면
        // SecurityException으로 즉시 종료된다. 시작 전에 막고 이유를 남긴다.
        if (!Permissions.hasFineLocation(this) && !Permissions.hasCoarseLocation(this)) {
            Telemetry.update {
                it.copy(
                    serviceRunning = false,
                    lastError = "위치 권한이 없어 수집을 시작할 수 없습니다. " +
                        "설정에서 ‘정확한 위치’를 허용한 뒤 다시 시작하세요."
                )
            }
            stopSelf()
            return
        }

        startedAtWallMs = System.currentTimeMillis()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("측정 준비 중"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        )

        aligner.reset()
        motion.reset()
        judge.reset()
        review.reset()
        gaps.start()
        eventCounts.clear()
        warnedCounts.clear()
        lastWarnWallMs = 0L
        lastWarnDistanceM = 0.0
        currentSpeedLimitKmh = null
        currentMatch = null
        gateSnapshot = GateSnapshot.INITIAL
        Telemetry.reset()

        alerts.start()

        if (prefs.diagnosticRecording) {
            diagnostics = DiagnosticRecorder(this).also { it.start() }
        }
        if (prefs.keepAwake) acquireWakeLock()

        val s = SensorSampler(this, ::onSensorFrame)
        val sensorOk = s.start()
        sampler = s

        val locationOk = LocationSource(this) { onGps(it) }.also { location = it }.start()

        Telemetry.update {
            it.copy(
                serviceRunning = true,
                startedAtWallMs = startedAtWallMs,
                diagnosticRecording = diagnostics != null,
                lastError = when {
                    !sensorOk -> "센서 등록 실패. 가속도계/자이로스코프를 확인하세요."
                    !locationOk -> "위치 갱신을 시작하지 못했습니다."
                    else -> ""
                }
            )
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repo.purgeOld()
                manualLimits.refresh()
                roadLimits.open()
                tripId = repo.startTrip(startedAtWallMs)
            }
            Telemetry.update {
                it.copy(
                    tripId = tripId,
                    speedZoneCount = manualLimits.zoneCount,
                    roadDataReady = roadLimits.ready,
                    roadDataSource = roadLimits.sourceName
                )
            }
        }
        lifecycleScope.launch { consumeAbsorbs() }
        lifecycleScope.launch { consumeEvents() }
        lifecycleScope.launch { publishLoop() }
    }

    private fun stopCollection() {
        sampler?.stop(); sampler = null
        location?.stop(); location = null
        diagnostics?.stop(); diagnostics = null
        alerts.stop()
        roadLimits.close()
        releaseWakeLock()

        val id = tripId
        if (id != 0L) {
            val summary = gaps.snapshot()
            val distance = motionSnapshot.distanceM
            val unmatched = judge.stats().unmatchedLimitSamples
            // 서비스 스코프는 곧 취소되므로 마감은 애플리케이션 스코프에서 처리한다.
            SafeDriveApp.appScope.launch {
                repo.finishTrip(
                    tripId = id,
                    endedAtMs = System.currentTimeMillis(),
                    distanceM = distance,
                    dataGapMs = summary.totalDataGapMs,
                    stallMs = summary.totalStallMs,
                    gapCount = summary.gapCount,
                    unmatchedLimitSamples = unmatched
                )
            }
        }
        tripId = 0L
    }

    // ------------------------------------------------------------------
    // 센서 스레드
    // ------------------------------------------------------------------

    private fun onSensorFrame(frame: SensorFrame) {
        gaps.onFrame(frame.timestampNs)
        diagnostics?.write(frame)

        val vehicleMotion = aligner.process(
            frame = frame,
            gpsSpeedMps = motion.gpsSpeedMps,
            gpsAccelMps2 = motion.gpsAccelMps2,
            gpsFresh = motion.gpsFresh
        )

        val pitchReliable = frame.gravityFromRotationVector
        motion.onFrame(frame.dtSec, vehicleMotion, pitchReliable)

        val snap = motion.snapshot()

        // 판정은 게이트와 무관하게 항상 돌린다. 게이트는 경고를 낼지 여부만 결정한다.
        // 다만 차량 좌표계가 없으면 종/횡/요 성분 자체가 정의되지 않으므로 그때는 건너뛴다.
        if (vehicleMotion != null) {
            judge.process(
                JudgeInput(
                    timestampNs = frame.timestampNs,
                    wallMs = System.currentTimeMillis(),
                    speedKmh = snap.fusedSpeedKmh,
                    longKmhPerSec = snap.longitudinalKmhPerSec,
                    verticalMps2 = vehicleMotion.vertical,
                    yawRateDps = snap.yawRateDps,
                    latitude = snap.latitude,
                    longitude = snap.longitude,
                    gpsAccuracyM = snap.gpsAccuracyM,
                    speedLimitKmh = currentSpeedLimitKmh,
                    gates = gateSnapshot,
                    pitchReliable = pitchReliable
                )
            )
        }

        // UI 표시 주기에 맞춰 스냅샷을 게시한다. 매 프레임 게시는 낭비다.
        if (lastPublishNs == 0L ||
            frame.timestampNs - lastPublishNs >= Constants.UI_UPDATE_INTERVAL_MS * 1_000_000L
        ) {
            lastPublishNs = frame.timestampNs
            alignSnapshot = aligner.snapshot()
            motionSnapshot = snap
            longitudinalError = motion.longitudinalVsGpsErrorKmhPerSec()
        }
    }

    /** 센서 스레드에서 호출된다. 경고는 지연을 줄이기 위해 여기서 바로 낸다. */
    private fun onJudgedEvent(e: DrivingEvent) {
        if (e.warned) alerts.warn(e.type, detailOf(e))
        eventChannel.trySend(e)
    }

    private fun detailOf(e: DrivingEvent): String = when (e.type) {
        EventType.OVERSPEED, EventType.LONG_OVERSPEED ->
            "%.0f km/h · 제한 %.0f".format(e.speedKmh, e.speedLimitKmh ?: 0.0)

        EventType.SHARP_TURN, EventType.SHARP_UTURN ->
            "%s %.0f°".format(e.turnDirection.label, abs(e.turnAngleDeg))

        else -> "%.1f km/h/s".format(abs(e.peakKmhPerSec))
    }

    private fun onGps(sample: GpsSample) {
        motion.onGps(sample)
        // 도로 매칭은 격자 인덱스 조회라 1Hz 콜백에서 해도 부담이 없다.
        val match = speedLimits.matchAt(
            latitude = sample.latitude,
            longitude = sample.longitude,
            bearingDeg = sample.bearingDeg,
            speedKmh = (motion.gpsSpeedMps ?: 0f) * 3.6f
        )
        currentMatch = match
        // 어린이보호구역 근처인데 제한속도가 40 이상으로 잡힌 구간은 데이터가 실제 규제를
        // 반영하지 못했을 수 있다. 매칭 실패와 똑같이 과속 판정을 보류한다.
        currentSpeedLimitKmh = if (match == null || match.schoolSuspect) null else match.limitKmh
    }

    // ------------------------------------------------------------------
    // 서비스 코루틴
    // ------------------------------------------------------------------

    private suspend fun consumeAbsorbs() {
        for (wallMs in absorbChannel) {
            val id = tripId
            if (id != 0L) withContext(Dispatchers.IO) { repo.markAbsorbedByUturn(id, wallMs) }
        }
    }

    private suspend fun consumeEvents() {
        for (e in eventChannel) {
            eventCounts[e.type] = (eventCounts[e.type] ?: 0) + 1
            if (e.warned) {
                warnedCounts[e.type] = (warnedCounts[e.type] ?: 0) + 1
                lastWarnWallMs = e.wallMs
                lastWarnDistanceM = motionSnapshot.distanceM
            }
            review.add(e)
            val id = tripId
            if (id != 0L) withContext(Dispatchers.IO) { repo.record(id, e) }
        }
    }

    private suspend fun publishLoop() {
        while (lifecycleScope.isActive && sampler != null) {
            // 프레임이 아예 오지 않는 상황은 onSensorFrame으로 잡을 수 없다.
            gaps.tick()
            alerts.clearIfExpired()

            val gapSummary: GapSummary = gaps.snapshot()
            val align = alignSnapshot
            val mot = motionSnapshot
            val health = sampler?.health() ?: SensorHealth()
            val gate = GateEvaluator.evaluate(
                alignment = align,
                motion = mot,
                health = health,
                gaps = gapSummary,
                serviceRunning = true
            )
            gateSnapshot = gate
            review.onSpeed(mot.fusedSpeedKmh, System.currentTimeMillis())

            Telemetry.update {
                it.copy(
                    serviceRunning = true,
                    startedAtWallMs = startedAtWallMs,
                    tripId = tripId,
                    motion = mot,
                    alignment = align,
                    health = health,
                    gates = gate,
                    gaps = gapSummary,
                    longitudinalErrorKmhPerSec = longitudinalError,
                    diagnosticRecording = diagnostics != null,
                    alert = alerts.state,
                    ttsAvailable = alerts.speechAvailable,
                    speedLimitKmh = currentSpeedLimitKmh,
                    speedLimitMatch = currentMatch,
                    roadDataReady = roadLimits.ready,
                    roadDataSource = roadLimits.sourceName,
                    speedZoneCount = manualLimits.zoneCount,
                    judge = judge.stats(),
                    eventCounts = HashMap(eventCounts),
                    warnedCounts = HashMap(warnedCounts),
                    review = review.state(),
                    noWarnDurationMs = System.currentTimeMillis() -
                        (if (lastWarnWallMs != 0L) lastWarnWallMs else startedAtWallMs),
                    noWarnDistanceM = (mot.distanceM - lastWarnDistanceM).coerceAtLeast(0.0)
                )
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastNotificationMs > NOTIFICATION_UPDATE_MS) {
                lastNotificationMs = now
                updateNotification(notificationText(align, mot, gate))
            }

            delay(Constants.UI_UPDATE_INTERVAL_MS)
        }
    }

    private fun notificationText(
        align: AlignmentSnapshot,
        mot: MotionSnapshot,
        gate: GateSnapshot
    ): String = if (align.aligned) {
        "%.0f km/h · %s · 이벤트 %d건".format(
            mot.fusedSpeedKmh,
            if (gate.warningAllowed) "감지 중" else "경고 보류",
            eventCounts.values.sum()
        )
    } else {
        "보정 중 %.0f%% · %s".format(align.overallProgress * 100, gate.alignment.detail)
    }

    // ------------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_service),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.channel_service_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DrivingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "safedrive:collection").apply {
            setReferenceCounted(false)
            // 타임아웃을 주어 서비스가 비정상 종료돼도 OS가 회수하게 한다.
            // 최장 근무(8시간)보다 넉넉하되 무한은 아니게 12시간으로 둔다.
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "safedrive_service"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_UPDATE_MS = 3000L
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        const val ACTION_STOP = "com.safedrive.bus.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DrivingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DrivingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
