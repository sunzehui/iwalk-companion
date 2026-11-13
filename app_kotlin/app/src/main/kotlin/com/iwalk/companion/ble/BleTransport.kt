package com.iwalk.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.iwalk.core.session.BluetoothTransport
import com.iwalk.core.session.IncomingPacket
import com.iwalk.core.session.LockControl
import com.iwalk.core.session.LockCommandException
import com.iwalk.protocol.LockProtocol
import com.iwalk.protocol.obfuscate
import com.iwalk.core.session.LinkFacts
import com.iwalk.core.session.PollScheduler
import com.iwalk.core.session.TransportEvent
import com.iwalk.core.session.TransportState
import com.iwalk.protocol.CCCD_UUID
import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.LinkBudget
import com.iwalk.protocol.TransportGroup
import com.iwalk.protocol.TransportPair
import com.iwalk.protocol.normaliseUuid
import com.iwalk.protocol.propertyFlags
import com.iwalk.protocol.selectTransport
import com.iwalk.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 真车链路（notes/domain-logic.md §2.6、§5.2）。
 *
 * 链路 bring-up 顺序：15 s 连接 → 服务发现 → 记录 GATT 树 → `selectTransport`
 * → 标准特征一次读取（2a00/2a01/2a04，失败记 `ERR`）→ 订阅通知 → 找写特征
 * → `connected` → 官方两条查询轮询。
 *
 * 安全边界：对外只有 [BluetoothTransport] 的收通知/连接接口；出站写入只有
 * 轮询调度器注入的两条固定查询，以及 LockControl 的固定锁帧。没有任意字节写入口。
 *
 * GATT 串行：所有 read / write / descriptor 操作都走同一个 [gattMutex]，一次只允许
 * 一个在途操作，避免 Android 并发 GATT 的 133 类错误。
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val advertisementName: String,
    override val id: String,
) : BluetoothTransport, LockControl {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _incoming = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 256)
    override val incoming = _incoming.asSharedFlow()

    private val _events = MutableSharedFlow<TransportEvent>(replay = 1, extraBufferCapacity = 16)
    override val events = _events.asSharedFlow()

    @Volatile
    override var state: TransportState = TransportState.idle
        private set

    override val label: String =
        advertisementName.ifEmpty { runCatching { device.name }.getOrNull().orEmpty() }

    override val isDemo: Boolean = false

    @Volatile
    override var linkFacts: LinkFacts? = null
        private set

    @Volatile
    override var lastMessage: String? = null
        private set

    private val lifecycleMonitor = Any()
    @Volatile private var closed = false
    @Volatile private var gatt: BluetoothGatt? = null
    private var pair: TransportPair? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var poller: PollScheduler? = null

    private val gattTree = mutableListOf<String>()
    private val standardReads = LinkedHashMap<String, String>()

    private val gattMutex = Mutex()
    private var connectionWaiter: CompletableDeferred<Boolean>? = null
    private var servicesWaiter: CompletableDeferred<Boolean>? = null
    private var readWaiter: Pair<UUID, CompletableDeferred<ByteArray?>>? = null
    private var writeWaiter: Pair<UUID, CompletableDeferred<Boolean>>? = null
    private var descriptorWaiter: Pair<Pair<UUID, UUID>, CompletableDeferred<Boolean>>? = null

    override suspend fun connect() {
        check(!closed) { "链路已关闭，请重新连接" }
        setState(TransportState.connecting)
        try {
            val g = connectGatt()
            ensureInitializing(g)
            if (!discoverServices(g)) error("服务发现超时")
            ensureInitializing(g)
            val services = g.services.orEmpty()
            recordServices(g, services)

            val servicesMap = services.associate { service ->
                normaliseUuid(service.uuid.toString()) to
                    service.characteristics.mapTo(mutableSetOf()) { normaliseUuid(it.uuid.toString()) }
            }
            val selected = selectTransport(servicesMap, label)
            pair = selected
            if (selected == null) {
                publishFacts(null)
                setState(TransportState.error, UNMATCHED_GROUP)
                return
            }

            readStandardChars(g, services)
            ensureInitializing(g)
            subscribeNotifications(g, services, selected)
            ensureInitializing(g)
            synchronized(lifecycleMonitor) {
                ensureInitializing(g)
                writeCharacteristic = findWriteCharacteristic(services, selected)
                publishFacts(selected)
                setState(TransportState.connected, "分组 ${selected.group.name}，写 ${selected.writeUuid}")
                startPolling(selected)
            }
        } catch (e: Exception) {
            closeGatt(TransportState.error, e.toString())
            throw e
        }
    }

    override val lockControlAvailable: Boolean
        get() = !closed && gatt != null && state == TransportState.connected && pair?.group == TransportGroup.g1NordicUart && writeCharacteristic != null

    override suspend fun setLock(lock: Boolean) {
        if (state != TransportState.connected) throw LockCommandException("链路未连接")
        if (!lockControlAvailable) throw LockCommandException("当前链路不支持固定锁帧")
        val g = gatt ?: throw LockCommandException("链路未连接")
        val writer = writeCharacteristic ?: throw LockCommandException("写特征不可用")
        if (!writeCharacteristic(g, writer, obfuscate(LockProtocol.frame(lock)))) {
            throw LockCommandException("BLE 写入失败，车端状态未确认")
        }
    }

    private fun failPendingOperations() {
        connectionWaiter?.complete(false)
        servicesWaiter?.complete(false)
        readWaiter?.second?.complete(null)
        writeWaiter?.second?.complete(false)
        descriptorWaiter?.second?.complete(false)
    }

    private fun ensureInitializing(g: BluetoothGatt) {
        check(!closed && gatt === g && state == TransportState.connecting) { "连接初始化已中断" }
    }

    /** Invalidate the connection before waking any queued operation. */
    private fun closeGatt(next: TransportState, message: String? = null) {
        val g = synchronized(lifecycleMonitor) {
            closed = true
            val previous = gatt
            gatt = null
            writeCharacteristic = null
            setState(next, message)
            poller?.stop()
            poller = null
            previous
        }
        failPendingOperations()
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        scope.cancel()
    }

    override suspend fun disconnect() = closeGatt(TransportState.disconnected)

    // ——————————————————————— 连接 / 发现 ———————————————————————

    private suspend fun connectGatt(): BluetoothGatt {
        val waiter = CompletableDeferred<Boolean>()
        connectionWaiter = waiter
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: error("connectGatt 返回 null")
        synchronized(lifecycleMonitor) {
            if (closed) { g.close(); error("连接已取消") }
            gatt = g
        }
        val connected = withTimeoutOrNull(15_000) { waiter.await() } ?: false
        connectionWaiter = null
        if (!connected) {
            runCatching { g.close() }
            error("连接超时或失败")
        }
        return g
    }

    private suspend fun discoverServices(g: BluetoothGatt): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        servicesWaiter = waiter
        if (!g.discoverServices()) {
            servicesWaiter = null
            return false
        }
        val ok = withTimeoutOrNull(15_000) { waiter.await() } ?: false
        servicesWaiter = null
        return ok
    }

    private fun recordServices(g: BluetoothGatt, services: List<BluetoothGattService>) {
        gattTree.clear()
        gattTree += "D ${device.address} mtu~${LinkBudget.defaultMtu}"
        for (service in services) {
            gattTree += "S ${service.uuid}"
            for (characteristic in service.characteristics) {
                val p = characteristic.properties
                val flags = propertyFlags(
                    read = p and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                    write = p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                    writeWithoutResponse = p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                    notify = p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                    indicate = p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                )
                val descriptors = characteristic.descriptors.map { it.uuid.toString() }
                val desc = if (descriptors.isEmpty()) "" else " desc=$descriptors"
                gattTree += "  C ${characteristic.uuid} [$flags]$desc"
            }
        }
    }

    private suspend fun readStandardChars(g: BluetoothGatt, services: List<BluetoothGattService>) {
        for (service in services) {
            for (characteristic in service.characteristics) {
                if (normaliseUuid(characteristic.uuid.toString()) !in STANDARD_READ_UUIDS) continue
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) continue
                val value = readCharacteristic(g, characteristic)
                standardReads[characteristic.uuid.toString()] = value?.toHex() ?: "ERR"
            }
        }
    }

    private suspend fun subscribeNotifications(
        g: BluetoothGatt,
        services: List<BluetoothGattService>,
        selected: TransportPair,
    ) {
        for (service in services) {
            val sameService = normaliseUuid(service.uuid.toString()) == normaliseUuid(selected.serviceUuid)
            for (characteristic in service.characteristics) {
                val notifiable = characteristic.properties and
                    (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                if (!notifiable) continue
                val matchesSelected =
                    normaliseUuid(characteristic.uuid.toString()) == normaliseUuid(selected.notifyUuid)
                if (!matchesSelected && !sameService) continue
                if (!g.setCharacteristicNotification(characteristic, true)) continue
                val descriptor = characteristic.getDescriptor(CCCD) ?: continue
                val indicationOnly = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                val cccdValue = if (indicationOnly) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                writeDescriptor(g, descriptor, cccdValue)
            }
        }
    }

    private fun findWriteCharacteristic(
        services: List<BluetoothGattService>,
        selected: TransportPair,
    ): BluetoothGattCharacteristic? {
        for (service in services) {
            if (normaliseUuid(service.uuid.toString()) != normaliseUuid(selected.serviceUuid)) continue
            for (characteristic in service.characteristics) {
                if (normaliseUuid(characteristic.uuid.toString()) != normaliseUuid(selected.writeUuid)) continue
                val writable = characteristic.properties and
                    (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                return if (writable) characteristic else null
            }
        }
        return null
    }

    // ——————————————————————— 串行 GATT 操作 ———————————————————————

    private suspend fun readCharacteristic(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): ByteArray? = gattMutex.withLock {
        val waiter = CompletableDeferred<ByteArray?>()
        readWaiter = characteristic.uuid to waiter
        try {
            val started = synchronized(lifecycleMonitor) {
                !closed && gatt === g && g.readCharacteristic(characteristic)
            }
            if (!started) return@withLock null
            withTimeoutOrNull(5_000) { waiter.await() }
        } finally {
            if (readWaiter?.first == characteristic.uuid) readWaiter = null
        }
    }

    private suspend fun writeDescriptor(
        g: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean = gattMutex.withLock {
        val key = descriptor.characteristic.uuid to descriptor.uuid
        val waiter = CompletableDeferred<Boolean>()
        descriptorWaiter = key to waiter
        try {
            val started = synchronized(lifecycleMonitor) {
                if (closed || gatt !== g) return@withLock false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(descriptor, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
            }
            if (!started) return@withLock false
            withTimeoutOrNull(5_000) { waiter.await() } ?: false
        } finally {
            if (descriptorWaiter?.first == key) descriptorWaiter = null
        }
    }

    private suspend fun writeCharacteristic(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean = gattMutex.withLock {
        if (gatt !== g || state != TransportState.connected) return@withLock false
        val waiter = CompletableDeferred<Boolean>()
        writeWaiter = characteristic.uuid to waiter
        val writeType = if (characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        try {
            val started = synchronized(lifecycleMonitor) {
                if (closed || gatt !== g || state != TransportState.connected) return@withLock false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(characteristic, bytes, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = writeType
                    @Suppress("DEPRECATION")
                    characteristic.value = bytes
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(characteristic)
                }
            }
            if (!started) return@withLock false
            withTimeoutOrNull(5_000) { waiter.await() } ?: false
        } finally {
            if (writeWaiter?.first == characteristic.uuid) writeWaiter = null
        }
    }

    // ——————————————————————— 轮询 ———————————————————————

    private fun startPolling(selected: TransportPair) {
        if (selected.group != TransportGroup.g1NordicUart) return
        val writer = writeCharacteristic
        if (writer == null) {
            setState(TransportState.connected, "写特征 ${selected.writeUuid} 不可用，无法轮询车端数据")
            return
        }
        poller?.stop()
        poller = PollScheduler(scope = scope, write = ::writePollFrame).also { it.start() }
    }

    private suspend fun writePollFrame(frame: IbwalkFrame) {
        val g = gatt ?: error("GATT 已断开")
        val writer = writeCharacteristic ?: error("写特征不可用")
        if (!writeCharacteristic(g, writer, frame.encodeWire())) error("BLE 写入失败")
    }

    // ——————————————————————— 回调 ———————————————————————

    private fun publishFacts(selected: TransportPair?) {
        linkFacts = LinkFacts(
            advertisementName = advertisementName,
            mtuNow = LinkBudget.defaultMtu,
            maxPayloadPerPacket = LinkBudget.maxPayloadPerPacket,
            transportGroup = selected?.group?.name,
            serviceUuid = selected?.serviceUuid,
            writeUuid = selected?.writeUuid,
            notifyUuid = selected?.notifyUuid,
            lockControl = if (selected?.group == TransportGroup.g1NordicUart && writeCharacteristic != null) {
                "fixed-g1-smalltype-experimental"
            } else {
                "unavailable"
            },
            writeCharacteristicAvailable = writeCharacteristic != null,
            gattTree = gattTree.toList(),
            standardReads = standardReads.toMap(),
        )
    }

    private fun setState(value: TransportState, message: String? = null) {
        state = value
        lastMessage = message
        _events.tryEmit(TransportEvent(value, message))
    }

    private fun emitPacket(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        _incoming.tryEmit(
            IncomingPacket(
                bytes = value.copyOf(),
                serviceUuid = characteristic.service?.uuid?.toString(),
                charUuid = characteristic.uuid.toString(),
            ),
        )
    }

    private fun completeRead(uuid: UUID, status: Int, value: ByteArray?) {
        val (pendingUuid, waiter) = readWaiter ?: return
        if (pendingUuid != uuid) return
        waiter.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionWaiter?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (gatt != null && gatt !== g) return
                    closeGatt(TransportState.disconnected)
                    runCatching { g.close() }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesWaiter?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(characteristic.uuid, status, value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            completeRead(characteristic.uuid, status, characteristic.value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val (pendingUuid, waiter) = writeWaiter ?: return
            if (pendingUuid != characteristic.uuid) return
            waiter.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val (pendingKey, waiter) = descriptorWaiter ?: return
            if (pendingKey != (descriptor.characteristic.uuid to descriptor.uuid)) return
            waiter.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emitPacket(characteristic, value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            emitPacket(characteristic, characteristic.value)
        }
    }

    private companion object {
        val CCCD: UUID = UUID.fromString(CCCD_UUID)

        /** 标准只读特征：设备名、外观、首选连接参数。 */
        val STANDARD_READ_UUIDS: Set<String> = setOf(
            "00002a00-0000-1000-8000-00805f9b34fb",
            "00002a01-0000-1000-8000-00805f9b34fb",
            "00002a04-0000-1000-8000-00805f9b34fb",
        )

        const val UNMATCHED_GROUP = "未匹配到已知传输组（G1–G5），仅展示服务树"
    }
}
