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
     * 운행 시작은 앱 실행이 대신한다. 기사가 버튼을 찾아 누를 이유가 없다.
     * 운행 종료는 5분 이상 정차했을 때 앱이 먼저 물어본다.
     *
     * 이력만 확인하고 나간 경우가 기록에 남지 않도록, 거리 100m 미만이고 이벤트가 없는
     * 운행은 종료 시 자동으로 삭제한다.
     */
    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_START, v).apply()

    /**
     * 사용자가 직접 정지를 눌렀는지.
     *
     * 이 값이 true인 동안 자동 시작이 동작하지 않는다. 없으면 상단바 알림으로 정지해도
     * 앱이 떠 있는 한 자동 시작이 즉시 다시 켜서 종료가 되지 않는다.
     *
     * 앱을 새로 열면 해제된다. 뒤로가기로 닫으면 수집도 함께 멈추고, 앱을 여는 것은
     * 운행하겠다는 뜻이므로, 표시를 그보다 오래 들고 있을 이유가 없다.
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

    // ------------------------------------------------------------------
    // 경고음
    // ------------------------------------------------------------------

    /**
     * 음성 앞에 짧은 경고음을 낼지.
     *
     * 버스 실내 소음은 100~300Hz 저음에 몰려 있어 목소리가 묻힌다. 2.2kHz 순음은
     * 그 대역을 피하므로 잘 뚫고 나가고, 오디오 경로를 여는 역할도 겸한다.
     */
    var alertToneEnabled: Boolean
        get() = sp.getBoolean(KEY_TONE_ON, true)
        set(v) = sp.edit().putBoolean(KEY_TONE_ON, v).apply()

    /**
     * 경고음 크기 0~1.
     *
     * 처음에는 0.9 고정이었는데 실차에서 "너무 크다"는 보고를 받았다.
     * 기본값은 `작게`로 낮춘다. 값은 설정 화면의 선택지와 같아야 칩이 선택돼 보인다.
     */
    var alertToneVolume: Float
        get() = sp.getFloat(KEY_TONE_VOL, 0.25f).coerceIn(0f, 1f)
        set(v) = sp.edit().putFloat(KEY_TONE_VOL, v.coerceIn(0f, 1f)).apply()

    /**
     * 음성 경고를 낼지.
     *
     * 끄면 알림음과 진동만 남는다. 소음 속에서는 "삐" 한 번이 "급감속"보다 확실하고,
     * 무슨 유형이었는지는 정차 리뷰에서 확인할 수 있다.
     * 둘 다 끄면 진동만 남으므로 알림음이 꺼져 있을 때는 이것을 끌 수 없게 한다.
     */
    var speechEnabled: Boolean
        get() = sp.getBoolean(KEY_SPEECH_ON, true)
        set(v) = sp.edit().putBoolean(KEY_SPEECH_ON, v).apply()

    /** 음성 높낮이. 높을수록 엔진 소음 대역을 벗어나 잘 들린다. */
    var speechPitch: Float
        get() = sp.getFloat(KEY_TTS_PITCH, 1.15f).coerceIn(0.5f, 2.0f)
        set(v) = sp.edit().putFloat(KEY_TTS_PITCH, v.coerceIn(0.5f, 2.0f)).apply()

    /** 음성 속도. 경고는 짧고 즉시 끝나야 하므로 기본이 1.0보다 빠르다. */
    var speechRate: Float
        get() = sp.getFloat(KEY_TTS_RATE, 1.1f).coerceIn(0.5f, 2.0f)
        set(v) = sp.edit().putFloat(KEY_TTS_RATE, v.coerceIn(0.5f, 2.0f)).apply()

    /**
     * 글자 크기 배율.
     *
     * 기본값은 기기 해상도와 무관하게 모든 문구가 한 줄에 들어가도록 맞춘 값이다.
     * 화면이 작거나 시스템 글꼴을 크게 쓰는 기기에서 줄바꿈이 생기면 사용자가 줄일 수 있다.
     */
    var textScale: Float
        // 단계를 재정의하기 전에 저장된 0.80·0.90은 이제 선택지에 없다.
        // 그대로 두면 설정 화면에서 어느 칩도 선택되지 않으므로 최소 단계로 올린다.
        get() = sp.getFloat(KEY_TEXT_SCALE, 1.0f).coerceAtLeast(1.0f)
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
        const val KEY_TONE_ON = "alert_tone_on"
        const val KEY_SPEECH_ON = "speech_on"
        const val KEY_TONE_VOL = "alert_tone_volume"
        const val KEY_TTS_PITCH = "speech_pitch"
        const val KEY_TTS_RATE = "speech_rate"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_USER_STOPPED = "user_stopped"
        const val KEY_DIAG = "diagnostic_recording"
        const val KEY_KEEP_AWAKE = "keep_awake"
    }
}
