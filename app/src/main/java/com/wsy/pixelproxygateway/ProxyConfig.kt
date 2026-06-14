package com.wsy.pixelproxygateway

import android.content.Intent
import org.json.JSONObject

data class ProxyConfig(
    val bindAddress: String = "0.0.0.0",
    val httpPort: Int = 8080,
    val socksPort: Int = 1080,
    val enableHttp: Boolean = true,
    val enableSocks: Boolean = true,
    val authEnabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    val healthUrl: String = "https://connectivitycheck.gstatic.com/generate_204",
    val expectedStatus: String = "204",
    val intervalSeconds: Long = 300,
    val timeoutSeconds: Int = 15,
    val failureThreshold: Int = 2,
    val startOnBoot: Boolean = true,
    val autoStart: Boolean = false,
    val restartRules: RestartRules = RestartRules(),
) {
    fun sanitized(): ProxyConfig {
        val trimmedUsername = username.trim()
        val sanitizedRestartRules = restartRules.sanitized()
        return copy(
            bindAddress = bindAddress.trim().ifBlank { "0.0.0.0" },
            httpPort = httpPort.coerceIn(1, 65535),
            socksPort = socksPort.coerceIn(1, 65535),
            intervalSeconds = intervalSeconds.coerceIn(30, 86_400),
            timeoutSeconds = timeoutSeconds.coerceIn(3, 120),
            failureThreshold = sanitizedRestartRules.healthFailureThreshold,
            username = if (authEnabled) trimmedUsername else "",
            password = if (authEnabled) password else "",
            restartRules = sanitizedRestartRules,
        )
    }

    fun startValidationError(): String? {
        if (!enableHttp && !enableSocks) {
            return "At least one listener must be enabled"
        }
        if (enableHttp && enableSocks && httpPort == socksPort) {
            return "HTTP and SOCKS listeners cannot use the same port: $httpPort"
        }
        return null
    }

    fun withIntentOverrides(intent: Intent?): ProxyConfig {
        if (intent == null) return this
        return copy(
            bindAddress = intent.stringExtra(Actions.EXTRA_BIND_ADDRESS, bindAddress),
            httpPort = intent.intExtra(Actions.EXTRA_HTTP_PORT, httpPort),
            socksPort = intent.intExtra(Actions.EXTRA_SOCKS_PORT, socksPort),
            enableHttp = intent.booleanExtra(Actions.EXTRA_ENABLE_HTTP, enableHttp),
            enableSocks = intent.booleanExtra(Actions.EXTRA_ENABLE_SOCKS, enableSocks),
            authEnabled = intent.booleanExtra(Actions.EXTRA_AUTH_ENABLED, authEnabled),
            username = intent.stringExtra(Actions.EXTRA_USERNAME, username),
            password = intent.stringExtra(Actions.EXTRA_PASSWORD, password),
            healthUrl = intent.stringExtra(Actions.EXTRA_HEALTH_URL, healthUrl),
            expectedStatus = intent.stringExtra(Actions.EXTRA_EXPECTED_STATUS, expectedStatus),
            intervalSeconds = intent.longExtra(Actions.EXTRA_INTERVAL_SECONDS, intervalSeconds),
            timeoutSeconds = intent.intExtra(Actions.EXTRA_TIMEOUT_SECONDS, timeoutSeconds),
            failureThreshold = intent.intExtra(Actions.EXTRA_FAILURE_THRESHOLD, failureThreshold),
            startOnBoot = intent.booleanExtra(Actions.EXTRA_START_ON_BOOT, startOnBoot),
            restartRules = restartRules.withIntentOverrides(intent, failureThreshold),
        ).sanitized()
    }

    fun toJson(includePassword: Boolean = false): JSONObject {
        return JSONObject()
            .put("bindAddress", bindAddress)
            .put("httpPort", httpPort)
            .put("socksPort", socksPort)
            .put("enableHttp", enableHttp)
            .put("enableSocks", enableSocks)
            .put("authEnabled", authEnabled)
            .put("username", username)
            .put("passwordSet", password.isNotEmpty())
            .put("password", if (includePassword) password else "")
            .put("healthUrl", healthUrl)
            .put("expectedStatus", expectedStatus)
            .put("intervalSeconds", intervalSeconds)
            .put("timeoutSeconds", timeoutSeconds)
            .put("failureThreshold", failureThreshold)
            .put("startOnBoot", startOnBoot)
            .put("autoStart", autoStart)
            .put("restartRules", restartRules.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): ProxyConfig {
            return ProxyConfig(
                bindAddress = json.optString("bindAddress", "0.0.0.0"),
                httpPort = json.optInt("httpPort", 8080),
                socksPort = json.optInt("socksPort", 1080),
                enableHttp = json.optBoolean("enableHttp", true),
                enableSocks = json.optBoolean("enableSocks", true),
                authEnabled = json.optBoolean("authEnabled", false),
                username = json.optString("username", ""),
                password = json.optString("password", ""),
                healthUrl = json.optString("healthUrl", "https://connectivitycheck.gstatic.com/generate_204"),
                expectedStatus = json.optString("expectedStatus", "204"),
                intervalSeconds = json.optLong("intervalSeconds", 300),
                timeoutSeconds = json.optInt("timeoutSeconds", 15),
                failureThreshold = json.optInt("failureThreshold", RestartRules.DEFAULT_HEALTH_FAILURE_THRESHOLD),
                startOnBoot = json.optBoolean("startOnBoot", true),
                autoStart = json.optBoolean("autoStart", false),
                restartRules = RestartRules.fromJson(
                    json.optJSONObject("restartRules") ?: json.optJSONObject("networkRestartRules"),
                    legacyHealthFailureThreshold = json.optInt(
                        "failureThreshold",
                        RestartRules.DEFAULT_HEALTH_FAILURE_THRESHOLD,
                    ),
                ),
            ).sanitized()
        }
    }
}

private fun RestartRules.toJson(): JSONObject {
    return JSONObject()
        .put("networkRestartEnabled", networkRestartEnabled)
        .put("networkRestartDelaySeconds", networkRestartDelaySeconds)
        .put("networkRestartCooldownSeconds", networkRestartCooldownSeconds)
        .put("ignoreDuplicateObservedCapabilities", ignoreDuplicateObservedCapabilities)
        .put("healthFailureRestartEnabled", healthFailureRestartEnabled)
        .put("healthFailureThreshold", healthFailureThreshold)
        .put("portFailureRestartEnabled", portFailureRestartEnabled)
        .put("portFailureThreshold", portFailureThreshold)
}

private fun RestartRules.Companion.fromJson(
    json: JSONObject?,
    legacyHealthFailureThreshold: Int,
): RestartRules {
    if (json == null) {
        return RestartRules(healthFailureThreshold = legacyHealthFailureThreshold).sanitized()
    }
    return RestartRules(
        networkRestartEnabled = json.optBoolean(
            "networkRestartEnabled",
            json.optBoolean("enabled", true),
        ),
        networkRestartDelaySeconds = json.optLong(
            "networkRestartDelaySeconds",
            json.optLong("restartDelaySeconds", RestartRules.DEFAULT_NETWORK_RESTART_DELAY_SECONDS),
        ),
        networkRestartCooldownSeconds = json.optLong(
            "networkRestartCooldownSeconds",
            json.optLong("restartCooldownSeconds", RestartRules.DEFAULT_NETWORK_RESTART_COOLDOWN_SECONDS),
        ),
        ignoreDuplicateObservedCapabilities = json.optBoolean("ignoreDuplicateObservedCapabilities", true),
        healthFailureRestartEnabled = json.optBoolean("healthFailureRestartEnabled", true),
        healthFailureThreshold = json.optInt("healthFailureThreshold", legacyHealthFailureThreshold),
        portFailureRestartEnabled = json.optBoolean("portFailureRestartEnabled", true),
        portFailureThreshold = json.optInt("portFailureThreshold", RestartRules.DEFAULT_PORT_FAILURE_THRESHOLD),
    ).sanitized()
}

private fun RestartRules.withIntentOverrides(intent: Intent, legacyHealthFailureThreshold: Int): RestartRules {
    return copy(
        networkRestartEnabled = intent.booleanExtra(Actions.EXTRA_NETWORK_RESTART_ENABLED, networkRestartEnabled),
        networkRestartDelaySeconds = intent.longExtra(
            Actions.EXTRA_NETWORK_RESTART_DELAY_SECONDS,
            networkRestartDelaySeconds,
        ),
        networkRestartCooldownSeconds = intent.longExtra(
            Actions.EXTRA_NETWORK_RESTART_COOLDOWN_SECONDS,
            networkRestartCooldownSeconds,
        ),
        ignoreDuplicateObservedCapabilities = intent.booleanExtra(
            Actions.EXTRA_NETWORK_RESTART_IGNORE_DUPLICATE_OBSERVED_CAPABILITIES,
            ignoreDuplicateObservedCapabilities,
        ),
        healthFailureRestartEnabled = intent.booleanExtra(
            Actions.EXTRA_HEALTH_FAILURE_RESTART_ENABLED,
            healthFailureRestartEnabled,
        ),
        healthFailureThreshold = intent.intExtra(
            Actions.EXTRA_HEALTH_FAILURE_THRESHOLD,
            intent.intExtra(Actions.EXTRA_FAILURE_THRESHOLD, legacyHealthFailureThreshold),
        ),
        portFailureRestartEnabled = intent.booleanExtra(
            Actions.EXTRA_PORT_FAILURE_RESTART_ENABLED,
            portFailureRestartEnabled,
        ),
        portFailureThreshold = intent.intExtra(Actions.EXTRA_PORT_FAILURE_THRESHOLD, portFailureThreshold),
    ).sanitized()
}

private fun Intent.stringExtra(name: String, defaultValue: String): String {
    return if (hasExtra(name)) getStringExtra(name) ?: defaultValue else defaultValue
}

private fun Intent.intExtra(name: String, defaultValue: Int): Int {
    return if (hasExtra(name)) getIntExtra(name, defaultValue) else defaultValue
}

private fun Intent.longExtra(name: String, defaultValue: Long): Long {
    return if (hasExtra(name)) getLongExtra(name, defaultValue) else defaultValue
}

private fun Intent.booleanExtra(name: String, defaultValue: Boolean): Boolean {
    return if (hasExtra(name)) getBooleanExtra(name, defaultValue) else defaultValue
}
