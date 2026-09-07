package com.safedrive.bus

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SafeDriveApp : Application() {

    companion object {
        /**
         * 서비스 종료 시점의 DB 마감처럼, 서비스 스코프가 취소된 뒤에도 끝까지
         * 실행돼야 하는 짧은 작업에만 쓴다.
         */
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
