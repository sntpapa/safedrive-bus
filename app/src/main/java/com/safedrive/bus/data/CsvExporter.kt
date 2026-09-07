package com.safedrive.bus.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.EventType
import com.safedrive.bus.core.SuppressReason
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 이벤트 이력 CSV 내보내기.
 *
 * 파일은 앱 전용 저장소에 만든다. 앱이 스스로 어디론가 보내는 경로는 없고,
 * 사용자가 공유 시트에서 직접 목적지를 고를 때만 파일이 밖으로 나간다.
 */
object CsvExporter {

    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)
    private val rowStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

    private val HEADER = listOf(
        "발생시각", "유형", "속도_kmh", "최대가감속_kmh_per_s", "회전각_deg", "회전방향",
        "임계값", "제한속도_kmh", "위도", "경도", "GPS정확도_m",
        "게이트_보정", "게이트_GPS", "게이트_연속성", "게이트_거치",
        "경고발생", "보류사유"
    ).joinToString(",")

    suspend fun exportRecent(context: Context, repo: TripRepository): File? {
        val since = System.currentTimeMillis() - Constants.HISTORY_DAYS * 24L * 60 * 60 * 1000
        val events = repo.eventsSince(since)

        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "export")
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, "safedrive_${fileStamp.format(Date())}.csv")

        return try {
            file.bufferedWriter(Charsets.UTF_8).use { w ->
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
                            "%.2f".format(e.peakKmhPerSec),
                            "%.1f".format(e.turnAngleDeg),
                            e.turnDirection,
                            "%.1f".format(e.thresholdValue),
                            e.speedLimitKmh?.let { "%.0f".format(it) } ?: "",
                            "%.6f".format(e.latitude),
                            "%.6f".format(e.longitude),
                            "%.1f".format(e.gpsAccuracyM),
                            e.gateAlignment.yn(), e.gateGps.yn(),
                            e.gateContinuity.yn(), e.gateMount.yn(),
                            e.warned.yn(),
                            SuppressReason.valueOf(e.suppressReason).label
                        ).joinToString(",")
                    )
                    w.write("\n")
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /** 사용자가 직접 목적지를 고르는 공유 시트. 앱이 스스로 보내지는 않는다. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun Boolean.yn(): String = if (this) "Y" else "N"
}
