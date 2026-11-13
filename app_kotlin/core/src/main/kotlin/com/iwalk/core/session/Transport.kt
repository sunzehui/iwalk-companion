package com.iwalk.core.session

import kotlinx.coroutines.flow.Flow

/**
 * 传输抽象（notes/domain-logic.md §4.1）。链路只有两个出站能力：
 * 固定轮询查询（:protocol 的 [com.iwalk.protocol.PollQuery]）与
 * [LockControl] 的一条固定锁帧。接口上没有"写入任意字节"。
 */

/** 一条通知：原始字节 + 它到达的特征。 */
data class IncomingPacket(
    val bytes: ByteArray,
    val serviceUuid: String? = null,
    val charUuid: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is IncomingPacket &&
        bytes.contentEquals(other.bytes) && serviceUuid == other.serviceUuid && charUuid == other.charUuid

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + (serviceUuid?.hashCode() ?: 0)
}

enum class TransportState { idle, connecting, connected, disconnected, error }

data class TransportEvent(val state: TransportState, val message: String? = null)

/** 应用对链路的全部认知：收通知、看事件，不写通用命令。 */
interface BluetoothTransport {
    val id: String
    val label: String
    val isDemo: Boolean

    /** 真车链路的 GATT/传输组事实；演示传输保持 null（§2.6）。 */
    val linkFacts: LinkFacts? get() = null

    val incoming: Flow<IncomingPacket>
    val events: Flow<TransportEvent>
    val state: TransportState

    /** 最后一条链路消息（BleTransport 在事件里同步保留，便于连接失败后立即读取）。 */
    val lastMessage: String? get() = null

    suspend fun connect()
    suspend fun disconnect()
}

/** 可选能力：两条静态推导的固定锁帧（2026-11-12 真车实测有效，E40/E57/E66）。 */
interface LockControl {
    val lockControlAvailable: Boolean

    suspend fun setLock(lock: Boolean)
}

/** 锁车指令失败；`toString()` 只有消息本身（§4.1，与 Dart 口径一致）。 */
class LockCommandException(message: String) : Exception(message) {
    override fun toString(): String = message ?: super.toString()
}
