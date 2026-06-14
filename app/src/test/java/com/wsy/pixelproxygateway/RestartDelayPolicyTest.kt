package com.wsy.pixelproxygateway

import org.junit.Assert.assertEquals
import org.junit.Test

class RestartDelayPolicyTest {
    @Test
    fun nonImmediateRetriesBackOffUntilMaxDelay() {
        var delay = RestartDelayPolicy.INITIAL_DELAY_SECONDS

        val first = RestartDelayPolicy.next(delay, immediate = false)
        assertEquals(5L, first.delaySeconds)
        assertEquals(10L, first.nextDelaySeconds)

        delay = first.nextDelaySeconds
        val second = RestartDelayPolicy.next(delay, immediate = false)
        assertEquals(10L, second.delaySeconds)
        assertEquals(20L, second.nextDelaySeconds)

        val capped = RestartDelayPolicy.next(300L, immediate = false)
        assertEquals(300L, capped.delaySeconds)
        assertEquals(300L, capped.nextDelaySeconds)
    }

    @Test
    fun immediateRestartDoesNotConsumeBackoff() {
        val next = RestartDelayPolicy.next(40L, immediate = true)

        assertEquals(0L, next.delaySeconds)
        assertEquals(40L, next.nextDelaySeconds)
    }
}
