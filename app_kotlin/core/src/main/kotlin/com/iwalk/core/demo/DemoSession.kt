package com.iwalk.core.demo

import com.iwalk.core.session.SessionManager
import kotlinx.coroutines.CoroutineScope

/**
 * 演示会话的装配规则（notes/domain-logic.md §4.6 + app_v2 providers.dart:69-73）：
 * 会话标定**故意**是 `DemoProfile.set()` 这份独立实例——它绝不回填用户标定，
 * 演示偏移永远不能被真车会话复用（工单 02 验收第 4 条）。
 */
object DemoSession {

    /** 会话 id：演示 `demo-<epochMillis>`、真车 `ble-<epochMillis>`（§4.6）。 */
    fun demoSessionId(nowMillis: Long): String = "demo-$nowMillis"

    fun bleSessionId(nowMillis: Long): String = "ble-$nowMillis"

    fun create(scope: CoroutineScope, transport: DemoTransport, sessionId: String): SessionManager =
        SessionManager(
            transport = transport,
            calibration = DemoProfile.set(),
            sessionId = sessionId,
            scope = scope,
        )
}
