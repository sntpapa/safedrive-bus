package com.safedrive.bus.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.EventType
import com.safedrive.bus.core.SuppressReason
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 내보내기 결과. 화면에 보여 줄 경로와 공유용 Uri를 함께 돌려준다. */
data class ExportResult(
    val displayPath: String,
    val uri: Uri?,
    /** 다운로드 폴더에 저장하지 못하고 앱 전용 폴더로 대체한 경우 사유 */
    val fallbackReason: String? = null
)

/**
 * 이벤트 이력 CSV 내보내기.
 *
 * 기사가 파일 관리자에서 바로 찾을 수 있어야 하므로 **다운로드 폴더**에 저장한다.
 * 앱이 스스로 어디론가 보내는 경로는 없고, 사용자가 공유 시트에서 직접 목적지를
 * 고를 때만 파일이 밖으로 나간다.
 */
object CsvExporter {

    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)
    private val rowStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

    private val HEADER = listOf(
        "발생시각", "유형", "속도_kmh", "판정값", "임계값",
        "IMU피크_kmh_per_s", "회전각_deg", "회전방향",
        "제한속도_kmh", "위도", "경도", "GPS정확도_m", "속도정확도_mps",
        "게이트_보정", "게이트_GPS", "게이트_연속성", "게이트_거치",
        "경고발생", "보류사유"
    ).joinToString(",")

    suspend fun exportRecent(context: Context, repo: TripRepository): ExportResult? {
        val since = System.currentTimeMillis() - Constants.HISTORY_DAYS * 24L * 60 * 60 * 1000
        val events = repo.eventsSince(since)
        val name = "safedrive_${fileStamp.format(Date())}.csv"

        // 안드로이드 10부터는 MediaStore로 다운로드 폴더에 바로 쓸 수 있다. 권한이 필요 없다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, name, events)?.let { return it }
        } else {
            writeToPublicDownloads(name, events)?.let { return it }
        }

        // 실패하면 앱 전용 폴더로 대체한다. 최소한 공유로는 꺼낼 수 있다.
        return writeToAppDir(context, name, events)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(
        context: Context,
        name: String,
        events: List<EventEntity>
    ): ExportResult? = try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            null
        } else {
            resolver.openOutputStream(uri)?.use { write(it, events) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            ExportResult("다운로드/$name", uri)
        }
    } catch (e: Exception) {
        Log.e(TAG, "MediaStore 저장 실패", e)
        null
    }

    @Suppress("DEPRECATION")
    private fun writeToPublicDownloads(name: String, events: List<EventEntity>): ExportResult? = try {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        file.outputStream().use { write(it, events) }
        ExportResult(file.absolutePath, null)
    } catch (e: Exception) {
        // API 28 이하에서는 저장소 권한이 필요하다. 없으면 여기서 실패한다.
        Log.e(TAG, "다운로드 폴더 저장 실패", e)
        null
    }

    private fun writeToAppDir(
        context: Context,
        name: String,
        events: List<EventEntity>
    ): ExportResult? = try {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "export")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        file.outputStream().use { write(it, events) }
        ExportResult(
            displayPath = file.absolutePath,
            uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            ),
            fallbackReason = "다운로드 폴더에 저장하지 못해 앱 전용 폴더에 저장했습니다."
        )
    } catch (e: Exception) {
        Log.e(TAG, "내보내기 실패", e)
        null
    }

    private fun write(out: OutputStream, events: List<EventEntity>) {
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            // 엑셀이 UTF-8을 한글로 제대로 읽게 하려면 파일 앞에 BOM이 필요하다.
            // 소스에 BOM 문자를 직접 넣으면 도구가 오해하므로 이스케이프로 쓴다.
            w.write("\uFEFF")
            w.write(HEADER)
            w.write("\n")
            for (e in events) {
                w.write(
                    listOf(
                        rowStamp.format(Date(e.occurredAtMs)),
                        EventType.fromName(e.type)?.label ?: e.type,
                        "%.1f".format(e.speedKmh),
                        "%.2f".format(e.judgedValue),
                        "%.1f".format(e.thresholdValue),
                        "%.2f".format(e.peakKmhPerSec),
                        "%.1f".format(e.turnAngleDeg),
                        e.turnDirection,
                        e.speedLimitKmh?.let { "%.0f".format(it) } ?: "",
                        "%.6f".format(e.latitude),
                        "%.6f".format(e.longitude),
                        "%.1f".format(e.gpsAccuracyM),
                        e.speedAccuracyMps?.let { "%.2f".format(it) } ?: "",
                        e.gateAlignment.yn(), e.gateGps.yn(),
                        e.gateContinuity.yn(), e.gateMount.yn(),
                        e.warned.yn(),
                        SuppressReason.valueOf(e.suppressReason).label
                    ).joinToString(",")
                )
                w.write("\n")
            }
        }
    }

    /** 사용자가 직접 목적지를 고르는 공유 시트. 앱이 스스로 보내지는 않는다. */
    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun Boolean.yn(): String = if (this) "Y" else "N"

    private const val TAG = "CsvExporter"
}
