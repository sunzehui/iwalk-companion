package com.iwalk.core.session

import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.PollQuery
import com.iwalk.protocol.kOfficialPollQueries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 官方 App 的两条固定查询调度器（notes/domain-logic.md §5.2）。
 *
 * 语义与 Dart 对齐：只写 [queries] 里的固定帧、一次一条、写失败立即停止且不重试、
 * 两条同时到期时索引小的先出。与 Dart 的一处有意差异：Dart 用固定 25 ms tick，
 * 首次 0x14 实际落在 125 ms；这里用「25 ms 上限 + 到点精确唤醒」，让 0x14/0xbc
 * 真正按 110/1010 ms 发出（工单 03 的验收节奏）。
 *
 * 调度器只构造明文帧；线上 XOR 0x55 由链路层调用 [IbwalkFrame.encodeWire] 完成。
 * 本类没有通用写接口，[write] 由链路层注入。
 */
class PollScheduler(
    private val scope: CoroutineScope,
    private val write: suspend (IbwalkFrame) -> Unit,
    private val queries: List<PollQuery> = kOfficialPollQueries,
    private val tickMillis: Long = 25,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile
    private var job: Job? = null

    @Volatile
    var sentCount: Int = 0
        private set

    @Volatile
    var lastError: Throwable? = null
        private set

    val isRunning: Boolean get() = job != null

    private var startedAtMillis: Long = 0
    private val nextAtMillis = mutableListOf<Long>()

    fun start() {
        if (job != null || queries.isEmpty()) return
        sentCount = 0
        lastError = null
        startedAtMillis = nowMillis()
        nextAtMillis.clear()
        nextAtMillis.addAll(queries.map { it.periodMs.toLong() })
        job = scope.launch {
            try {
                pump()
            } finally {
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pump() {
        while (currentCoroutineContext().isActive) {
            val elapsed = nowMillis() - startedAtMillis
            val index = queries.indices.firstOrNull { elapsed >= nextAtMillis[it] }
            if (index != null) {
                val query = queries[index]
                try {
                    write(query.build())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    return
                }
                sentCount += 1
                nextAtMillis[index] = nowMillis() - startedAtMillis + query.periodMs
                continue
            }
            val waitMillis = (nextAtMillis.min() - elapsed).coerceAtLeast(1L)
            delay(minOf(tickMillis, waitMillis))
        }
    }
}
