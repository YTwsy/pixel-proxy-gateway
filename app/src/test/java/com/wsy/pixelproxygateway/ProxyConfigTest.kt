package com.wsy.pixelproxygateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigTest {
    @Test
    fun sanitizedClampsPortsAndWatchdogSettings() {
        val config = ProxyConfig(
            bindAddress = "  ",
            httpPort = 0,
            socksPort = 70_000,
            authEnabled = true,
            intervalSeconds = 5,
            timeoutSeconds = 1,
            failureThreshold = 100,
            username = "  user  ",
        ).sanitized()

        assertEquals("0.0.0.0", config.bindAddress)
        assertEquals(1, config.httpPort)
        assertEquals(65_535, config.socksPort)
        assertEquals(30, config.intervalSeconds)
        assertEquals(3, config.timeoutSeconds)
        assertEquals(20, config.failureThreshold)
        assertEquals("user", config.username)
    }

    @Test
    fun sanitizedClearsCredentialsWhenAuthIsDisabled() {
        val config = ProxyConfig(
            authEnabled = false,
            username = " user ",
            password = "secret",
        ).sanitized()

        assertEquals("", config.username)
        assertEquals("", config.password)
    }

    @Test
    fun startValidationRejectsNoListeners() {
        val error = ProxyConfig(enableHttp = false, enableSocks = false).startValidationError()

        assertEquals("At least one listener must be enabled", error)
    }

    @Test
    fun startValidationRejectsPortCollision() {
        val error = ProxyConfig(httpPort = 8080, socksPort = 8080).startValidationError()

        assertEquals("HTTP and SOCKS listeners cannot use the same port: 8080", error)
    }

    @Test
    fun startValidationAcceptsSingleListener() {
        val error = ProxyConfig(enableHttp = false, enableSocks = true, socksPort = 1080).startValidationError()

        assertNull(error)
    }
}
