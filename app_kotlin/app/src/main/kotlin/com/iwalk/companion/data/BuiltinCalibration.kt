package com.iwalk.companion.data

import com.iwalk.core.model.CalibrationSet

/**
 * 内置标定（2026-11-13 用户指令「将这些标定都打进去」）。
 *
 * 内容逐字来自 `downloads/iwk1779887-calibration-partial-v1.json`（E64/E71 真车证据），
 * 每条映射都对应证据日志中的采集验证，不含任何推测字段。用户未导入标定时自动生效；
 * 用户一旦导入或清除标定，以内置为兜底的链路即让位于用户数据（`load() ?: builtin`）。
 */
object BuiltinCalibration {

    private const val JSON = """{
  "profiles": [
    {
      "name": "iWK1779887-battery-only-v1",
      "cmd": 13,
      "a1": 1,
      "a2": 20,
      "evidence": "E57/E64：仅针对 i.walk:iWK1779887 的部分标定。应答组 0x0d/0x01/0x14，payload 从 0 起计的 off28 u8 为电量百分比。2026-10-21 官方截图 shot_03_baseline20s.png 为 83%，同会话原始 HCI 回放的 2368 条主状态均为 83；E71 复确认：2026-11-13 详细信息页同刻会话 off28=66（与页面电池状态时序一致）。速度、电压、温度及其他字段不映射；不保证适用于其他车型或固件。",
      "fields": [
        {
          "field": "batteryPercent",
          "offset": 28,
          "type": "u8",
          "scale": 1,
          "bias": 0
        }
      ]
    },
    {
      "name": "iWK1779887-range-only-v1",
      "cmd": 13,
      "a1": 1,
      "a2": 42,
      "evidence": "E71：仅针对 i.walk:iWK1779887 的预计里程标定。应答组 0x0d/0x01/0x2a（官方 App「详细信息」页轮询组，2026-11-13 06:39-06:47 同刻 HCI 报文采集），payload 从 0 起计的 off2 u8 为预计剩余行驶里程 km：806 帧中 13×629、14×174、12×3，与同刻页面「预计剩余行驶里程 13Km」一致（±1 波动属静止估算）。温度候选 off6 证据不足未映射；不保证适用于其他车型或固件。",
      "fields": [
        {
          "field": "estimatedRange",
          "offset": 2,
          "type": "u8",
          "scale": 1,
          "bias": 0
        }
      ]
    }
  ]
}"""

    /** 内置标定；JSON 损坏时为 null（此时回退空集，界面显示 —，绝不猜测）。 */
    val set: CalibrationSet? = try {
        CalibrationSet.decode(JSON)
    } catch (_: Exception) {
        null
    }
}
