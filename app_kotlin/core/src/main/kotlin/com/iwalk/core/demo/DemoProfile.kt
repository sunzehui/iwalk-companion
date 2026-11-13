package com.iwalk.core.demo

import com.iwalk.core.model.CalibrationProfile
import com.iwalk.core.model.CalibrationSet
import com.iwalk.core.model.FieldMapping
import com.iwalk.core.model.ScalarType
import com.iwalk.core.model.TelemetryField

/**
 * 演示标定（notes/domain-logic.md §3.6）。这些偏移描述的是演示生成器，不是真车；
 * UI 必须标为未验证。它存在只为在采集证据（U02/U03）之前把整条链路跑通。
 */
object DemoProfile {

    fun profile(): CalibrationProfile = CalibrationProfile(
        name = "demo-simulated (未验证)",
        cmd = DemoTransport.DEMO_CMD,
        a1 = DemoTransport.DEMO_A1,
        a2 = DemoTransport.DEMO_A2,
        evidence = "仅用于演示传输；真实字段偏移需真机采集后确定（U03）",
        fields = listOf(
            FieldMapping(TelemetryField.speed, 0, ScalarType.i16le, scale = 0.01),
            FieldMapping(TelemetryField.batteryPercent, 2, ScalarType.u8, scale = 1.0),
            FieldMapping(TelemetryField.temperature, 3, ScalarType.i16le, scale = 0.1),
            FieldMapping(TelemetryField.workMode, 5, ScalarType.u8),
            FieldMapping(TelemetryField.warningCode, 6, ScalarType.u8),
            FieldMapping(TelemetryField.estimatedRange, 7, ScalarType.u8, scale = 1.0),
        ),
    )

    fun set(): CalibrationSet = CalibrationSet(listOf(profile()))
}
