package com.wsy.pixelproxygateway

enum class NetworkRecoveryAction {
    CLEAR_FAILURES,
    RETRY_PROBE,
    RESTART_PROXY,
    SUPPRESS_RESTART,
    SKIP_STOPPED,
}

data class NetworkRecoveryDecision(
    val action: NetworkRecoveryAction,
    val nextFailures: Int,
    val summary: String,
)

object NetworkRecoveryPolicy {
    const val PROBE_DELAY_SECONDS: Long = 5
    const val FAILURE_THRESHOLD: Int = 2
    const val RESTART_COOLDOWN_SECONDS: Long = 30

    fun decide(
        result: HealthCheckResult,
        previousFailures: Int,
        desiredRunning: Boolean,
        nowMillis: Long,
        lastRestartAtMillis: Long,
        failureThreshold: Int = FAILURE_THRESHOLD,
        restartCooldownMillis: Long = RESTART_COOLDOWN_SECONDS * 1_000,
    ): NetworkRecoveryDecision {
        if (!desiredRunning) {
            return NetworkRecoveryDecision(
                action = NetworkRecoveryAction.SKIP_STOPPED,
                nextFailures = 0,
                summary = "proxy stopped",
            )
        }

        if (result.ok) {
            return NetworkRecoveryDecision(
                action = NetworkRecoveryAction.CLEAR_FAILURES,
                nextFailures = 0,
                summary = probeSummary(result),
            )
        }

        val nextFailures = previousFailures + 1
        if (nextFailures < failureThreshold.coerceAtLeast(1)) {
            return NetworkRecoveryDecision(
                action = NetworkRecoveryAction.RETRY_PROBE,
                nextFailures = nextFailures,
                summary = probeSummary(result),
            )
        }

        val cooldownActive = lastRestartAtMillis > 0 && nowMillis - lastRestartAtMillis < restartCooldownMillis
        return NetworkRecoveryDecision(
            action = if (cooldownActive) NetworkRecoveryAction.SUPPRESS_RESTART else NetworkRecoveryAction.RESTART_PROXY,
            nextFailures = nextFailures,
            summary = probeSummary(result),
        )
    }

    fun probeSummary(result: HealthCheckResult): String {
        val port = if (result.portOk) "ok" else "fail"
        val request = if (result.requestOk) "ok" else "fail"
        val error = result.lastError.ifBlank { "none" }
        return "port=$port request=$request status=${result.requestStatus} error=$error"
    }
}
