package com.wsy.pixelproxygateway

object Actions {
    const val START = "com.wsy.pixelproxygateway.action.START"
    const val STOP = "com.wsy.pixelproxygateway.action.STOP"
    const val RESTART = "com.wsy.pixelproxygateway.action.RESTART"
    const val APPLY_CONFIG = "com.wsy.pixelproxygateway.action.APPLY_CONFIG"
    const val CHECK_HEALTH = "com.wsy.pixelproxygateway.action.CHECK_HEALTH"
    const val REPOST_NOTIFICATION = "com.wsy.pixelproxygateway.action.REPOST_NOTIFICATION"

    const val EXTRA_BIND_ADDRESS = "bind_address"
    const val EXTRA_HTTP_PORT = "http_port"
    const val EXTRA_SOCKS_PORT = "socks_port"
    const val EXTRA_ENABLE_HTTP = "enable_http"
    const val EXTRA_ENABLE_SOCKS = "enable_socks"
    const val EXTRA_AUTH_ENABLED = "auth_enabled"
    const val EXTRA_USERNAME = "username"
    const val EXTRA_PASSWORD = "password"
    const val EXTRA_HEALTH_URL = "health_url"
    const val EXTRA_EXPECTED_STATUS = "expected_status"
    const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
    const val EXTRA_TIMEOUT_SECONDS = "timeout_seconds"
    const val EXTRA_FAILURE_THRESHOLD = "failure_threshold"
    const val EXTRA_START_ON_BOOT = "start_on_boot"
    const val EXTRA_NETWORK_RESTART_ENABLED = "network_restart_enabled"
    const val EXTRA_NETWORK_RESTART_DELAY_SECONDS = "network_restart_delay_seconds"
    const val EXTRA_NETWORK_RESTART_COOLDOWN_SECONDS = "network_restart_cooldown_seconds"
    const val EXTRA_NETWORK_RESTART_IGNORE_DUPLICATE_OBSERVED_CAPABILITIES =
        "network_restart_ignore_duplicate_observed_capabilities"
    const val EXTRA_HEALTH_FAILURE_RESTART_ENABLED = "health_failure_restart_enabled"
    const val EXTRA_HEALTH_FAILURE_THRESHOLD = "health_failure_threshold"
    const val EXTRA_PORT_FAILURE_RESTART_ENABLED = "port_failure_restart_enabled"
    const val EXTRA_PORT_FAILURE_THRESHOLD = "port_failure_threshold"
}
