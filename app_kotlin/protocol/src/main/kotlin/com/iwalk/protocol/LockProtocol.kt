package com.iwalk.protocol

/**
 * 本应用已知的唯一出站车辆指令：两条固定锁帧。
 *
 * 静态证据（厂商 App 12.1.4，arm64 `libcpp_empty_test.so`）：
 * `TriggerLogic::onClickLock` → `Bluetooth::SendWriteCmd2` →
 * `SendFramePack(cmd=0x0A, len=2, a1=0x03, a2=0x70|0x71, payload=01 00)`，
 * 走 `_isSmallType != 0` 分支（非 M6 设备的默认值）。
 *
 * **2026-11-12 真车实测（E66，操作者确认停稳后发送）**：`0x70` 关锁与 `0x71` 解锁
 * 各发一次，车辆均实际响应（上锁/解锁）；HCI 报文采集确认每次滑动只产生一条对应写、
 * 无重试。链路上依旧没有任何车端回执，「已锁/已解锁」只能靠目视，不能靠协议回读。
 * 不要在这里追加其它命令字节、重试、设备探测或通用写接口。
 * 帧不携带状态回读：BLE 写入成功不能证明车辆已锁或已解锁（证据 E57：官方发送后无 ACK）。
 */
object LockProtocol {
    const val COMMAND = 0x0A
    const val A1 = 0x03
    const val LOCK_A2 = 0x70
    const val UNLOCK_A2 = 0x71
    const val PAYLOAD_VALUE = 1

    /** 明文锁帧字节。一条 = 一次写入，不重试、不回读。 */
    fun frame(lock: Boolean): ByteArray = build(lock).encode()

    /** 线上锁帧字节：整帧 ⊕ 0x55（规格第 7 节第 7 条）。 */
    fun wireFrame(lock: Boolean): ByteArray = build(lock).encodeWire()

    /** 小写十六进制，用于出站记录与证据导出。 */
    fun hex(lock: Boolean): String = frame(lock).toHex()

    private fun build(lock: Boolean): IbwalkFrame = IbwalkFrame(
        cmd = COMMAND,
        a1 = A1,
        a2 = if (lock) LOCK_A2 else UNLOCK_A2,
        payload = byteArrayOf(PAYLOAD_VALUE.toByte(), 0),
    )
}
