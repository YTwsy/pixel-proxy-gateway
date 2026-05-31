package com.wsy.pixelproxygateway

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRecoveryPolicyTest {
    @Test
    fun successfulProbeClearsFailures() {
        val decision = NetworkRecoveryPolicy.decide(
            result = HealthCheckResult(
                portOk = true,
                portMessage = "ok",
                requestOk = true,
                requestStatus = 204,
                requestError = "",
            ),
            previousFailures = 2,
            desiredRunning = true,
            nowMillis = 60_000,
            lastRestartAtMillis = 0,
        )

        assertEquals(NetworkRecoveryAction.CLEAR_FAILURES, decision.action)
        assertEquals(0, decision.nextFailures)
        assertEquals("port=ok request=ok status=204 error=none details=none", decision.summary)
    }

    @Test
    fun firstFailedProbeRetriesBeforeRestarting() {
        val decision = NetworkRecoveryPolicy.decide(
            result = failedRequest(),
            previousFailures = 0,
            desiredRunning = true,
            nowMillis = 60_000,
            lastRestartAtMillis = 0,
        )

        assertEquals(NetworkRecoveryAction.RETRY_PROBE, decision.action)
        assertEquals(1, decision.nextFailures)
    }

    @Test
    fun repeatedFailedProbeRestartsAfterThreshold() {
        val decision = NetworkRecoveryPolicy.decide(
            result = failedRequest(),
            previousFailures = 1,
            desiredRunning = true,
            nowMillis = 60_000,
            lastRestartAtMillis = 0,
        )

        assertEquals(NetworkRecoveryAction.RESTART_PROXY, decision.action)
        assertEquals(2, decision.nextFailures)
    }

    @Test
    fun restartCooldownSuppressesImmediateSecondRestart() {
        val decision = NetworkRecoveryPolicy.decide(
            result = failedRequest(),
            previousFailures = 1,
            desiredRunning = true,
            nowMillis = 60_000,
            lastRestartAtMillis = 45_000,
        )

        assertEquals(NetworkRecoveryAction.SUPPRESS_RESTART, decision.action)
        assertEquals(2, decision.nextFailures)
    }

    @Test
    fun stoppedProxySkipsProbeRecovery() {
        val decision = NetworkRecoveryPolicy.decide(
            result = failedRequest(),
            previousFailures = 1,
            desiredRunning = false,
            nowMillis = 60_000,
            lastRestartAtMillis = 0,
        )

        assertEquals(NetworkRecoveryAction.SKIP_STOPPED, decision.action)
        assertEquals(0, decision.nextFailures)
    }

    private fun failedRequest(): HealthCheckResult {
        return HealthCheckResult(
            portOk = true,
            portMessage = "ok",
            requestOk = false,
            requestStatus = 0,
            requestError = "timeout",
            requestResults = listOf(
                ProxyRequestResult("http", ok = true, status = 204, error = ""),
                ProxyRequestResult("socks5", ok = false, status = 0, error = "timeout"),
            ),
        )
    }
}
