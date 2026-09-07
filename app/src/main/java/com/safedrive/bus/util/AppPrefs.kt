package com.safedrive.bus.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 기기 내부 설정. 외부로 나가는 값은 없다.
 */
class AppPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("safedrive", Context.MODE_PRIVATE)

    /** 최초 실행 안내(참고용 고지 + 권한 + 배터리 최적화)를 마쳤는지 */
    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING, false)
        set(v) = sp.edit().putBoolean(KEY_ONBOARDING, v).apply()

    /**
     * 앱을 열면 수집을 자동으로 시작한다.
     *
     * 이 앱은 목적이 하나뿐이고 온보딩에서 이미 설명과 권한 동의를 받으므로,
     * 매번 시작 버튼을 누르게 하는 것은 불필요한 조작이다.
     * 이력만 확인하고 나간 경우가 기록에 남지 않도록, 거리 100m 미만이고 이벤트가 없는
     * 운행은 종료 시 자동으로 삭제한다.
     */
    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_START, v).apply()

    /**
     * 사용자가 직접 정지를 눌렀는지.
     *
     * 이 값이 true인 동안에는 자동 시작이 동작하지 않는다. 없으면 정지를 눌러도
     * 화면이 열려 있는 한 자동 시작이 즉시 다시 켜서 종료가 되지 않는다.
     * 앱을 새로 열면 해제된다.
     */
    var userStopped: Boolean
        get() = sp.getBoolean(KEY_USER_STOPPED, false)
        set(v) = sp.edit().putBoolean(KEY_USER_STOPPED, v).apply()

    /**
     * 진단 모드. 켜면 자이로 원시값을 기기 내부 파일로 저장한다.
     * 급진로변경·급앞지르기는 판정하지 않지만 향후 재검토를 위해 원시값만 남긴다.
     * 평상시에는 저장하지 않는다.
     */
    var diagnosticRecording: Boolean
        get() = sp.getBoolean(KEY_DIAG, false)
        set(v) = sp.edit().putBoolean(KEY_DIAG, v).apply()

    /**
     * 화면 꺼짐 구간에서 수집을 강제로 유지한다(PARTIAL_WAKE_LOCK).
     *
     * 기본값은 꺼짐이다. wake-up 센서 + 하드웨어 배칭으로 충분한 기기에서는 불필요하고
     * 8시간 운행 배터리에 불리하기 때문이다. 끊김 기록에 손실이 확인되면 켠다.
     */
    var keepAwake: Boolean
        get() = sp.getBoolean(KEY_KEEP_AWAKE, false)
        set(v) = sp.edit().putBoolean(KEY_KEEP_AWAKE, v).apply()

    /**
     * 글자 크기 배율.
     *
     * 기본값은 기기 해상도와 무관하게 모든 문구가 한 줄에 들어가도록 맞춘 값이다.
     * 화면이 작거나 시스템 글꼴을 크게 쓰는 기기에서 줄바꿈이 생기면 사용자가 줄일 수 있다.
     */
    var textScale: Float
        get() = sp.getFloat(KEY_TEXT_SCALE, 1.0f)
        set(v) = sp.edit().putFloat(KEY_TEXT_SCALE, v).apply()

    // ------------------------------------------------------------------
    // 세션 이어받기
    //
    // 제조사 절전 정책이 서비스를 종료시키면 START_STICKY로 되살아나는데,
    // 그때 운행 시작 시각과 누적 거리가 0으로 돌아가면 "경고 없이 N분"이
    // 실제 운행 시간보다 짧게 나온다. 최근 상태를 남겨 두었다가 이어받는다.
    // ------------------------------------------------------------------

    var sessionTripId: Long
        get() = sp.getLong(KEY_S_TRIP, 0L)
        set(v) = sp.edit().putLong(KEY_S_TRIP, v).apply()

    var sessionStartedAt: Long
        get() = sp.getLong(KEY_S_START, 0L)
        set(v) = sp.edit().putLong(KEY_S_START, v).apply()

    var sessionDistanceM: Float
        get() = sp.getFloat(KEY_S_DIST, 0f)
        set(v) = sp.edit().putFloat(KEY_S_DIST, v).apply()

    var sessionLastWarnAt: Long
        get() = sp.getLong(KEY_S_WARN_AT, 0L)
        set(v) = sp.edit().putLong(KEY_S_WARN_AT, v).apply()

    var sessionLastWarnDistanceM: Float
        get() = sp.getFloat(KEY_S_WARN_DIST, 0f)
        set(v) = sp.edit().putFloat(KEY_S_WARN_DIST, v).apply()

    /** 마지막 생존 신호. 이 값이 최근이면 서비스가 비정상 종료된 것으로 본다. */
    var sessionHeartbeat: Long
        get() = sp.getLong(KEY_S_BEAT, 0L)
        set(v) = sp.edit().putLong(KEY_S_BEAT, v).apply()

    /** 이번 운행에서 서비스가 되살아난 횟수. */
    var sessionRestartCount: Int
        get() = sp.getInt(KEY_S_RESTART, 0)
        set(v) = sp.edit().putInt(KEY_S_RESTART, v).apply()

    /** 정상 종료 시 호출. 다음 시작은 새 운행이 된다. */
    fun clearSession() {
        sp.edit()
            .remove(KEY_S_TRIP).remove(KEY_S_START).remove(KEY_S_DIST)
            .remove(KEY_S_WARN_AT).remove(KEY_S_WARN_DIST)
            .remove(KEY_S_BEAT).remove(KEY_S_RESTART)
            .apply()
    }

    private companion object {
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_S_TRIP = "session_trip_id"
        const val KEY_S_START = "session_started_at"
        const val KEY_S_DIST = "session_distance_m"
        const val KEY_S_WARN_AT = "session_last_warn_at"
        const val KEY_S_WARN_DIST = "session_last_warn_distance_m"
        const val KEY_S_BEAT = "session_heartbeat"
        const val KEY_S_RESTART = "session_restart_count"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_USER_STOPPED = "user_stopped"
        const val KEY_DIAG = "diagnostic_recording"
        const val KEY_KEEP_AWAKE = "keep_awake"
    }
}
