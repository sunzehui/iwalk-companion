package com.iwalk.core.session

import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.PollQuery
import com.iwalk.protocol.kOfficialPollQueries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** notes/domain-logic.md §5.2：节奏、优先级、单飞与失败即停。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollSchedulerTest {

    private data class Sent(val at: Long, val cmd: Int, val a2: Int)

    @Test
    fun `official queries fire at 110 and 1010 ms with 0x14 first`() = runTest {
        val sent = mutableListOf<Sent>()
        val scheduler = PollScheduler(
            scope = this,
            write = { sent += Sent(testScheduler.currentTime, it.cmd, it.a2) },
            nowMillis = { testScheduler.currentTime },
        )
        scheduler.start()
        advanceTimeBy(1_150)
        scheduler.stop()

        assertEquals(0x14, sent.first().a2)
        assertEquals(110L, sent.first().at)
        assertEquals(1010L, sent.first { it.a2 == 0x2a }.at)
        // 110, 220, ..., 1100 = 10 次 0x14；1010 一次 0x2a（E71：0x2a 替换 0xbc 时隙）。
        assertEquals(10, sent.count { it.a2 == 0x14 })
        assertEquals(1, sent.count { it.a2 == 0x2a })
        assertEquals(11, scheduler.sentCount)
        assertNull(scheduler.lastError)
    }

    @Test
    fun `same-due queries pick the lower index`() = runTest {
        val order = mutableListOf<Int>()
        val queries = listOf(
            PollQuery(0x0A, 0x01, 0x14, listOf(0x26), 100),
            PollQuery(0x0A, 0x01, 0xBC, listOf(0x02), 100),
        )
        val scheduler = PollScheduler(
            scope = this,
            write = { order += it.a2 },
            queries = queries,
            nowMillis = { testScheduler.currentTime },
        )
        scheduler.start()
        advanceTimeBy(101)
        scheduler.stop()
        assertEquals(listOf(0x14, 0xBC), order)
    }

    @Test
    fun `a write failure stops the schedule without retry or count`() = runTest {
        var attempts = 0
        val scheduler = PollScheduler(
            scope = this,
            write = {
                attempts += 1
                error("write failed")
            },
            nowMillis = { testScheduler.currentTime },
        )
        scheduler.start()
        advanceTimeBy(1_000)
        assertEquals(1, attempts)
        assertEquals(0, scheduler.sentCount)
        assertNotNull(scheduler.lastError)
        assertFalse(scheduler.isRunning)
    }

    @Test
    fun `stop cancels further writes`() = runTest {
        var attempts = 0
        val scheduler = PollScheduler(
            scope = this,
            write = { attempts += 1 },
            nowMillis = { testScheduler.currentTime },
        )
        scheduler.start()
        advanceTimeBy(200)
        scheduler.stop()
        val afterStop = attempts
        advanceTimeBy(1_000)
        assertEquals(afterStop, attempts)
        assertFalse(scheduler.isRunning)
    }

    @Test
    fun `start is a no-op when already running or empty`() = runTest {
        var attempts = 0
        val scheduler = PollScheduler(
            scope = this,
            write = { attempts += 1 },
            nowMillis = { testScheduler.currentTime },
        )
        scheduler.start()
        scheduler.start()
        advanceTimeBy(1_000)
        scheduler.stop()
        assertTrue(attempts <= 10)

        val empty = PollScheduler(
            scope = this,
            write = { attempts += 1 },
            queries = kOfficialPollQueries.take(0),
            nowMillis = { testScheduler.currentTime },
        )
        empty.start()
        advanceTimeBy(500)
        assertFalse(empty.isRunning)
        assertEquals(0, empty.sentCount)
    }

    @Test
    fun `slow writes delay the next query instead of bursting`() = runTest {
        val at = mutableListOf<Long>()
        val scheduler = PollScheduler(
            scope = this,
            write = {
                at += testScheduler.currentTime
                kotlinx.coroutines.delay(50)
            },
            nowMillis = { testScheduler.currentTime },
        )
        scheduler.start()
        advanceTimeBy(400)
        scheduler.stop()
        // 第一次 110，写入耗时 50 → 下一次 = 160 + 110 = 270；160 不会补发第二条。
        assertEquals(listOf(110L, 270L), at)
    }
}
