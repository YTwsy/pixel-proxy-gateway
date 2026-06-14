package com.wsy.pixelproxygateway

import java.util.concurrent.TimeUnit

data class RestartRules(
    val networkRestartEnabled: Boolean = true,
    val networkRestartDelaySeconds: Long = DEFAULT_NETWORK_RESTART_DELAY_SECONDS,
    val networkRestartCooldownSeconds: Long = DEFAULT_NETWORK_RESTART_COOLDOWN_SECONDS,
    val ignoreDuplicateObservedCapabilities: Boolean = true,
    val healthFailureRestartEnabled: Boolean = true,
    val healthFailureThreshold: Int = DEFAULT_HEALTH_FAILURE_THRESHOLD,
    val portFailureRestartEnabled: Boolean = true,
    val portFailureThreshold: Int = DEFAULT_PORT_FAILURE_THRESHOLD,
) {
    fun sanitized(): RestartRules {
        return copy(
            networkRestartDelaySeconds = networkRestartDelaySeconds.coerceIn(
                MIN_NETWORK_RESTART_DELAY_SECONDS,
                MAX_NETWORK_RESTART_DELAY_SECONDS,
            ),
            networkRestartCooldownSeconds = networkRestartCooldownSeconds.coerceIn(
                MIN_NETWORK_RESTART_COOLDOWN_SECONDS,
                MAX_NETWORK_RESTART_COOLDOWN_SECONDS,
            ),
            healthFailureThreshold = healthFailureThreshold.coerceIn(
                MIN_FAILURE_THRESHOLD,
                MAX_FAILURE_THRESHOLD,
            ),
            portFailureThreshold = portFailureThreshold.coerceIn(
                MIN_FAILURE_THRESHOLD,
                MAX_FAILURE_THRESHOLD,
            ),
        )
    }

    companion object {
        const val DEFAULT_NETWORK_RESTART_DELAY_SECONDS = 3L
        const val DEFAULT_NETWORK_RESTART_COOLDOWN_SECONDS = 30L
        const val DEFAULT_HEALTH_FAILURE_THRESHOLD = 2
        const val DEFAULT_PORT_FAILURE_THRESHOLD = 1
        const val MIN_NETWORK_RESTART_DELAY_SECONDS = 0L
        const val MAX_NETWORK_RESTART_DELAY_SECONDS = 300L
        const val MIN_NETWORK_RESTART_COOLDOWN_SECONDS = 0L
        const val MAX_NETWORK_RESTART_COOLDOWN_SECONDS = 3_600L
        const val MIN_FAILURE_THRESHOLD = 1
        const val MAX_FAILURE_THRESHOLD = 20
    }
}

object NetworkChangeRestartPolicy {
    fun decide(
        desiredRunning: Boolean,
        nowMillis: Long,
        activeNetworkKey: String,
        lastRestartAtMillis: Long,
        lastRestartNetworkKey: String,
        rules: RestartRules = RestartRules(),
    ): NetworkChangeRestartDecision {
        if (!desiredRunning) {
            return NetworkChangeRestartDecision(
                action = NetworkChangeRestartAction.SKIP_STOPPED,
                summary = "proxy is not desired running",
            )
        }

        val sanitizedRules = rules.sanitized()
        if (!sanitizedRules.networkRestartEnabled) {
            return NetworkChangeRestartDecision(
                action = NetworkChangeRestartAction.SKIP_DISABLED,
                summary = "network restart is disabled",
            )
        }

        val cooldownMillis = TimeUnit.SECONDS.toMillis(sanitizedRules.networkRestartCooldownSeconds)
        val sameNetwork = activeNetworkKey.isNotBlank() && activeNetworkKey == lastRestartNetworkKey
        val elapsedMillis = nowMillis - lastRestartAtMillis
        if (cooldownMillis > 0 && sameNetwork && lastRestartAtMillis > 0 && elapsedMillis in 0 until cooldownMillis) {
            val remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(cooldownMillis - elapsedMillis).coerceAtLeast(1)
            return NetworkChangeRestartDecision(
                action = NetworkChangeRestartAction.SUPPRESS_COOLDOWN,
                summary = "same network restarted recently; cooldown remaining=${remainingSeconds}s",
            )
        }

        return NetworkChangeRestartDecision(
            action = NetworkChangeRestartAction.RESTART,
            summary = "restart allowed",
        )
    }

    fun restartReason(trigger: String, sequence: Long, summary: String): String {
        return "network_change:$trigger seq=$sequence $summary".take(180)
    }

    fun observedRestartEligibility(
        event: String,
        observedNetworkKey: String,
        previousObservedNetworkKey: String?,
        rules: RestartRules = RestartRules(),
    ): ObservedNetworkRestartEligibility {
        if (!rules.sanitized().ignoreDuplicateObservedCapabilities) {
            return ObservedNetworkRestartEligibility(
                scheduleRestart = true,
                summary = "restart eligible",
            )
        }

        val unchangedNetwork = previousObservedNetworkKey != null &&
            observedNetworkKey == previousObservedNetworkKey
        if (event == "observed_capabilities_changed" && unchangedNetwork) {
            return ObservedNetworkRestartEligibility(
                scheduleRestart = false,
                summary = "observed_network_identity_unchanged",
            )
        }

        return ObservedNetworkRestartEligibility(
            scheduleRestart = true,
            summary = "restart eligible",
        )
    }
}

enum class NetworkChangeRestartAction {
    RESTART,
    SKIP_STOPPED,
    SKIP_DISABLED,
    SUPPRESS_COOLDOWN,
}

data class NetworkChangeRestartDecision(
    val action: NetworkChangeRestartAction,
    val summary: String,
)

data class ObservedNetworkRestartEligibility(
    val scheduleRestart: Boolean,
    val summary: String,
)
