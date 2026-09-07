package com.safedrive.bus.diag

import android.content.Context
import android.util.Log
import com.safedrive.bus.sensor.SensorFrame
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 진단 모드 전용 원시값 기록기.
 *
 * 급진로변경·급앞지르기는 판정하지 않기로 했으나, 향후 재검토가 가능하도록
 * 진단 모드가 켜진 경우에만 자이로 원시값을 파일로 남긴다. 평상시에는 저장하지 않는다.
 *
 * 저장 위치는 앱 전용 외부 저장소(Android/data/<pkg>/files/diag)이며 외부 전송은 하지 않는다.
 */
class DiagnosticRecorder(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var rows = 0L

    val currentFile: File? get() = file
    val rowCount: Long get() = rows

    fun start() {
        if (writer != null) return
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diag")
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
            val f = File(dir, "gyro_raw_$stamp.csv")
            val w = BufferedWriter(FileWriter(f), 64 * 1024)
            w.write("timestamp_ns,gyro_x,gyro_y,gyro_z,accel_x,accel_y,accel_z\n")
            writer = w
            file = f
            rows = 0L
        } catch (e: Exception) {
            Log.e(TAG, "진단 파일 생성 실패", e)
            writer = null
            file = null
        }
    }

    fun write(frame: SensorFrame) {
        val w = writer ?: return
        try {
            w.append(frame.timestampNs.toString()).append(',')
                .append(frame.gyro.x.toString()).append(',')
                .append(frame.gyro.y.toString()).append(',')
                .append(frame.gyro.z.toString()).append(',')
                .append(frame.accel.x.toString()).append(',')
                .append(frame.accel.y.toString()).append(',')
                .append(frame.accel.z.toString()).append('\n')
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
    }

    private companion object {
        const val TAG = "DiagnosticRecorder"
    }
}
