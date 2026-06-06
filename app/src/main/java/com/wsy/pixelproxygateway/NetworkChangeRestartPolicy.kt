package com.wsy.pixelproxygateway

import java.util.concurrent.TimeUnit

object NetworkChangeRestartPolicy {
    const val RESTART_DELAY_SECONDS = 3L
    const val RESTART_COOLDOWN_SECONDS = 30L

    fun decide(
        desiredRunning: Boolean,
        nowMillis: Long,
        activeNetworkKey: String,
        lastRestartAtMillis: Long,
        lastRestartNetworkKey: String,
    ): NetworkChangeRestartDecision {
        if (!desiredRunning) {
            return NetworkChangeRestartDecision(
                action = NetworkChangeRestartAction.SKIP_STOPPED,
                summary = "proxy is not desired running",
            )
        }

        val cooldownMillis = TimeUnit.SECONDS.toMillis(RESTART_COOLDOWN_SECONDS)
        val sameNetwork = activeNetworkKey.isNotBlank() && activeNetworkKey == lastRestartNetworkKey
        val elapsedMillis = nowMillis - lastRestartAtMillis
        if (sameNetwork && lastRestartAtMillis > 0 && elapsedMillis in 0 until cooldownMillis) {
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
    ): ObservedNetworkRestartEligibility {
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
