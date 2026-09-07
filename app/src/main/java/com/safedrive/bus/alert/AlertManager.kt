package com.safedrive.bus.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.safedrive.bus.core.Constants
import com.safedrive.bus.core.EventType
import java.util.Locale

/** 화면에 표시할 경고 상태. 화면은 보조 수단이므로 최소한만 담는다. */
data class AlertState(
    val type: EventType? = null,
    val headline: String = "",
    val detail: String = "",
    /** elapsedRealtime 기준 표시 종료 시각 */
    val expiresAtElapsedMs: Long = 0L
) {
    fun isActive(): Boolean =
        type != null && SystemClock.elapsedRealtime() < expiresAtElapsedMs
}

/**
 * 경고 전달.
 *
 * 주 수단은 TTS 음성 + 짧은 진동이다. 화면이 꺼져 있어도 동작해야 하므로
 * 화면 표시는 보조로만 쓴다. 기사가 화면을 읽어야만 내용을 알 수 있으면 안 된다.
 *
 * 오디오 포커스는 TRANSIENT_MAY_DUCK으로 요청한다. 버스 라디오나 내비 안내가
 * 나오는 중에도 잠깐 볼륨을 낮추고 경고가 들리게 하기 위함이다.
 */
class AlertManager(private val context: Context) {

    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioAttributes = AudioAttributes.Builder()
        // 내비게이션 안내로 선언해야 다른 앱이 볼륨을 낮추고 경고가 묻히지 않는다.
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var focusRequest: AudioFocusRequest? = null

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Volatile
    var state: AlertState = AlertState()
        private set

    fun start() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val res = t.setLanguage(Locale.KOREAN)
                if (res == TextToSpeech.LANG_MISSING_DATA ||
                    res == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.e(TAG, "한국어 TTS 데이터 없음. 진동만 동작한다.")
                    ttsReady = false
                } else {
                    t.setAudioAttributes(audioAttributes)
                    // 경고는 짧고 즉시 끝나야 한다. 기본 속도보다 약간 빠르게.
                    t.setSpeechRate(1.1f)
                    ttsReady = true
                }
            } else {
                Log.e(TAG, "TTS 초기화 실패: $status")
                ttsReady = false
            }
        }
    }

    fun stop() {
        abandonFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        state = AlertState()
    }

    val speechAvailable: Boolean get() = ttsReady

    /**
     * 경고 1회. 유형별 디바운스는 판정기에서 이미 처리하므로 여기서는 그대로 내보낸다.
     */
    fun warn(type: EventType, detail: String) {
        state = AlertState(
            type = type,
            headline = type.label,
            detail = detail,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + Constants.ALERT_DISPLAY_MS
        )
        vibrate()
        speak(type.speech)
    }

    fun clearIfExpired() {
        if (state.type != null && !state.isActive()) state = AlertState()
    }

    private fun speak(text: String) {
        val t = tts
        if (t == null || !ttsReady) return
        requestFocus()
        // QUEUE_FLUSH: 직전 경고가 아직 나가고 있으면 끊고 최신 것만 말한다.
        // 경고가 밀려서 뒤늦게 쏟아지면 오히려 방해가 된다.
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun vibrate() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createOneShot(
            Constants.ALERT_VIBRATION_MS,
            VibrationEffect.DEFAULT_AMPLITUDE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager
                vm?.vibrate(CombinedVibration.createParallel(effect)) ?: v.vibrate(effect)
            } else {
                v.vibrate(effect)
            }
        } catch (e: Exception) {
            Log.e(TAG, "진동 실패", e)
        }
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private companion object {
        const val TAG = "AlertManager"
        const val UTTERANCE_ID = "safedrive-warn"
    }
}
