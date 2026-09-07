package com.safedrive.bus.speedlimit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.safedrive.bus.core.Constants
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 표준노드링크 기반 제한속도 조회.
 *
 * 데이터는 tools/build_speedlimit_db.py 로 만든 SQLite를 assets에 넣어 둔 것이다.
 * 주행 중 네트워크를 쓰지 않는다. 터널·지하 구간에서도 조회가 끊기지 않고,
 * 8시간 운행 배터리에도 영향이 없다.
 *
 * 맵매칭
 *  1. 위경도 격자(0.002도) 9칸에서 후보 세그먼트를 뽑는다
 *  2. GPS 진행방위와 링크 방위가 크게 다르면 버린다 (반대 차선·교차 도로 제거)
 *  3. 가장 가까운 링크를 고르되, 2순위가 거의 같은 거리인데 제한속도가 다르면
 *     어느 도로인지 확정할 수 없다고 보고 null을 돌려준다 (과속 판정 보류)
 *
 * 틀린 제한속도로 경고하는 것보다 조용한 편이 낫다는 이 앱의 원칙을 그대로 따른다.
 */
class NodeLinkSpeedLimitProvider(private val context: Context) : SpeedLimitProvider {

    private class LinkRow(
        val effSpd: Int,
        val camSpd: Int?,
        val roadName: String?,
        val schoolSuspect: Boolean,
        val points: FloatArray   // lat, lon 교대
    )

    private var db: SQLiteDatabase? = null
    private var cellDeg = 0.002
    private var region = ""
    private var linkCount = 0

    /** 같은 도로를 계속 달리므로 최근 링크 몇 개만 들고 있어도 조회가 거의 사라진다. */
    private val cache = object : LinkedHashMap<Int, LinkRow>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, LinkRow>?): Boolean = size > 256
    }

    /** 직전에 매칭된 링크. 고가/지하차도처럼 평면상 겹치는 도로를 구분하는 데 쓴다. */
    private var lastLink: Int = -1

    override val sourceName: String
        get() = if (db == null) "도로 데이터 없음" else "표준노드링크 $region ${linkCount}개 구간"

    val ready: Boolean get() = db != null

    /** 최초 1회 assets의 DB를 내부 저장소로 복사하고 연다. */
    fun open(): Boolean {
        if (db != null) return true
        return try {
            val target = File(context.filesDir, DB_NAME)
            val stamp = File(context.filesDir, "$DB_NAME.version")
            // assets.openFd()는 압축되지 않은 파일에만 동작한다. .db는 APK에서 압축되므로
            // 크기로 버전을 비교하려던 방식이 항상 예외를 냈고, 도로 데이터가 통째로
            // 로드되지 않았다. 버전 문자열로 비교한다.
            if (!target.exists() || stamp.readTextOrNull() != DB_VERSION) {
                context.assets.open(DB_NAME).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                stamp.writeText(DB_VERSION)
                Log.i(TAG, "도로 데이터 복사 완료 ${target.length()} bytes")
            }
            val d = SQLiteDatabase.openDatabase(
                target.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            d.rawQuery("SELECT key, value FROM meta", null).use { c ->
                while (c.moveToNext()) {
                    when (c.getString(0)) {
                        "cell_deg" -> cellDeg = c.getString(1).toDoubleOrNull() ?: cellDeg
                        "region" -> region = c.getString(1)
                        "link_count" -> linkCount = c.getString(1).toIntOrNull() ?: 0
                    }
                }
            }
            db = d
            true
        } catch (e: Exception) {
            Log.e(TAG, "도로 데이터를 열지 못했습니다", e)
            db = null
            false
        }
    }

    fun close() {
        db?.close()
        db = null
        cache.clear()
        lastLink = -1
    }

    override fun matchAt(
        latitude: Double,
        longitude: Double,
        bearingDeg: Float?,
        speedKmh: Float
    ): SpeedLimitMatch? {
        val d = db ?: return null
        val cx = floor(longitude / cellDeg).toInt()
        val cy = floor(latitude / cellDeg).toInt()

        val useBearing = bearingDeg != null && speedKmh >= Constants.LINK_MATCH_MIN_SPEED_KMH

        // 1) 주변 9칸의 후보 세그먼트
        val candidates = ArrayList<IntArray>(64)  // link, seg, bearing
        d.rawQuery(
            "SELECT link, seg, bearing FROM seg_index " +
                "WHERE cx BETWEEN ? AND ? AND cy BETWEEN ? AND ?",
            arrayOf("${cx - 1}", "${cx + 1}", "${cy - 1}", "${cy + 1}")
        ).use { c ->
            while (c.moveToNext()) {
                val br = c.getInt(2)
                if (useBearing && angleDiff(br.toDouble(), bearingDeg!!.toDouble()) >
                    Constants.LINK_MATCH_BEARING_TOLERANCE_DEG
                ) continue
                candidates.add(intArrayOf(c.getInt(0), c.getInt(1), br))
            }
        }
        if (candidates.isEmpty()) return null

        // 2) 후보 링크 형상 확보
        val needed = candidates.map { it[0] }.distinct().filter { it !in cache }
        if (needed.isNotEmpty()) loadLinks(d, needed)

        // 3) 세그먼트별 거리
        val mLat = 111_132.0
        val mLon = 111_320.0 * cos(Math.toRadians(latitude))
        var bestLink = -1
        var bestDist = Double.MAX_VALUE
        var secondDist = Double.MAX_VALUE
        var secondSpd = -1
        for (cand in candidates) {
            val row = cache[cand[0]] ?: continue
            val i = cand[1] * 2
            if (i + 3 >= row.points.size) continue
            val dist = pointToSegmentM(
                latitude, longitude,
                row.points[i].toDouble(), row.points[i + 1].toDouble(),
                row.points[i + 2].toDouble(), row.points[i + 3].toDouble(),
                mLat, mLon
            )
            // 직전에 달리던 링크는 약간 우대한다. 고가도로와 그 아래 도로처럼
            // 평면상 겹치는 경우 경로 연속성이 유일한 단서다.
            val adjusted = if (cand[0] == lastLink) dist - CONTINUITY_BONUS_M else dist
            if (adjusted < bestDist) {
                if (bestLink != cand[0]) {
                    secondDist = bestDist
                    secondSpd = cache[bestLink]?.effSpd ?: -1
                }
                bestDist = adjusted
                bestLink = cand[0]
            } else if (cand[0] != bestLink && adjusted < secondDist) {
                secondDist = adjusted
                secondSpd = row.effSpd
            }
        }

        val row = cache[bestLink] ?: return null
        if (bestDist > Constants.LINK_MATCH_MAX_M) {
            lastLink = -1
            return null
        }

        // 4) 어느 도로인지 확정할 수 없으면 보류한다.
        if (secondSpd > 0 && secondSpd != row.effSpd &&
            secondDist - bestDist < Constants.LINK_MATCH_AMBIGUOUS_M
        ) {
            lastLink = -1
            return null
        }

        lastLink = bestLink
        return SpeedLimitMatch(
            limitKmh = row.effSpd.toDouble(),
            source = if (row.camSpd != null) "단속카메라 기준" else "표준노드링크",
            roadName = row.roadName,
            distanceM = max(0.0, bestDist),
            fromCamera = row.camSpd != null,
            schoolSuspect = row.schoolSuspect
        )
    }

    private fun loadLinks(d: SQLiteDatabase, ids: List<Int>) {
        val placeholders = ids.joinToString(",") { "?" }
        d.rawQuery(
            "SELECT id, eff_spd, cam_spd, road_name, school_suspect, geom " +
                "FROM links WHERE id IN ($placeholders) AND eff_spd IS NOT NULL",
            ids.map { it.toString() }.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(5)
                val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
                val pts = FloatArray(blob.size / 4)
                for (i in pts.indices) pts[i] = buf.getFloat(i * 4)
                cache[c.getInt(0)] = LinkRow(
                    effSpd = c.getInt(1),
                    camSpd = if (c.isNull(2)) null else c.getInt(2),
                    roadName = c.getString(3),
                    schoolSuspect = c.getInt(4) == 1,
                    points = pts
                )
            }
        }
    }

    private fun pointToSegmentM(
        plat: Double, plon: Double,
        alat: Double, alon: Double,
        blat: Double, blon: Double,
        mLat: Double, mLon: Double
    ): Double {
        val px = (plon - alon) * mLon
        val py = (plat - alat) * mLat
        val bx = (blon - alon) * mLon
        val by = (blat - alat) * mLat
        val len2 = bx * bx + by * by
        if (len2 < 1e-9) return hypot(px, py)
        val t = min(1.0, max(0.0, (px * bx + py * by) / len2))
        return hypot(px - t * bx, py - t * by)
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun File.readTextOrNull(): String? = try {
        if (exists()) readText() else null
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "NodeLinkSpeedLimit"
        const val DB_NAME = "speedlimit.db"

        /** 데이터를 다시 뽑으면 이 값을 바꾼다. 기기의 사본이 교체된다. */
        const val DB_VERSION = "daejeon-nodelink-2026-08-12"

        /** 직전 링크에 주는 거리 보정 [m]. 경로 연속성을 반영하되 뒤집기 쉬운 정도로만 준다. */
        const val CONTINUITY_BONUS_M = 4.0
    }
}
