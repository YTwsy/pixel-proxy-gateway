package com.wsy.pixelproxygateway

import org.junit.Assert.assertEquals
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
}
