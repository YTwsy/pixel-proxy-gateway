package com.wsy.pixelproxygateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStatusTest {
    @Test
    fun textAndJsonIncludeStatusFreshnessAndCoreHealth() {
        val status = RuntimeStatus(
            statusUpdatedAt = "2026-05-17T05:00:00.000+08:00",
            statusUpdatedAtEpochMillis = 1_779_000_000_000,
            serviceRunning = true,
            desiredRunning = true,
            proxyRunning = true,
            proxyPid = 1234,
            startOnBoot = true,
            autoStart = true,
            portOk = true,
            requestOk = true,
            restartCount = 7,
            lastRestartReason = "process_exit:1",
            lastError = "",
            lastNetworkEventAt = "2026-05-17T05:01:00.000+08:00",
            lastNetworkEvent = "available seq=2",
            lastNetworkSummary = "active=100 transports=wifi",
            lastNetworkRestartAt = "2026-05-17T05:01:08.000+08:00",
            lastNetworkRestartReason = "network_change:available seq=2 active=100",
            networkRestartCount = 3,
        )

        val json = status.toJson()
        assertEquals("2026-05-17T05:00:00.000+08:00", json.getString("statusUpdatedAt"))
        assertEquals(1_779_000_000_000, json.getLong("statusUpdatedAtEpochMillis"))
        assertEquals(true, json.getBoolean("serviceRunning"))
        assertEquals(true, json.getBoolean("desiredRunning"))
        assertEquals(true, json.getBoolean("proxyRunning"))
        assertEquals(true, json.getBoolean("startOnBoot"))
        assertEquals(true, json.getBoolean("autoStart"))
        assertEquals(1234, json.getLong("proxyPid"))
        assertEquals(7, json.getInt("restartCount"))
        assertEquals("available seq=2", json.getString("lastNetworkEvent"))
        assertEquals(3, json.getInt("networkRestartCount"))

        val text = status.toText()
        assertTrue(text.contains("statusUpdatedAt=2026-05-17T05:00:00.000+08:00"))
        assertTrue(text.contains("statusUpdatedAtEpochMillis=1779000000000"))
        assertTrue(text.contains("serviceRunning=true"))
        assertTrue(text.contains("desiredRunning=true"))
        assertTrue(text.contains("proxyRunning=true"))
        assertTrue(text.contains("startOnBoot=true"))
        assertTrue(text.contains("autoStart=true"))
        assertTrue(text.contains("restartCount=7"))
        assertTrue(text.contains("lastNetworkEvent=available seq=2"))
        assertTrue(text.contains("lastNetworkRestartReason=network_change:available seq=2 active=100"))
        assertTrue(text.contains("networkRestartCount=3"))
    }
}
