package com.wsy.pixelproxygateway

import org.json.JSONObject
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
            restartRules = RestartRules(
                networkRestartDelaySeconds = -1,
                networkRestartCooldownSeconds = 9_999,
                healthFailureThreshold = 100,
                portFailureThreshold = 100,
            ),
        ).sanitized()

        assertEquals("0.0.0.0", config.bindAddress)
        assertEquals(1, config.httpPort)
        assertEquals(65_535, config.socksPort)
        assertEquals(30, config.intervalSeconds)
        assertEquals(3, config.timeoutSeconds)
        assertEquals(20, config.failureThreshold)
        assertEquals("user", config.username)
        assertEquals(0L, config.restartRules.networkRestartDelaySeconds)
        assertEquals(3_600L, config.restartRules.networkRestartCooldownSeconds)
        assertEquals(20, config.restartRules.healthFailureThreshold)
        assertEquals(20, config.restartRules.portFailureThreshold)
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

    @Test
    fun fromJsonDefaultsRestartRulesToCurrentBehavior() {
        val config = ProxyConfig.fromJson(JSONObject())

        assertEquals(true, config.restartRules.networkRestartEnabled)
        assertEquals(3L, config.restartRules.networkRestartDelaySeconds)
        assertEquals(30L, config.restartRules.networkRestartCooldownSeconds)
        assertEquals(true, config.restartRules.ignoreDuplicateObservedCapabilities)
        assertEquals(true, config.restartRules.healthFailureRestartEnabled)
        assertEquals(2, config.restartRules.healthFailureThreshold)
        assertEquals(true, config.restartRules.portFailureRestartEnabled)
        assertEquals(1, config.restartRules.portFailureThreshold)
    }

    @Test
    fun restartRulesRoundTripThroughJson() {
        val original = ProxyConfig(
            restartRules = RestartRules(
                networkRestartEnabled = false,
                networkRestartDelaySeconds = 12,
                networkRestartCooldownSeconds = 90,
                ignoreDuplicateObservedCapabilities = false,
                healthFailureRestartEnabled = false,
                healthFailureThreshold = 4,
                portFailureRestartEnabled = false,
                portFailureThreshold = 3,
            ),
        )

        val restored = ProxyConfig.fromJson(original.toJson(includePassword = true))

        assertEquals(original.restartRules, restored.restartRules)
        assertEquals(4, restored.failureThreshold)
    }

    @Test
    fun legacyFailureThresholdFeedsDefaultRestartRules() {
        val config = ProxyConfig.fromJson(JSONObject().put("failureThreshold", 7))

        assertEquals(7, config.failureThreshold)
        assertEquals(7, config.restartRules.healthFailureThreshold)
    }
}
