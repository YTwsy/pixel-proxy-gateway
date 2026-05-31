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
            lastHttpProxyRequestOk = true,
            lastHttpProxyStatus = 204,
            lastHttpProxyError = "",
            lastSocksProxyRequestOk = false,
            lastSocksProxyStatus = 0,
            lastSocksProxyError = "timeout",
            lastProxyRequestSummary = "http=ok status=204 error=none; socks5=fail status=0 error=timeout",
            restartCount = 7,
            lastRestartReason = "process_exit:1",
            lastError = "",
            lastNetworkEventAt = "2026-05-17T05:01:00.000+08:00",
            lastNetworkEvent = "available seq=3",
            lastNetworkSummary = "transports=wifi capabilities=internet,validated if=wlan0 ipv4=192.168.1.103",
            lastNetworkProbeAt = "2026-05-17T05:01:05.000+08:00",
            lastNetworkProbeResult = "port=ok request=ok status=204 error=none",
            networkRecoveryFailures = 1,
            networkRecoveryRestartCount = 2,
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
        assertEquals(true, json.getBoolean("lastHttpProxyRequestOk"))
        assertEquals(204, json.getInt("lastHttpProxyStatus"))
        assertEquals(false, json.getBoolean("lastSocksProxyRequestOk"))
        assertEquals("timeout", json.getString("lastSocksProxyError"))
        assertEquals("available seq=3", json.getString("lastNetworkEvent"))
        assertEquals("port=ok request=ok status=204 error=none", json.getString("lastNetworkProbeResult"))
        assertEquals(1, json.getInt("networkRecoveryFailures"))
        assertEquals(2, json.getInt("networkRecoveryRestartCount"))

        val text = status.toText()
        assertTrue(text.contains("statusUpdatedAt=2026-05-17T05:00:00.000+08:00"))
        assertTrue(text.contains("statusUpdatedAtEpochMillis=1779000000000"))
        assertTrue(text.contains("serviceRunning=true"))
        assertTrue(text.contains("desiredRunning=true"))
        assertTrue(text.contains("proxyRunning=true"))
        assertTrue(text.contains("startOnBoot=true"))
        assertTrue(text.contains("autoStart=true"))
        assertTrue(text.contains("restartCount=7"))
        assertTrue(text.contains("lastHttpProxyRequestOk=true"))
        assertTrue(text.contains("lastSocksProxyError=timeout"))
        assertTrue(text.contains("lastProxyRequestSummary=http=ok status=204 error=none; socks5=fail status=0 error=timeout"))
        assertTrue(text.contains("lastNetworkEvent=available seq=3"))
        assertTrue(text.contains("lastNetworkProbeResult=port=ok request=ok status=204 error=none"))
        assertTrue(text.contains("networkRecoveryRestartCount=2"))
    }
}
