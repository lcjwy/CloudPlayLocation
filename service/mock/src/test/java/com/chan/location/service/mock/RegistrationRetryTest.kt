package com.chan.location.service.mock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 注册重试状态机：节流、失败计数、放弃阈值、成功/位置关闭的清零语义 */
class RegistrationRetryTest {
    private var now = 0L

    private fun retry(
        maxFailures: Int,
        intervalMs: Long = 0L,
        onFirstFailure: () -> Unit = {},
        onGiveUp: () -> Unit = {},
    ) = RegistrationRetry(maxFailures, intervalMs, onFirstFailure, onGiveUp) { now }

    @Test
    fun attemptWithinThrottleWindowSkipsRegistration() {
        var calls = 0
        val retry = retry(maxFailures = 3, intervalMs = 1_000L)
        now = 5_000L
        assertFalse(
            retry.attempt {
                calls++
                false
            },
        )
        now = 5_500L // 距上次 500ms，未到 1s 节奏：不调用注册也不计数
        assertFalse(
            retry.attempt {
                calls++
                false
            },
        )
        assertEquals(1, calls)
    }

    @Test
    fun consecutiveFailuresGiveUpAtLimit() {
        var gaveUp = false
        val retry = retry(maxFailures = 2, onGiveUp = { gaveUp = true })
        now = 1_000L
        assertFalse(retry.attempt { false })
        now = 2_000L
        assertFalse(retry.attempt { false })
        assertTrue(gaveUp)
    }

    @Test
    fun successResetsFailureCount() {
        var gaveUp = false
        val retry = retry(maxFailures = 2, onGiveUp = { gaveUp = true })
        now = 1_000L
        assertFalse(retry.attempt { false })
        now = 2_000L
        assertTrue(retry.attempt { true })
        now = 3_000L
        assertFalse(retry.attempt { false })
        assertFalse(gaveUp)
    }

    @Test
    fun resetKeepsLocationOffFromGivingUp() {
        var gaveUp = false
        val retry = retry(maxFailures = 2, onGiveUp = { gaveUp = true })
        now = 1_000L
        assertFalse(retry.attempt { false })
        now = 2_000L // 系统位置关闭：reset 清零，连续失败不累积到放弃
        retry.reset()
        assertFalse(retry.attempt { false })
        now = 3_000L
        retry.reset()
        assertFalse(retry.attempt { false })
        assertFalse(gaveUp)
    }

    @Test
    fun firstFailureNotifiesOnlyOnce() {
        var notified = 0
        val retry = retry(maxFailures = 5, onFirstFailure = { notified++ })
        now = 1_000L
        retry.attempt { false }
        now = 2_000L
        retry.attempt { false }
        assertEquals(1, notified)
    }
}
