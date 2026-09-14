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
import com.safedrive.bus.data.TripReportExporter
import com.safedrive.bus.filter.LowPass1PVec3
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

    /** 수평 가속도 벡터 저역통과. 정렬 전 대체 검증이 진동에 부풀려지지 않게 한다. */
    private val horizLpf = LowPass1PVec3(Constants.ACCEL_LPF_CUTOFF_HZ)

    private var sampler: SensorSampler? = null
    private var location: LocationSource? = null
    private var diagnostics: DiagnosticRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var startedAtWallMs = 0L
    private var lastPublishNs = 0L
    private var lastNotificationMs = 0L

    @Volatile
    private var tripId = 0L

    /** 이번 정차에서 종료 확인 알림을 이미 띄웠는지. 정차마다 한 번만 묻는다. */
    private var idlePromptShown = false
    /** 사용자가 "계속 운행"을 고른 정차의 시작 시각. 그 정차 동안에는 다시 묻지 않는다. */
    private var idleAnsweredFor = 0L

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

    /** 서비스가 되살아나 직전 운행을 이어받았는지. 진단 화면에 표시한다. */
    @Volatile
    private var sessionRestartCount = 0

    private var lastHeartbeatMs = 0L

    /**
     * 직전 운행 이어받기가 끝났는지.
     *
     * 이 값이 false인 동안에는 생존 신호를 쓰지 않는다. 게시 루프가 먼저 돌면
     * 아직 0인 누적 거리로 저장값을 덮어써서 이어받을 거리가 사라진다.
     */
    @Volatile
    private var sessionReady = false

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
        if (intent?.action == ACTION_PREVIEW_ALERT) {
            // 설정 화면의 시험 재생. 수집 중이 아니면 TTS가 준비되지 않았으므로 무시한다.
            alerts.preview("급감속")
            return START_STICKY
        }
        if (intent?.action == ACTION_KEEP_DRIVING) {
            // 이번 정차 동안에는 다시 묻지 않는다. 출발했다가 또 오래 서면 새로 묻는다.
            idleAnsweredFor = review.state().stoppedSinceWallMs
            cancelIdleNotification()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // 사용자가 직접 멈춘 것이므로 자동 시작이 다시 켜지 않도록 표시해 둔다.
            prefs.userStopped = true
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
        horizLpf.reset()
        motion.reset()
        judge.reset()
        review.reset()
        gaps.start()
        eventCounts.clear()
        warnedCounts.clear()
        lastWarnWallMs = 0L
        lastWarnDistanceM = 0.0
        lastHeartbeatMs = 0L
        sessionReady = false
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
                resumeOrStartTrip()
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

    /**
     * 직전 운행을 이어받거나 새로 시작한다.
     *
     * 제조사 절전 정책이 서비스를 죽이면 START_STICKY로 되살아나는데, 그때마다
     * 새 운행으로 시작하면 "경고 없이 N분"이 실제 운행 시간보다 짧게 나오고
     * 이력도 잘게 쪼개진다. 최근 생존 신호가 남아 있으면 같은 운행으로 잇는다.
     */
    private suspend fun resumeOrStartTrip() {
        val now = System.currentTimeMillis()
        val prevTrip = prefs.sessionTripId
        val prevStart = prefs.sessionStartedAt

        // 운행의 경계는 기사가 정한다. 운행 종료·앱 종료·알림 정지에서만 clearSession()이
        // 돌고, 그 외에 세션 정보가 남아 있다면 절전 정책이 프로세스를 죽인 것이다.
        //
        // 예전에는 마지막 생존 신호로부터 10분 안에 되살아난 경우만 이어받았다. 그러면
        // 30분 휴게 중에 서비스가 죽었을 때 같은 운행이 이력 두 건으로 갈린다.
        // 날짜만 확인한다. 자정을 넘긴 운행은 어차피 rolloverIfNewDay가 끊는다.
        val resumable = prevTrip != 0L &&
            prevStart != 0L &&
            localDate(prevStart) == localDate(now) &&
            repo.isTripOpen(prevTrip)

        if (resumable) {
            tripId = prevTrip
            startedAtWallMs = prefs.sessionStartedAt.takeIf { it != 0L } ?: startedAtWallMs
            lastWarnWallMs = prefs.sessionLastWarnAt
            lastWarnDistanceM = prefs.sessionLastWarnDistanceM.toDouble()
            motion.seedDistance(prefs.sessionDistanceM.toDouble())
            sessionRestartCount = prefs.sessionRestartCount + 1
            prefs.sessionRestartCount = sessionRestartCount
        } else {
            tripId = repo.startTrip(startedAtWallMs)
            sessionRestartCount = 0
            prefs.sessionTripId = tripId
            prefs.sessionStartedAt = startedAtWallMs
            prefs.sessionDistanceM = 0f
            prefs.sessionLastWarnAt = 0L
            prefs.sessionLastWarnDistanceM = 0f
            prefs.sessionRestartCount = 0
        }
        prefs.sessionHeartbeat = now
        sessionReady = true

        // 절전 정책에 프로세스가 죽으면 마감 코드가 돌지 못해 운행이 열린 채 남는다.
        // 이력에 "진행 중"으로 쌓이므로, 지금 쓰는 운행만 빼고 여기서 닫는다.
        repo.closeOrphanTrips(tripId)
    }

    /**
     * 자정을 넘기면 운행을 끊고 새로 시작한다.
     *
     * 하나의 기록이 날짜를 넘겨 이어지면 "오늘 몇 건"이 어제 것과 섞인다.
     * 막차가 자정을 넘겨도 그 시점에 끊는다. 어제 운행은 이력에 그대로 남는다.
     */
    private suspend fun rolloverIfNewDay(nowWall: Long) {
        if (tripId == 0L || !sessionReady) return
        if (localDate(nowWall) == localDate(startedAtWallMs)) return

        val summary = gaps.snapshot()
        repo.finishTrip(
            tripId = tripId,
            endedAtMs = nowWall,
            distanceM = motionSnapshot.distanceM,
            dataGapMs = summary.totalDataGapMs,
            stallMs = summary.totalStallMs,
            gapCount = summary.gapCount,
            unmatchedLimitSamples = judge.stats().unmatchedLimitSamples,
            limitSampleTotal = judge.stats().limitSampleTotal
        )
        // 새 운행이 시작되기 전에 어제 운행의 진단 값을 파일로 남긴다.
        val finishedId = tripId
        val reportSnap = Telemetry.state.value
        SafeDriveApp.appScope.launch {
            runCatching {
                TripReportExporter.save(
                    applicationContext, repo, finishedId, reportSnap,
                    TripReportExporter.Trigger.MIDNIGHT
                )
            }
        }

        startedAtWallMs = nowWall
        lastWarnWallMs = 0L
        lastWarnDistanceM = motionSnapshot.distanceM
        eventCounts.clear()
        warnedCounts.clear()
        sessionRestartCount = 0
        tripId = repo.startTrip(nowWall)

        prefs.sessionTripId = tripId
        prefs.sessionStartedAt = nowWall
        prefs.sessionDistanceM = 0f
        prefs.sessionLastWarnAt = 0L
        prefs.sessionLastWarnDistanceM = 0f
        prefs.sessionRestartCount = 0
    }

    private fun localDate(wallMs: Long): java.time.LocalDate =
        java.time.Instant.ofEpochMilli(wallMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()

    private fun stopCollection() {
        // 진단 값은 이 순간이 마지막이다. 다음 운행이 시작되면 초기화된다.
        val reportSnap = Telemetry.state.value
        cancelIdleNotification()
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
            val limitTotal = judge.stats().limitSampleTotal
            // 서비스 스코프는 곧 취소되므로 마감은 애플리케이션 스코프에서 처리한다.
            SafeDriveApp.appScope.launch {
                repo.finishTrip(
                    tripId = id,
                    endedAtMs = System.currentTimeMillis(),
                    distanceM = distance,
                    dataGapMs = summary.totalDataGapMs,
                    stallMs = summary.totalStallMs,
                    gapCount = summary.gapCount,
                    unmatchedLimitSamples = unmatched,
                    limitSampleTotal = limitTotal
                )
                // 스크린샷 대신 파일로 남긴다. 마감 뒤에 써야 거리·보류 비율이 확정값이고,
                // 빈 운행으로 지워졌으면 저장하지 않는다. 실패해도 마감에는 영향이 없다.
                runCatching {
                    TripReportExporter.save(
                        applicationContext, repo, id, reportSnap,
                        TripReportExporter.Trigger.TRIP_END
                    )
                }
            }
        }
        // 사용자가 직접 멈춘 것이므로 다음 시작은 새 운행이다.
        prefs.clearSession()
        tripId = 0L
        sessionRestartCount = 0
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
        // 좌표계 정렬이 끝나지 않아도 가감속·과속은 GPS 속도만으로 판정할 수 있다.
        // 정렬을 기다리게 하면 보정이 막힌 동안 이벤트가 한 건도 남지 않아
        // 무엇이 잘못됐는지조차 알 수 없게 된다.
        judge.process(
            JudgeInput(
                timestampNs = frame.timestampNs,
                wallMs = System.currentTimeMillis(),
                speedKmh = snap.fusedSpeedKmh,
                longKmhPerSec = snap.longitudinalKmhPerSec,
                verticalMps2 = vehicleMotion?.vertical ?: 0f,
                yawRateDps = snap.yawRateDps,
                horizontalMps2 = frame.horizontalAccelMps2,
                horizontalLpfMps2 = horizLpf.update(
                    frame.horizontalAccel,
                    if (frame.dtSec > 0.0 && frame.dtSec < 1.0) frame.dtSec else 0.02
                ).norm,
                latitude = snap.latitude,
                longitude = snap.longitude,
                gpsAccuracyM = snap.gpsAccuracyM,
                speedAccuracyMps = snap.gpsSpeedAccuracyMps,
                speedSuppressed = snap.gpsSpeedSuppressed,
                speedLimitKmh = currentSpeedLimitKmh,
                roadName = currentMatch?.roadName,
                matchDistanceM = currentMatch?.distanceM,
                gates = gateSnapshot,
                pitchReliable = pitchReliable,
                // 히스테리시스는 정렬기가 건다. 여기서 다시 계산하면 경계에서 깜빡인다.
                forwardSignTrusted = alignSnapshot.forwardSignTrusted,
                forwardSignAgreement = alignSnapshot.forwardSignAgreement,
                vehicleFrameReady = vehicleMotion != null
            )
        )

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

        else -> "%.1f km/h/s".format(abs(e.judgedValue))
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
        // 과속 판정을 보류하는 두 경우.
        //  - 어린이보호구역 근처인데 제한속도가 40 이상: 데이터가 실제 규제를 반영 못 했을 수 있다
        //  - 제한속도가 30 미만: 주차장 진출입로 같은 이면도로에 잘못 매칭됐을 가능성이 크다
        currentSpeedLimitKmh = when {
            match == null -> null
            match.schoolSuspect -> null
            match.limitKmh < Constants.MIN_TRUSTED_SPEED_LIMIT_KMH -> null
            else -> match.limitKmh
        }
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
                prefs.sessionLastWarnAt = lastWarnWallMs
                prefs.sessionLastWarnDistanceM = lastWarnDistanceM.toFloat()
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
                    tripDurationMs = System.currentTimeMillis() - startedAtWallMs,
                    noWarnDurationMs = System.currentTimeMillis() -
                        (if (lastWarnWallMs != 0L) lastWarnWallMs else startedAtWallMs),
                    noWarnDistanceM = (mot.distanceM - lastWarnDistanceM).coerceAtLeast(0.0),
                    sessionRestartCount = sessionRestartCount
                )
            }

            val nowWall = System.currentTimeMillis()
            rolloverIfNewDay(nowWall)
            updateIdlePrompt(nowWall)
            if (sessionReady && nowWall - lastHeartbeatMs > Constants.SESSION_HEARTBEAT_INTERVAL_MS) {
                lastHeartbeatMs = nowWall
                prefs.sessionHeartbeat = nowWall
                prefs.sessionDistanceM = mot.distanceM.toFloat()
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
        if (nm.getNotificationChannel(CHANNEL_IDLE_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_IDLE_ID,
                    getString(R.string.channel_idle),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = getString(R.string.channel_idle_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    /**
     * 정차가 길어지면 알림을 띄우고, 출발하면 거둔다.
     *
     * 앱 안의 팝업과 같은 임계값(IDLE_END_PROMPT_MS)을 쓴다. 화면이 켜져 있으면
     * 둘 다 보이지만, 꺼져 있으면 이 알림만 남는다.
     */
    private fun updateIdlePrompt(nowWall: Long) {
        val r = review.state()
        val since = r.stoppedSinceWallMs
        if (!r.stopped || since == 0L) {
            // 출발했다. 알림을 거두고 다음 정차를 위해 초기화한다.
            cancelIdleNotification()
            return
        }
        if (since == idleAnsweredFor) return
        if (idlePromptShown) return
        val idle = nowWall - since
        if (idle < Constants.IDLE_END_PROMPT_MS) return
        postIdleNotification(idle)
    }

    /**
     * 오래 정차했을 때 운행을 끝낼지 알림으로 묻는다.
     *
     * 앱 안의 팝업은 화면이 켜져 있어야 보인다. 화면을 끈 채 하차하면 아무도 못 보고
     * 운행이 자정까지 이어진다. 실측(2026-09-11)에서 하차 후 도보 구간이 운행에 섞여
     * 급가속 경고까지 나갔다. 거리와 시간이 부풀려지면 100km 환산이 통째로 틀어진다.
     *
     * 포그라운드 알림과 별도 채널로 띄운다. 그쪽은 IMPORTANCE_LOW라 조용하고,
     * 이건 놓치면 안 되기 때문이다.
     */
    private fun postIdleNotification(idleMs: Long) {
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, DrivingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val keep = PendingIntent.getService(
            this, 3,
            Intent(this, DrivingService::class.java).setAction(ACTION_KEEP_DRIVING),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_IDLE_ID)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(getString(R.string.idle_title))
            .setContentText("%s째 정차 중입니다.".format(formatIdle(idleMs)))
            .addAction(0, getString(R.string.end_trip), stop)
            .addAction(0, getString(R.string.keep_driving), keep)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .build()
        getSystemService(NotificationManager::class.java).notify(IDLE_NOTIFICATION_ID, n)
        idlePromptShown = true
    }

    private fun cancelIdleNotification() {
        if (!idlePromptShown) return
        getSystemService(NotificationManager::class.java).cancel(IDLE_NOTIFICATION_ID)
        idlePromptShown = false
    }

    private fun formatIdle(ms: Long): String {
        val m = ms / 60_000L
        return if (m > 0) "%d분".format(m) else "%d초".format(ms / 1000L)
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
        private const val CHANNEL_IDLE_ID = "safedrive_idle"
        private const val IDLE_NOTIFICATION_ID = 1002
        private const val NOTIFICATION_UPDATE_MS = 3000L
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        const val ACTION_STOP = "com.safedrive.bus.STOP"
        const val ACTION_KEEP_DRIVING = "com.safedrive.bus.KEEP_DRIVING"
        const val ACTION_PREVIEW_ALERT = "com.safedrive.bus.PREVIEW_ALERT"

        /** 설정 화면에서 경고음·음성을 시험 재생한다. */
        fun previewAlert(context: Context) {
            context.startService(
                Intent(context, DrivingService::class.java).setAction(ACTION_PREVIEW_ALERT)
            )
        }

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
