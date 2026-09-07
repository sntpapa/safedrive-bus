package com.safedrive.bus.core

/**
 * 판정 대상 8개 유형.
 *
 * 제외 항목(급진로변경, 급앞지르기, 연속운전)은 정의하지 않는다.
 * DB에 문자열로 저장되므로 name 값을 바꾸면 기존 기록을 읽을 수 없다.
 */
enum class EventType(
    val label: String,
    /** TTS로 읽어 줄 문구. 짧을수록 좋다. */
    val speech: String
) {
    OVERSPEED("과속", "과속"),
    LONG_OVERSPEED("장기과속", "장기 과속"),
    HARSH_ACCEL("급가속", "급가속"),
    HARSH_START("급출발", "급출발"),
    HARSH_DECEL("급감속", "급감속"),
    HARSH_STOP("급정지", "급정지"),
    SHARP_TURN("급좌우회전", "급회전"),
    SHARP_UTURN("급유턴", "급유턴");

    companion object {
        fun fromName(n: String): EventType? = entries.firstOrNull { it.name == n }
    }
}

/** 판정은 성립했으나 경고를 내보내지 않은 이유. */
enum class SuppressReason(val label: String) {
    NONE("경고 발생"),
    GATE_BLOCKED("게이트 차단"),
    DEBOUNCE("디바운스"),
    BORDERLINE_PITCH("경사로 보정 불가 · 경계값"),
    BORDERLINE_SHOCK("노면 충격 동반 · 경계값"),
    ABSORBED_BY_UTURN("급U턴에 포함됨"),
    CONFLICTING_DIRECTION("직전과 반대 방향 · 잡음 의심"),
    IMPLAUSIBLE("차량 성능 초과 · 측정 오류 의심")
}

/** 회전 방향. */
enum class TurnDirection(val label: String) {
    LEFT("좌"), RIGHT("우"), NONE("")
}
