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

        val text = status.toText()
        assertTrue(text.contains("statusUpdatedAt=2026-05-17T05:00:00.000+08:00"))
        assertTrue(text.contains("statusUpdatedAtEpochMillis=1779000000000"))
        assertTrue(text.contains("serviceRunning=true"))
        assertTrue(text.contains("desiredRunning=true"))
        assertTrue(text.contains("proxyRunning=true"))
        assertTrue(text.contains("startOnBoot=true"))
        assertTrue(text.contains("autoStart=true"))
        assertTrue(text.contains("restartCount=7"))
    }
}
