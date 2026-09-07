package com.safedrive.bus.sensor

import com.safedrive.bus.core.Vec3

/**
 * 타임스탬프 기준으로 정합된 한 시점의 IMU 관측값.
 *
 * 가속도계를 마스터 클록으로 삼고, 자이로/회전벡터는 같은 시각으로 보간해 붙인다.
 * 세 센서가 서로 다른 배치로 도착하므로 "가장 최근 값"을 그냥 붙이면 최대 1초까지
 * 어긋난 값이 짝지어질 수 있다. 그래서 SensorSampler에서 큐를 두고 정합한다.
 */
data class SensorFrame(
    /** SensorEvent.timestamp 기준 나노초 (기기 부팅 기준 단조 증가) */
    val timestampNs: Long,
    /** 직전 프레임과의 간격(초). 첫 프레임은 0. */
    val dtSec: Double,
    /** 단말 좌표계 가속도 [m/s^2]. 정지 시 위쪽으로 +g를 읽는다. */
    val accel: Vec3,
    /** 단말 좌표계 각속도 [rad/s] */
    val gyro: Vec3,
    /** 단말 좌표계 중력 벡터 [m/s^2]. GAME_ROTATION_VECTOR에서 산출. 위쪽이 +. */
    val gravity: Vec3,
    /** 중력 벡터를 회전벡터로부터 얻었는지 여부. false면 TYPE_GRAVITY 또는 LPF 대체값. */
    val gravityFromRotationVector: Boolean,
    /** 보조 센서 정합 오차가 허용치를 넘었으면 true. 이 프레임은 정렬 학습에 쓰지 않는다. */
    val degraded: Boolean
) {
    /** 중력을 제거한 선형가속도 [m/s^2]. 경사로에서도 중력 성분이 정확히 빠진다. */
    val linearAccel: Vec3 get() = accel - gravity
}
