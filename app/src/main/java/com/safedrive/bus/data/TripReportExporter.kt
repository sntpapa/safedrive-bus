package com.safedrive.bus.data

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.EventType
import com.safedrive.bus.core.SuppressReason
import com.safedrive.bus.sensor.GravitySource
import com.safedrive.bus.sensor.SensorInfo
import com.safedrive.bus.service.TelemetrySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 운행 1회의 진단 리포트와 이벤트 CSV를 파일로 남긴다.
 *
 * 진단 탭 스크린샷을 대신한다. 스크린샷은 운행을 종료하기 전에, 폰을 거치대에서
 * 떼지 않고, 정차 후 3분 안에 찍어야 했다. 떼면 거치 편차가 올라가 재보정이 걸리고
 * (실측: 캡처 중 22.3°), 늦으면 값이 다음 운행 것으로 바뀐다(실측: `총 운행 38초`
 * 캡처에 전방축 샘플 0). 운행이 끝나는 순간 서비스가 직접 저장하면 셋 다 신경 쓸
 * 필요가 없다.
 *
 * 저장 위치: 다운로드/SafeDrive/
 *  - safedrive_trip_<시작시각>_report.txt  요약·보류 사유·부호 대조·보정·게이트·센서
 *  - safedrive_trip_<시작시각>_events.csv  이번 운행 이벤트만 (CSV 내보내기와 같은 컬럼)
 *
 * 절전 정책이 프로세스를 강제로 죽인 운행은 마감 코드가 돌지 못하므로 남지 않는다.
 * 그 경우는 이력 탭의 CSV 내보내기로 이벤트만 꺼낼 수 있다.
 */
object TripReportExporter {

    enum class Trigger(val label: String) {
        TRIP_END("운행 종료 시 자동 저장"),
        MIDNIGHT("자정 운행 분리 시 자동 저장"),
        MANUAL("진단 탭에서 수동 저장 · 운행 진행 중")
    }

    data class Result(val report: ExportResult, val events: ExportResult?)

    private const val SUB_DIR = "SafeDrive"

    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA)
    private val clockStamp = SimpleDateFormat("HHmmss", Locale.KOREA)
    private val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

    /** 부호 대조 대상. 표시 순서도 이 순서를 따른다. */
    private val LONGITUDINAL = listOf(
        EventType.HARSH_ACCEL, EventType.HARSH_START,
        EventType.HARSH_DECEL, EventType.HARSH_STOP
    )

    suspend fun save(
        context: Context,
        repo: TripRepository,
        tripId: Long,
        snap: TelemetrySnapshot,
        trigger: Trigger,
        liveDistanceM: Double? = null
    ): Result? {
        if (tripId == 0L) return null
        // 빈 운행은 마감 때 지워진다. 그때는 남길 것이 없다.
        val trip = repo.tripById(tripId) ?: return null
        val events = repo.eventListOf(tripId)

        var base = "safedrive_trip_${fileStamp.format(Date(trip.startedAtMs))}"
        // 운행 중 여러 번 저장해도 덮어쓰지 않도록 저장 시각을 붙인다.
        if (trigger == Trigger.MANUAL) base += "_at${clockStamp.format(Date())}"

        val text = buildReport(context, trip, events, snap, trigger, liveDistanceM)
        val report = CsvExporter.saveToDownloads(
            context, "${base}_report.txt", "text/plain", SUB_DIR
        ) { out ->
            out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        } ?: return null
        val csv = CsvExporter.saveToDownloads(
            context, "${base}_events.csv", "text/csv", SUB_DIR
        ) { out -> CsvExporter.writeEvents(out, events) }
        return Result(report, csv)
    }

    private fun buildReport(
        context: Context,
        trip: TripEntity,
        events: List<EventEntity>,
        snap: TelemetrySnapshot,
        trigger: Trigger,
        liveDistanceM: Double?
    ): String = buildString {
        val now = System.currentTimeMillis()
        fun line(s: String = "") = append(s).append('\n')
        fun kv(k: String, v: String) = line("  $k : $v")
        fun section(title: String) { line(); line("[$title]") }

        line("SafeDrive 운행 진단 리포트")
        line("=".repeat(40))
        kv("저장", "${timeStamp.format(Date(now))} · ${trigger.label}")
        kv("앱", appVersion(context))
        kv(
            "기기",
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})"
        )
        if (snap.tripId != 0L && snap.tripId != trip.id) {
            line("  ※ 아래 진단 값은 다른 운행(#${snap.tripId})의 것이다. 보정·게이트·센서는 참고만 할 것.")
        }

        // ------------------------------------------------------------------
        section("운행 요약")
        val ended = trip.endedAtMs != null
        val distanceKm = (liveDistanceM ?: trip.distanceM) / 1000.0
        val durationMs = (trip.endedAtMs ?: now) - trip.startedAtMs
        kv(
            "운행",
            "#${trip.id} · ${timeStamp.format(Date(trip.startedAtMs))} ~ " +
                (trip.endedAtMs?.let { timeStamp.format(Date(it)) } ?: "진행 중")
        )
        kv("운행시간", formatDuration(durationMs))
        kv("주행거리", "%.2f km".format(distanceKm))
        val gapMs = if (ended) trip.dataGapMs else snap.gaps.totalDataGapMs
        val gapCount = if (ended) trip.gapCount else snap.gaps.gapCount
        val stallMs = if (ended) trip.stallMs else snap.gaps.totalStallMs
        kv("감지 중단", "${formatDuration(gapMs)} · ${gapCount}구간")
        kv("전달 지연", formatDuration(stallMs))
        kv("서비스 재시작", "${snap.sessionRestartCount}회")
        val useTrip = ended && trip.limitSampleTotal > 0
        val unmatched = if (useTrip) trip.unmatchedLimitSamples else snap.judge.unmatchedLimitSamples
        val limitTotal = if (useTrip) trip.limitSampleTotal else snap.judge.limitSampleTotal
        kv(
            "과속 판정 보류",
            if (limitTotal <= 0L) "-"
            else "%.1f%% (%d/%d 프레임)".format(100.0 * unmatched / limitTotal, unmatched, limitTotal)
        )

        // ------------------------------------------------------------------
        section("유형별 건수")
        // 급U턴에 흡수된 급좌우회전은 이력 화면과 같이 집계에서 뺀다.
        val counted = events.filter { it.suppressReason != SuppressReason.ABSORBED_BY_UTURN.name }
        line("  유형 · 전체 · 경고 · 경고/100km")
        for (t in EventType.values()) {
            val ofType = counted.filter { it.type == t.name }
            if (ofType.isEmpty()) continue
            val warned = ofType.count { it.warned }
            line("  %s · %d · %d · %s".format(t.label, ofType.size, warned, per100km(warned, distanceKm)))
        }
        val totalWarned = counted.count { it.warned }
        line(
            "  합계 · %d · %d · %s".format(counted.size, totalWarned, per100km(totalWarned, distanceKm))
        )
        if (counted.isNotEmpty()) {
            line("  경고율 %.0f%%".format(100.0 * totalWarned / counted.size))
        }

        // ------------------------------------------------------------------
        section("보류 사유별")
        if (counted.isEmpty()) {
            line("  이벤트 없음")
        } else {
            counted.groupingBy { it.suppressReason }.eachCount()
                .entries.sortedByDescending { it.value }
                .forEach { (reason, n) ->
                    val label = runCatching { SuppressReason.valueOf(reason).label }.getOrDefault(reason)
                    line("  %4d  %s".format(n, label))
                }
        }

        // ------------------------------------------------------------------
        section("IMU 부호 대조 · 정렬 완료 이벤트")
        line("  GPS 판정값과 IMU 가감속의 부호가 같은 비율. 평균이 교차검증에 쓰는 값이다.")
        line("  평균이 70%를 넘으면 교차검증을 믿을 수 있다. 피크는 비교용이다.")
        var meanOkAll = 0
        var peakOkAll = 0
        var nAll = 0
        for (t in LONGITUDINAL) {
            val rs = counted.filter {
                it.type == t.name && it.gateAlignment && it.meanKmhPerSec != 0f
            }
            if (rs.isEmpty()) continue
            val meanOk = rs.count { sameSign(it.judgedValue, it.meanKmhPerSec) }
            val peakOk = rs.count { sameSign(it.judgedValue, it.peakKmhPerSec) }
            line(
                "  %s · 평균 %d/%d = %.0f%% · 피크 %.0f%%".format(
                    t.label, meanOk, rs.size, 100.0 * meanOk / rs.size, 100.0 * peakOk / rs.size
                )
            )
            meanOkAll += meanOk; peakOkAll += peakOk; nAll += rs.size
        }
        if (nAll == 0) {
            line("  대조할 이벤트 없음 (정렬 완료 후 가감속 이벤트가 없었음)")
        } else {
            line(
                "  전체 · 평균 %d/%d = %.0f%% · 피크 %.0f%%".format(
                    meanOkAll, nAll, 100.0 * meanOkAll / nAll, 100.0 * peakOkAll / nAll
                )
            )
        }

        // ------------------------------------------------------------------
        section("좌표계 보정 · 운행 전체")
        // 저장 시점 값은 종점에서 폰을 다루면 비어 버린다. 운행 전체 누적을 먼저 적는다.
        val a0 = snap.alignment
        kv(
            "정렬 완료 시간",
            "${formatDuration(a0.alignedTotalMs)} · 운행의 %.0f%%"
                .format(pct(a0.alignedTotalMs, durationMs))
        )
        kv(
            "부호 신뢰 시간",
            "${formatDuration(a0.trustedTotalMs)} · 정렬 시간의 %.0f%%"
                .format(pct(a0.trustedTotalMs, a0.alignedTotalMs))
        )
        val la = a0.lastAligned
        if (la == null) {
            kv("마지막으로 끝난 정렬 구간", "없음")
        } else {
            kv(
                "마지막으로 끝난 정렬 구간",
                "%s 종료 · %s 지속 · 부호 일치 %s · %d표본 · %s · 종료 사유: %s".format(
                    timeStamp.format(Date(la.endedWallMs)), formatDuration(la.durationMs),
                    if (la.signAgreement.isNaN()) "-" else "%.0f%%".format(la.signAgreement * 100),
                    la.signSamples, if (la.trusted) "신뢰" else "불신", la.endReason
                )
            )
        }

        section("좌표계 보정 · 저장 시점")
        val a = snap.alignment
        kv("상태", "${a.state.name} · 진행률 %.0f%%".format(a.overallProgress * 100))
        kv("전방축 샘플", "${a.forwardSamples} / ${Constants.FWD_MIN_SAMPLES}")
        kv("가감속 구간", "${a.forwardSegments} / ${Constants.FWD_MIN_SEGMENTS}")
        kv(
            "방향 집중도",
            if (a.eigenRatio <= 0.0) "-"
            else "%.1f / 기준 %.1f".format(a.eigenRatio, Constants.FWD_MIN_EIGEN_RATIO)
        )
        kv(
            "각도 표준편차",
            if (a.angleStdDeg.isNaN()) "-"
            else "%.1f° / 기준 %.1f°".format(a.angleStdDeg, Constants.FWD_MAX_ANGLE_STD_DEG)
        )
        val signTrusted = a.forwardSignTrusted
        kv(
            "전방축 부호 일치",
            if (a.forwardSignSamples == 0) "- (정렬 완료 후 표본이 쌓여야 나옴)"
            else "%.0f%% · %d표본 · 뒤집음 %d회 · %s".format(
                a.forwardSignAgreement * 100, a.forwardSignSamples, a.forwardFlipCount,
                if (signTrusted) "신뢰 (IMU 교차검증 사용)" else "불신 (수평 크기 검증으로 대체)"
            )
        )
        kv("거치 편차", "%.1f°".format(a.mountDeviationDeg))
        kv("중력 방향 변화율", "%.1f°/s".format(a.mountRateDps))
        kv(
            "재보정",
            "${a.invalidationCount}회" +
                (if (a.lastInvalidationReason.isNotEmpty()) " · 마지막: ${a.lastInvalidationReason}" else "")
        )
        if (a.invalidations.isNotEmpty()) {
            line("  재보정 이력 (최근 ${a.invalidations.size}건)")
            for (r in a.invalidations) {
                line(
                    "    %s · %.0f km/h · %s · 변화율 %.0f°/s · 편차 %.0f° · 자이로 %.0f°/s".format(
                        timeStamp.format(Date(r.wallMs)), r.speedKmh, r.reason,
                        r.rateDps, r.deviationDeg, r.gyroDps
                    )
                )
            }
        }
        kv(
            "정차 판정",
            "막는 조건 %s · 가속도 크기 %.3f · 누적 진동 %.3f · 중력 샘플 %d".format(
                a.stationaryBlockedBy.ifEmpty { "없음" },
                a.accelMagnitude, a.gravityStdDev, a.gravitySamples
            )
        )

        // ------------------------------------------------------------------
        section("경고 게이트 · 저장 시점")
        val g = snap.gates
        listOf(
            "좌표계 보정" to g.alignment,
            "GPS 품질" to g.gpsQuality,
            "센서 연속성" to g.sensorContinuity,
            "거치 안정성" to g.mountStability
        ).forEach { (name, gate) ->
            kv(name, "${if (gate.passed) "통과" else "막힘"} · ${gate.detail}")
        }

        // ------------------------------------------------------------------
        section("센서")
        val h = snap.health
        kv(
            "실측 샘플링률",
            "가속 %.1fHz / 자이로 %.1fHz / 자세 %.1fHz".format(h.accelHz, h.gyroHz, h.attitudeHz)
        )
        kv("가속도계", sensorInfo(h.accelInfo))
        kv("자이로스코프", sensorInfo(h.gyroInfo))
        kv("자세 센서", sensorInfo(h.attitudeInfo))
        kv(
            "중력 산출",
            when (h.gravitySource) {
                GravitySource.GAME_ROTATION_VECTOR -> "GAME_ROTATION_VECTOR (자기장 미사용)"
                GravitySource.GRAVITY_SENSOR -> "TYPE_GRAVITY 폴백"
                GravitySource.ACCEL_LOWPASS -> "가속도계 저역통과 폴백"
            }
        )
        kv("정합 오차 프레임", "${h.degradedFrames}건")
        val m = snap.motion
        kv(
            "GPS",
            (if (m.gpsAccuracyM == Float.MAX_VALUE) "정확도 -" else "정확도 %.1fm".format(m.gpsAccuracyM)) +
                " · " +
                (if (m.gpsAgeMs == Long.MAX_VALUE) "경과 -" else "경과 %.1f초".format(m.gpsAgeMs / 1000.0))
        )
        kv("음성 경고", if (snap.ttsAvailable) "사용 가능" else "불가 · 진동만")

        // ------------------------------------------------------------------
        section("제한속도")
        kv("도로 데이터", snap.roadDataSource.ifEmpty { "-" })
    }

    private fun sameSign(a: Float, b: Float): Boolean = (a >= 0f) == (b >= 0f)

    private fun pct(part: Long, whole: Long): Double =
        if (whole <= 0L) 0.0 else 100.0 * part / whole

    private fun per100km(count: Int, distanceKm: Double): String =
        // 1km 미만에서는 환산이 과장된다. 이력 화면과 같은 기준이다.
        if (distanceKm < 1.0) "-" else "%.1f".format(count * 100.0 / distanceKm)

    private fun sensorInfo(i: SensorInfo?): String =
        if (i == null) "없음"
        else "${i.name} · ${if (i.isWakeUp) "wake-up" else "non-wakeup"} · " +
            "FIFO ${i.fifoReservedEvents}/${i.fifoMaxEvents} · 최소 ${i.minDelayUs}us"

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%d시간 %d분".format(h, m)
            m > 0 -> "%d분 %d초".format(m, sec)
            else -> "%d초".format(sec)
        }
    }

    private fun appVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${PackageInfoCompat.getLongVersionCode(pi)})"
    } catch (e: Exception) {
        "-"
    }
}
