package com.wsy.pixelproxygateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class NetworkChangeRestartPolicyTest {
    @Test
    fun stoppedProxySkipsNetworkRestart() {
        val decision = NetworkChangeRestartPolicy.decide(
            desiredRunning = false,
            nowMillis = 1_000,
            activeNetworkKey = "wifi|wlan0",
            lastRestartAtMillis = 0,
            lastRestartNetworkKey = "",
        )

        assertEquals(NetworkChangeRestartAction.SKIP_STOPPED, decision.action)
    }

    @Test
    fun firstNetworkChangeAllowsRestart() {
        val decision = NetworkChangeRestartPolicy.decide(
            desiredRunning = true,
            nowMillis = 1_000,
            activeNetworkKey = "wifi|wlan0",
            lastRestartAtMillis = 0,
            lastRestartNetworkKey = "",
        )

        assertEquals(NetworkChangeRestartAction.RESTART, decision.action)
    }

    @Test
    fun sameNetworkInsideCooldownIsSuppressed() {
        val decision = NetworkChangeRestartPolicy.decide(
            desiredRunning = true,
            nowMillis = TimeUnit.SECONDS.toMillis(20),
            activeNetworkKey = "wifi|wlan0",
            lastRestartAtMillis = TimeUnit.SECONDS.toMillis(10),
            lastRestartNetworkKey = "wifi|wlan0",
        )

        assertEquals(NetworkChangeRestartAction.SUPPRESS_COOLDOWN, decision.action)
    }

    @Test
    fun disabledNetworkRestartSkipsRestart() {
        val decision = NetworkChangeRestartPolicy.decide(
            desiredRunning = true,
            nowMillis = 1_000,
            activeNetworkKey = "wifi|wlan0",
            lastRestartAtMillis = 0,
            lastRestartNetworkKey = "",
            rules = RestartRules(networkRestartEnabled = false),
        )

        assertEquals(NetworkChangeRestartAction.SKIP_DISABLED, decision.action)
    }

    @Test
    fun zeroCooldownAllowsSameNetworkRestart() {
        val decision = NetworkChangeRestartPolicy.decide(
            desiredRunning = true,
            nowMillis = TimeUnit.SECONDS.toMillis(20),
            activeNetworkKey = "wifi|wlan0",
            lastRestartAtMillis = TimeUnit.SECONDS.toMillis(10),
            lastRestartNetworkKey = "wifi|wlan0",
            rules = RestartRules(networkRestartCooldownSeconds = 0),
        )

        assertEquals(NetworkChangeRestartAction.RESTART, decision.action)
    }

    @Test
    fun differentNetworkInsideCooldownStillAllowsRestart() {
        val decision = NetworkChangeRestartPolicy.decide(
            desiredRunning = true,
            nowMillis = TimeUnit.SECONDS.toMillis(20),
            activeNetworkKey = "cellular|rmnet0",
            lastRestartAtMillis = TimeUnit.SECONDS.toMillis(10),
            lastRestartNetworkKey = "wifi|wlan0",
        )

        assertEquals(NetworkChangeRestartAction.RESTART, decision.action)
    }

    @Test
    fun duplicateObservedCapabilitiesWithoutIdentityChangeDoesNotScheduleRestart() {
        val networkKey = "event=114,wifi,wlan0,192.168.1.103,114.114.114.114,8.8.8.8"

        val decision = NetworkChangeRestartPolicy.observedRestartEligibility(
            event = "observed_capabilities_changed",
            observedNetworkKey = networkKey,
            previousObservedNetworkKey = networkKey,
        )

        assertFalse(decision.scheduleRestart)
        assertEquals("observed_network_identity_unchanged", decision.summary)
    }

    @Test
    fun duplicateObservedCapabilitiesCanScheduleRestartWhenFilteringIsDisabled() {
        val networkKey = "event=114,wifi,wlan0,192.168.1.103,114.114.114.114,8.8.8.8"

        val decision = NetworkChangeRestartPolicy.observedRestartEligibility(
            event = "observed_capabilities_changed",
            observedNetworkKey = networkKey,
            previousObservedNetworkKey = networkKey,
            rules = RestartRules(ignoreDuplicateObservedCapabilities = false),
        )

        assertTrue(decision.scheduleRestart)
    }

    @Test
    fun observedCapabilitiesWithIdentityChangeStillSchedulesRestart() {
        val decision = NetworkChangeRestartPolicy.observedRestartEligibility(
            event = "observed_capabilities_changed",
            observedNetworkKey = "event=114,wifi,wlan0,192.168.1.103,8.8.8.8",
            previousObservedNetworkKey = "event=113,cellular,rmnet0,10.0.0.2,8.8.8.8",
        )

        assertTrue(decision.scheduleRestart)
    }

    @Test
    fun observedLinkPropertiesChangeSchedulesRestartEvenForKnownNetwork() {
        val networkKey = "event=114,wifi,wlan0,192.168.1.103,8.8.8.8"

        val decision = NetworkChangeRestartPolicy.observedRestartEligibility(
            event = "observed_link_properties_changed",
            observedNetworkKey = networkKey,
            previousObservedNetworkKey = networkKey,
        )

        assertTrue(decision.scheduleRestart)
    }
}
