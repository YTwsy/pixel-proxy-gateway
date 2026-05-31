package com.wsy.pixelproxygateway

object HealthStatus {
    fun applyManualCheck(
        status: RuntimeStatus,
        config: ProxyConfig,
        result: HealthCheckResult,
        checkedAt: String,
    ): RuntimeStatus {
        return applyRequestDetails(status.copy(
            bindAddress = config.bindAddress,
            httpPort = config.httpPort,
            socksPort = config.socksPort,
            enableHttp = config.enableHttp,
            enableSocks = config.enableSocks,
            startOnBoot = config.startOnBoot,
            autoStart = config.autoStart,
            portOk = result.portOk,
            lastPortCheckAt = checkedAt,
            requestOk = result.requestOk,
            lastRequestCheckAt = checkedAt,
            lastHttpStatus = result.requestStatus,
            consecutiveFailures = if (result.requestOk) 0 else status.consecutiveFailures,
            lastError = result.lastError,
        ), result)
    }

    fun applyRequestDetails(status: RuntimeStatus, result: HealthCheckResult): RuntimeStatus {
        val http = result.requestResultFor("http")
        val socks = result.requestResultFor("socks5")
        return status.copy(
            lastHttpProxyRequestOk = http?.ok ?: false,
            lastHttpProxyStatus = http?.status ?: 0,
            lastHttpProxyError = http?.error ?: "disabled",
            lastSocksProxyRequestOk = socks?.ok ?: false,
            lastSocksProxyStatus = socks?.status ?: 0,
            lastSocksProxyError = socks?.error ?: "disabled",
            lastProxyRequestSummary = result.requestSummary,
        )
    }

    fun logLine(result: HealthCheckResult): String {
        return "manual health check port=${result.portOk} request=${result.requestOk} " +
            "status=${result.requestStatus} error=${result.lastError} details=${result.requestSummary}"
    }
}
