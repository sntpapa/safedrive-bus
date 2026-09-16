package com.safedrive.bus.diag

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.safedrive.bus.loc.GpsSample
import com.safedrive.bus.sensor.SensorFrame
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 진단 모드 전용 원시값 기록기.
 *
 * 목적이 바뀌었다. 예전에는 급진로변경 재검토용으로 자이로만 남겼지만, 지금은
 * **판정 방식을 오프라인에서 통째로 재현**하기 위한 기록이다. 그러려면 센서만으로는
 * 부족하고 중력 방향과 GPS가 함께 있어야 한다. 이 파일 하나로 다음을 다시 계산할 수 있다.
 *  - 좌표계 정렬(중력·전방축)과 종방향 가속도
 *  - GPS 지연 추정과 융합 속도
 *  - 지금 방식·IMU 단독·융합 방식의 판정 건수 비교
 *
 * 저장 위치를 다운로드/SafeDrive 로 옮겼다. 예전 위치(앱 전용 폴더)는 최신 안드로이드에서
 * 기사가 파일을 꺼내기 어렵다.
 *
 * 크기: 100Hz 기준 2시간에 약 100MB. 평상시에는 꺼 두고 검증할 때만 켠다.
 */
class DiagnosticRecorder(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var pendingUri: android.net.Uri? = null
    private var path: String = ""
    private var rows = 0L

    val currentPath: String get() = path
    val rowCount: Long get() = rows

    fun start() {
        if (writer != null) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        val name = "safedrive_raw_$stamp.csv"
        try {
            val w = openDownloads(name) ?: openAppDir(name) ?: return
            w.write(HEADER)
            w.write("\n")
            writer = w
            rows = 0L
        } catch (e: Exception) {
            Log.e(TAG, "진단 파일 생성 실패", e)
            writer = null
        }
    }

    /**
     * @param longitudinalMps2 차량 좌표계 종가속도. 정렬 전이면 null.
     * @param gpsIsNewFix 이번 프레임에 새 GPS fix가 들어왔는지. 같은 값을 매 줄 반복해
     *   쓰면 파일이 두 배가 되므로 새 fix일 때만 채운다.
     */
    fun write(
        frame: SensorFrame,
        longitudinalMps2: Float?,
        gps: GpsSample?,
        gpsIsNewFix: Boolean
    ) {
        val w = writer ?: return
        try {
            val sb = StringBuilder(160)
            sb.append(frame.timestampNs).append(',')
                .append(fmt(frame.dtSec.toFloat())).append(',')
                .append(fmt(frame.accel.x)).append(',')
                .append(fmt(frame.accel.y)).append(',')
                .append(fmt(frame.accel.z)).append(',')
                .append(fmt(frame.gyro.x)).append(',')
                .append(fmt(frame.gyro.y)).append(',')
                .append(fmt(frame.gyro.z)).append(',')
                .append(fmt(frame.gravity.x)).append(',')
                .append(fmt(frame.gravity.y)).append(',')
                .append(fmt(frame.gravity.z)).append(',')
                .append(if (frame.gravityFromRotationVector) '1' else '0').append(',')
                .append(if (frame.degraded) '1' else '0').append(',')
                .append(if (longitudinalMps2 == null) "" else fmt(longitudinalMps2)).append(',')
            if (gpsIsNewFix && gps != null) {
                sb.append(fmt(gps.speedMps ?: Float.NaN)).append(',')
                    .append(fmt(gps.accuracyM)).append(',')
                    .append(fmt(gps.speedAccuracyMps ?: Float.NaN)).append(',')
                    .append(fmt(gps.bearingDeg ?: Float.NaN)).append(',')
                    .append(gps.wallMs).append(',')
                    .append(gps.latitude).append(',')
                    .append(gps.longitude)
            } else {
                sb.append(",,,,,,")
            }
            sb.append('\n')
            w.append(sb)
            rows++
        } catch (e: Exception) {
            Log.e(TAG, "진단 기록 실패", e)
            stop()
        }
    }

    fun stop() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        // MediaStore에 쓴 파일은 완료 표시를 해야 다른 앱에서 보인다.
        pendingUri?.let { uri ->
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            } catch (_: Exception) {
            }
        }
        pendingUri = null
    }

    private fun openDownloads(name: String): BufferedWriter? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val out = context.contentResolver.openOutputStream(uri) ?: return null
            pendingUri = uri
            path = "다운로드/$SUB_DIR/$name"
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024)
        } catch (e: Exception) {
            Log.e(TAG, "다운로드 폴더 저장 실패", e)
            null
        }
    }

    private fun openAppDir(name: String): BufferedWriter? = try {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diag")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        path = f.absolutePath
        BufferedWriter(OutputStreamWriter(f.outputStream(), Charsets.UTF_8), 64 * 1024)
    } catch (e: Exception) {
        Log.e(TAG, "진단 파일 생성 실패", e)
        null
    }

    /** 소수 넷째 자리면 충분하다. 기본 toString은 자릿수가 길어 파일이 커진다. */
    private fun fmt(v: Float): String =
        if (v.isNaN()) "" else String.format(Locale.US, "%.4f", v)

    private companion object {
        const val TAG = "DiagnosticRecorder"
        const val SUB_DIR = "SafeDrive"
        const val HEADER =
            "timestamp_ns,dt_sec," +
                "accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,grav_x,grav_y,grav_z," +
                "grav_from_rv,degraded,long_mps2," +
                "gps_speed_mps,gps_acc_m,gps_speed_acc_mps,gps_bearing_deg,gps_wall_ms,lat,lon"
    }
}
