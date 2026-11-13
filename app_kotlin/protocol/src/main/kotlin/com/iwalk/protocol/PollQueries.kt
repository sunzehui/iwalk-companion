package com.iwalk.protocol

/**
 * 官方 App 的固定查询集（notes/domain-logic.md §5.1 + E71 修订）。
 *
 * 语料与节奏来自厂商 App（`com.loby.balance.minirobot` 5.4.1）真车 HCI 报文采集。
 * 只有这两条属于官方客户端；另一次报文采集里看到的 18 条查询来自我们自己的诊断构建。
 *
 * 载荷字节 = 期望应答长度（0x14 答 38 字节，0x2a 答 34 字节）。
 *
 * E71（2026-11-13）修订：第二条从 0xbc 换成 0x2a。0xbc 在本机型只回 `bebe`
 * 哨兵、无任何信息量；0x2a 是官方「详细信息」页真实轮询的查询（同刻 HCI
 * `captures/20261113_detail`：0x1a/0x2a 约 0.4 s 交替，比本调度器的 1010 ms
 * 更密），载荷 off2 = 预计剩余里程 km。周期保持 1010 ms 与原 0xbc 时隙相同，
 * 总线负载不变，不影响 0x14 的 110 ms 节奏与回包率。
 */
class PollQuery(
    val cmd: Int,
    val a1: Int,
    val a2: Int,
    val payload: List<Int>,
    val periodMs: Int,
) {
    /** 调度器只构造明文帧；线上混淆由 [IbwalkFrame.encodeWire] 完成。 */
    fun build(): IbwalkFrame = IbwalkFrame(
        cmd = cmd,
        a1 = a1,
        a2 = a2,
        payload = ByteArray(payload.size) { i -> payload[i].toByte() },
    )

    override fun toString(): String =
        "cmd=0x${cmd.toString(16)} a2=0x${a2.toString(16)} @${periodMs}ms"
}

/** 列表顺序即优先级：两条同时到期时 0x14 先出。 */
val kOfficialPollQueries: List<PollQuery> = listOf(
    // 0x14：状态帧（序列号、电压在载荷 18–19、电量在 28）
    PollQuery(0x0A, 0x01, 0x14, listOf(0x26), 110),
    // 0x2a：详细信息帧（预计里程在载荷 2，E71 同刻锚定）；替换原 0xbc 哨兵时隙
    PollQuery(0x0A, 0x01, 0x2A, listOf(0x22), 1010),
)
