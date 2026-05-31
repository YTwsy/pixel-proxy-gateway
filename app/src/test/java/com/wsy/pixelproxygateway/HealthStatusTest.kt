package com.wsy.pixelproxygateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthStatusTest {
    @Test
    fun manualCheckSuccessUpdatesHealthAndClearsRequestFailures() {
        val status = RuntimeStatus(
            bindAddress = "0.0.0.0",
            httpPort = 8080,
            socksPort = 1080,
            portOk = false,
            requestOk = false,
            consecutiveFailures = 2,
            lastError = "old failure",
        )

        val updated = HealthStatus.applyManualCheck(
            status = status,
            config = ProxyConfig(httpPort = 18080, enableSocks = false, autoStart = true),
            result = HealthCheckResult(
                portOk = true,
                portMessage = "ok",
                requestOk = true,
                requestStatus = 204,
                requestError = "",
                requestResults = listOf(ProxyRequestResult("http", ok = true, status = 204, error = "")),
            ),
            checkedAt = "2026-05-21T10:00:00.000+08:00",
        )

        assertEquals(18080, updated.httpPort)
        assertFalse(updated.enableSocks)
        assertTrue(updated.autoStart)
        assertTrue(updated.portOk)
        assertTrue(updated.requestOk)
        assertEquals(204, updated.lastHttpStatus)
        assertEquals(0, updated.consecutiveFailures)
        assertEquals("", updated.lastError)
        assertTrue(updated.lastHttpProxyRequestOk)
        assertEquals(204, updated.lastHttpProxyStatus)
        assertFalse(updated.lastSocksProxyRequestOk)
        assertEquals("disabled", updated.lastSocksProxyError)
        assertEquals("2026-05-21T10:00:00.000+08:00", updated.lastRequestCheckAt)
    }

    @Test
    fun manualCheckFailureKeepsWatchdogFailureCountUntouched() {
        val status = RuntimeStatus(consecutiveFailures = 3)

        val updated = HealthStatus.applyManualCheck(
            status = status,
            config = ProxyConfig(),
            result = HealthCheckResult(
                portOk = true,
                portMessage = "ok",
                requestOk = false,
                requestStatus = 500,
                requestError = "unexpected status 500",
                requestResults = listOf(
                    ProxyRequestResult("http", ok = true, status = 204, error = ""),
                    ProxyRequestResult("socks5", ok = false, status = 500, error = "unexpected status 500"),
                ),
            ),
            checkedAt = "2026-05-21T10:01:00.000+08:00",
        )

        assertTrue(updated.portOk)
        assertFalse(updated.requestOk)
        assertEquals(500, updated.lastHttpStatus)
        assertEquals(3, updated.consecutiveFailures)
        assertEquals("unexpected status 500", updated.lastError)
        assertTrue(updated.lastHttpProxyRequestOk)
        assertFalse(updated.lastSocksProxyRequestOk)
        assertEquals("unexpected status 500", updated.lastSocksProxyError)
    }
}
