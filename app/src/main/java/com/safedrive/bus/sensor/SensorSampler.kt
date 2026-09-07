package com.safedrive.bus.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.Vec3
import com.safedrive.bus.filter.LowPass1PVec3
import kotlin.math.abs

/** 중력 벡터를 어디서 얻고 있는지. 경사로 보정 신뢰도와 직결된다. */
enum class GravitySource {
    /** TYPE_GAME_ROTATION_VECTOR (자기장 미사용). 경사로 보정 신뢰 가능. */
    GAME_ROTATION_VECTOR,

    /** TYPE_GRAVITY 폴백. 기기에 따라 내부적으로 자기장이 섞일 수 있다. */
    GRAVITY_SENSOR,

    /** 가속도계 저역통과 폴백. 장시간 가감속을 중력으로 오인하므로 경사로 보정 신뢰 불가. */
    ACCEL_LOWPASS
}

data class SensorInfo(
    val name: String,
    val isWakeUp: Boolean,
    val fifoReservedEvents: Int,
    val fifoMaxEvents: Int,
    val minDelayUs: Int
) {
    /** FIFO가 없으면 maxReportLatencyUs가 무시되어 배칭 이득이 사라진다. */
    val batchingSupported: Boolean get() = fifoReservedEvents > 0 || fifoMaxEvents > 0
}

data class SensorHealth(
    val accelHz: Double = 0.0,
    val gyroHz: Double = 0.0,
    val attitudeHz: Double = 0.0,
    val accelInfo: SensorInfo? = null,
    val gyroInfo: SensorInfo? = null,
    val attitudeInfo: SensorInfo? = null,
    val gravitySource: GravitySource = GravitySource.ACCEL_LOWPASS,
    val pendingAccelSamples: Int = 0,
    val degradedFrames: Long = 0L
)

/**
 * 포그라운드 서비스 안에서만 사용하는 IMU 수집기.
 *
 * 설계 요점
 *  1. wake-up 센서를 우선 요청한다. non-wakeup 센서는 AP 서스펜드 구간에서 FIFO가 유실될 수 있다.
 *  2. maxReportLatencyUs로 하드웨어 배칭을 요청하되, FIFO가 없는 기기에서는 무시된다.
 *     그 사실을 SensorInfo.batchingSupported로 노출해 디버그 화면에서 확인할 수 있게 한다.
 *  3. 가속도/자이로/회전벡터가 서로 다른 배치로 도착하므로 이벤트 타임스탬프 기준으로 정합한다.
 *     "가장 최근 값 붙이기"는 배칭 환경에서 최대 1초까지 어긋난 값을 짝지어 버린다.
 */
class SensorSampler(
    context: Context,
    private val onFrame: (SensorFrame) -> Unit
) : SensorEventListener {

    private data class Timed(val ts: Long, val v: Vec3)

    private data class Sample(val v: Vec3?, val skewNs: Long)

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var thread: HandlerThread? = null

    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var attitudeSensor: Sensor? = null
    private var gravitySource = GravitySource.ACCEL_LOWPASS

    private val accelQ = ArrayDeque<Timed>()
    private val gyroQ = ArrayDeque<Timed>()
    private val gravQ = ArrayDeque<Timed>()

    private val accelRate = RateMeter()
    private val gyroRate = RateMeter()
    private val attitudeRate = RateMeter()

    private val gravityLpf = LowPass1PVec3(Constants.GRAVITY_LPF_CUTOFF_HZ)

    private var lastEmittedTsNs = 0L

    @Volatile
    private var degradedFrames = 0L

    @Volatile
    private var running = false

    /**
     * health()는 서비스 코루틴에서 호출되지만 RateMeter는 센서 스레드에서만 갱신된다.
     * 다른 스레드에서 직접 읽으면 ArrayDeque가 깨진 상태를 볼 수 있으므로,
     * 센서 스레드에서 주기적으로 스냅샷을 만들어 volatile 필드에 게시한다.
     */
    @Volatile
    private var cachedHealth = SensorHealth()

    private var healthPublishCounter = 0

    // 센서 콜백은 전용 HandlerThread 하나에서만 실행되므로 버퍼를 재사용해도 안전하다.
    private val rotationMatrix = FloatArray(9)
    private val rvBuffer = FloatArray(4)

    fun start(): Boolean {
        if (running) return true

        accelSensor = pickSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = pickSensor(Sensor.TYPE_GYROSCOPE)

        // 자기장을 쓰지 않는 GAME_ROTATION_VECTOR 우선.
        // 버스 내부 금속 구조물과 스피커 자석 때문에 자기장 기반 센서는 쓰지 않는다.
        attitudeSensor = pickSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        gravitySource = if (attitudeSensor != null) {
            GravitySource.GAME_ROTATION_VECTOR
        } else {
            attitudeSensor = pickSensor(Sensor.TYPE_GRAVITY)
            if (attitudeSensor != null) GravitySource.GRAVITY_SENSOR else GravitySource.ACCEL_LOWPASS
        }

        if (accelSensor == null || gyroSensor == null) {
            Log.e(TAG, "필수 센서 없음 accel=$accelSensor gyro=$gyroSensor")
            return false
        }

        val ht = HandlerThread("safedrive-sensor").also { it.start() }
        thread = ht
        val h = Handler(ht.looper)

        var ok = register(accelSensor, h)
        ok = register(gyroSensor, h) && ok
        attitudeSensor?.let { ok = register(it, h) && ok }

        running = ok
        if (!ok) stop() else publishHealth()
        return ok
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        accelQ.clear(); gyroQ.clear(); gravQ.clear()
        gravityLpf.reset()
        lastEmittedTsNs = 0L
    }

    /** 다른 스레드에서 안전하게 읽을 수 있는 최신 스냅샷. */
    fun health(): SensorHealth = cachedHealth

    /** 센서 스레드에서만 호출한다. */
    private fun publishHealth() {
        cachedHealth = SensorHealth(
            accelHz = accelRate.hz,
            gyroHz = gyroRate.hz,
            attitudeHz = attitudeRate.hz,
            accelInfo = accelSensor?.toInfo(),
            gyroInfo = gyroSensor?.toInfo(),
            attitudeInfo = attitudeSensor?.toInfo(),
            gravitySource = gravitySource,
            pendingAccelSamples = accelQ.size,
            degradedFrames = degradedFrames
        )
    }

    /** 가속도계 이벤트 타임스탬프 기준 마지막 수신 시각(ns). 끊김 감시에 사용. */
    fun lastAccelEventTsNs(): Long = accelRate.lastEventTsNs

    fun totalAccelEvents(): Long = accelRate.totalEvents

    // ------------------------------------------------------------------

    private fun pickSensor(type: Int): Sensor? {
        // wake-up 변종이 있으면 우선 사용한다. AP 서스펜드 구간에서도 FIFO가 보존되고
        // 배치 만료 시 AP를 깨워 전달하므로 화면 꺼짐 상태의 유실이 크게 줄어든다.
        return sensorManager.getDefaultSensor(type, true)
            ?: sensorManager.getDefaultSensor(type, false)
    }

    private fun register(sensor: Sensor?, h: Handler): Boolean {
        if (sensor == null) return false
        val ok = sensorManager.registerListener(
            this,
            sensor,
            Constants.SENSOR_SAMPLING_PERIOD_US,
            Constants.SENSOR_BATCH_LATENCY_US,
            h
        )
        if (!ok) Log.e(TAG, "registerListener 실패: " + sensor.name)
        return ok
    }

    private fun Sensor.toInfo() = SensorInfo(
        name = name,
        isWakeUp = isWakeUpSensor,
        fifoReservedEvents = fifoReservedEventCount,
        fifoMaxEvents = fifoMaxEventCount,
        minDelayUs = minDelay
    )

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        if (!running) return
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelRate.onEvent(e.timestamp)
                accelQ.addLast(Timed(e.timestamp, Vec3.of(e.values)))
                drain()
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroRate.onEvent(e.timestamp)
                gyroQ.addLast(Timed(e.timestamp, Vec3.of(e.values)))
                drain()
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                attitudeRate.onEvent(e.timestamp)
                gravQ.addLast(Timed(e.timestamp, gravityFromRotationVector(e.values)))
                drain()
            }

            Sensor.TYPE_GRAVITY -> {
                attitudeRate.onEvent(e.timestamp)
                gravQ.addLast(Timed(e.timestamp, Vec3.of(e.values)))
                drain()
            }
        }
    }

    /**
     * 회전벡터 -> 단말 좌표계 중력 벡터.
     *
     * getRotationMatrixFromVector가 주는 R은 world = R * device 관계다.
     * 월드 좌표계에서 가속도계가 정지 시 읽는 값은 위쪽 +g, 즉 (0, 0, g)이므로
     * 단말 좌표계로 되돌리면 R^T * (0,0,g) = R의 3번째 행 * g 가 된다.
     */
    private fun gravityFromRotationVector(values: FloatArray): Vec3 {
        // 일부 기기는 5개 원소를 준다. 구형 단말에서 길이 5 배열을 그대로 넘기면
        // 예외가 발생한 사례가 보고되어 앞 4개만 복사해 사용한다.
        java.util.Arrays.fill(rvBuffer, 0f)
        val n = minOf(values.size, 4)
        System.arraycopy(values, 0, rvBuffer, 0, n)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rvBuffer)
        val g = Constants.STANDARD_GRAVITY
        return Vec3(rotationMatrix[6] * g, rotationMatrix[7] * g, rotationMatrix[8] * g)
    }

    /** 보조 센서 큐가 무한정 자라지 않게 오래된 항목을 제거한다(정합용 1개는 남긴다). */
    private fun trim(q: ArrayDeque<Timed>) {
        val head = accelQ.firstOrNull()?.ts
        if (head != null) {
            while (q.size >= 2 && q[1].ts <= head) q.removeFirst()
        }
        while (q.size > Constants.AUX_QUEUE_MAX) q.removeFirst()
    }

    /**
     * 가속도 샘플 시각까지 보조 센서가 도착한 것만 정합해 내보낸다.
     * 보조 센서가 죽어 accelQ가 계속 자라면 degraded로 표시하고 강제로 흘려보낸다.
     */
    private fun drain() {
        while (accelQ.isNotEmpty()) {
            val a = accelQ.first()
            val force = accelQ.size > Constants.AUX_QUEUE_MAX
            val gyroReady = gyroQ.isNotEmpty() && gyroQ.last().ts >= a.ts
            val gravReady = gravitySource == GravitySource.ACCEL_LOWPASS ||
                (gravQ.isNotEmpty() && gravQ.last().ts >= a.ts)
            if (!force && !(gyroReady && gravReady)) break

            accelQ.removeFirst()

            val dtSec = if (lastEmittedTsNs == 0L) 0.0 else (a.ts - lastEmittedTsNs) / 1e9
            lastEmittedTsNs = a.ts

            val gyroSample = interpolate(gyroQ, a.ts)
            val gravSample = if (gravitySource == GravitySource.ACCEL_LOWPASS) {
                // 폴백 경로. 장시간 가감속을 중력으로 오인하므로 경사로 보정 신뢰도가 떨어진다.
                // 그 사실은 GravitySource로 함께 노출된다.
                Sample(gravityLpf.update(a.v, if (dtSec > 0.0) dtSec else 0.02), 0L)
            } else {
                interpolate(gravQ, a.ts)
            }

            val skew = maxOf(gyroSample.skewNs, gravSample.skewNs)
            val degraded = skew > Constants.AUX_MAX_SKEW_NS ||
                gyroSample.v == null || gravSample.v == null
            if (degraded) degradedFrames++

            trim(gyroQ)
            trim(gravQ)

            // 50Hz 기준 약 0.5초마다 갱신. UI 표시 주기보다 촘촘하면 낭비다.
            if (++healthPublishCounter >= HEALTH_PUBLISH_EVERY) {
                healthPublishCounter = 0
                publishHealth()
            }

            onFrame(
                SensorFrame(
                    timestampNs = a.ts,
                    dtSec = dtSec,
                    accel = a.v,
                    gyro = gyroSample.v ?: Vec3.ZERO,
                    gravity = gravSample.v ?: gravityLpf.value,
                    gravityFromRotationVector =
                        gravitySource == GravitySource.GAME_ROTATION_VECTOR,
                    degraded = degraded
                )
            )
        }
    }

    /** targetTs를 감싸는 두 샘플을 선형보간한다. 한쪽만 있으면 그 값을 쓰고 skew를 보고한다. */
    private fun interpolate(q: ArrayDeque<Timed>, targetTs: Long): Sample {
        if (q.isEmpty()) return Sample(null, Long.MAX_VALUE)
        var before: Timed? = null
        var after: Timed? = null
        for (s in q) {
            if (s.ts <= targetTs) before = s else { after = s; break }
        }
        val b = before
        val f = after
        return when {
            b != null && f != null -> {
                val span = (f.ts - b.ts).toFloat()
                val t = if (span <= 0f) 0f else (targetTs - b.ts) / span
                Sample(b.v + (f.v - b.v) * t, 0L)
            }
            b != null -> Sample(b.v, abs(targetTs - b.ts))
            f != null -> Sample(f.v, abs(f.ts - targetTs))
            else -> Sample(null, Long.MAX_VALUE)
        }
    }

    companion object {
        private const val TAG = "SensorSampler"
        private const val HEALTH_PUBLISH_EVERY = 25
    }
}
