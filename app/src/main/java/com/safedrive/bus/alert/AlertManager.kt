package com.safedrive.bus.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import com.safedrive.bus.util.AppPrefs
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

    // 설정은 매번 읽는다. SharedPreferences는 메모리에 캐시되므로 비용이 없고,
    // 설정 변경을 서비스에 따로 전파할 필요가 없어진다.
    private val prefs = AppPrefs(context)

    private val audioAttributes = AudioAttributes.Builder()
        // 내비게이션 안내로 선언해야 다른 앱이 볼륨을 낮추고 경고가 묻히지 않는다.
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var focusRequest: AudioFocusRequest? = null

    /**
     * 음성 앞에 내는 짧은 경고음.
     *
     * 버스 실내 소음은 100~300Hz 저음에 몰려 있다. 2.2kHz 순음은 그 대역을 피하므로
     * 말보다 훨씬 잘 뚫고 나간다. 오디오 경로를 여는 역할도 겸한다.
     *
     * 음성과 같은 AudioAttributes를 써야 카오디오·블루투스로 함께 나간다.
     * ToneGenerator는 스트림 타입만 받아 라우팅이 갈리므로 쓰지 않는다.
     */
    private val toneBuffer: ShortArray by lazy { buildTone() }
    private var tonePlayer: AudioTrack? = null

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
                    // 값은 speak()에서 매번 적용한다. 설정에서 바꿀 수 있기 때문이다.
                    ttsReady = true
                }
            } else {
                Log.e(TAG, "TTS 초기화 실패: $status")
                ttsReady = false
            }
        }
    }

    fun stop() {
        tonePlayer?.release()
        tonePlayer = null
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
    /** 설정 화면의 시험 재생. 경고음과 음성만 내고 진동·화면 표시는 하지 않는다. */
    fun preview(text: String) {
        requestFocus()
        beep()
        speak(text)
    }

    fun warn(type: EventType, detail: String) {
        state = AlertState(
            type = type,
            headline = type.label,
            detail = detail,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + Constants.ALERT_DISPLAY_MS
        )
        vibrate()
        requestFocus()
        beep()
        speak(type.speech)
    }

    fun clearIfExpired() {
        if (state.type != null && !state.isActive()) state = AlertState()
    }

    private fun speak(text: String) {
        if (!prefs.speechEnabled) return
        val t = tts
        if (t == null || !ttsReady) return
        // 포커스는 warn()에서 이미 잡았다.

        // 오디오 경로가 열리기 전에 발화가 시작되면 첫 음절이 잘린다.
        // 포커스 전환과 라우팅에 블루투스는 200~500ms, 스피커도 50~150ms가 걸린다.
        //
        // 실차에서 "급감속"의 `급`이 들리지 않는다는 보고가 있었다. 하필 이 음절이
        // 가장 취약하다. `ㄱ`은 파열음, `ㅡ`는 에너지가 낮은 모음, `ㅂ` 받침은
        // 소리가 거의 없는 미파음이라 셋 다 짧고 약하다.
        //
        // 설정에서 바꿀 수 있으므로 발화 직전에 적용한다.
        t.setPitch(prefs.speechPitch)
        t.setSpeechRate(prefs.speechRate)

        // 무음을 먼저 흘려 경로를 열어 둔다. QUEUE_ADD로 이어 붙여야 사이가 안 벌어진다.
        t.playSilentUtterance(LEAD_SILENCE_MS, TextToSpeech.QUEUE_FLUSH, null)
        // QUEUE_ADD: 위 무음 뒤에 이어 말한다. 직전 경고는 위의 FLUSH가 이미 끊었다.
        t.speak(text, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
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

    private fun buildTone(): ShortArray {
        val n = TONE_SAMPLE_RATE * TONE_MS / 1000
        val fade = TONE_SAMPLE_RATE * 8 / 1000  // 8ms. 양 끝을 죽여 "딱" 소리를 없앤다.
        return ShortArray(n) { i ->
            val env = (minOf(i, n - i).toDouble() / fade).coerceAtMost(1.0)
            val v = kotlin.math.sin(2.0 * Math.PI * TONE_HZ * i / TONE_SAMPLE_RATE)
            (v * env * 0.9 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun beep() {
        if (!prefs.alertToneEnabled) return
        try {
            tonePlayer?.let {
                it.stop()
                it.reloadStaticData()
                it.setVolume(prefs.alertToneVolume)
                it.play()
                return
            }
            val buf = toneBuffer
            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(TONE_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buf.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, buf.size)
            track.setVolume(prefs.alertToneVolume)
            track.play()
            tonePlayer = track
        } catch (e: Exception) {
            Log.e(TAG, "경고음 실패", e)
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

        /**
         * 발화 앞에 흘리는 무음 길이.
         *
         * 오디오 경로가 열릴 시간을 준다. 경고음(120ms)이 같은 시점에 나가므로
         * 실질 추가 지연은 둘 중 긴 쪽이다. 경고 지연(배칭 1초 + TTS 합성)에
         * 더해지는 값이라 무한정 늘릴 수 없다.
         * 블루투스에서 여전히 첫 음절이 잘리면 올린다.
         */
        const val LEAD_SILENCE_MS = 150L

        const val TONE_SAMPLE_RATE = 44100
        const val TONE_MS = 120
        const val TONE_HZ = 2200.0
    }
}
