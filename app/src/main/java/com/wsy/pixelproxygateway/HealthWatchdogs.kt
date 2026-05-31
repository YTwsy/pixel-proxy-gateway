package com.wsy.pixelproxygateway

import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

object HealthWatchdogs {
    fun checkAll(config: ProxyConfig): HealthCheckResult {
        val portResult = checkPorts(config, config.timeoutSeconds * 1000)
        val requestResult = checkRequests(config)
        return HealthCheckResult(
            portOk = portResult.first,
            portMessage = portResult.second,
            requestOk = requestResult.ok,
            requestStatus = requestResult.status,
            requestError = requestResult.error,
            requestResults = requestResult.results,
        )
    }

    fun checkPorts(config: ProxyConfig, timeoutMs: Int): Pair<Boolean, String> {
        val targets = mutableListOf<Int>()
        if (config.enableHttp) targets += config.httpPort
        if (config.enableSocks) targets += config.socksPort
        if (targets.isEmpty()) return false to "no listeners enabled"
        val host = portCheckHost(config.bindAddress)
        for (port in targets) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
            }.getOrElse {
                return false to "port $host:$port failed: ${it.message}"
            }
        }
        return true to "ok"
    }

    internal fun portCheckHost(bindAddress: String): String {
        val normalized = bindAddress.trim().removePrefix("[").removeSuffix("]")
        return when (normalized.lowercase()) {
            "", "0.0.0.0", "localhost" -> "127.0.0.1"
            "::", "0:0:0:0:0:0:0:0" -> "::1"
            else -> normalized
        }
    }

    @Synchronized
    fun checkRequest(config: ProxyConfig): RequestResult {
        val result = checkRequests(config)
        return RequestResult(
            ok = result.ok,
            status = result.status,
            error = result.error,
        )
    }

    @Synchronized
    fun checkRequests(config: ProxyConfig): RequestCheckResult {
        val host = portCheckHost(config.bindAddress)
        val targets = mutableListOf<ProxyRequestTarget>()
        if (config.enableHttp) {
            targets += ProxyRequestTarget(
                listener = ProxyListener.HTTP,
                proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, config.httpPort)),
            )
        }
        if (config.enableSocks) {
            targets += ProxyRequestTarget(
                listener = ProxyListener.SOCKS5,
                proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, config.socksPort)),
            )
        }
        if (targets.isEmpty()) {
            return RequestCheckResult(
                ok = false,
                status = 0,
                error = "no listeners enabled",
                results = emptyList(),
            )
        }

        val results = targets.map { target ->
            val result = checkRequestViaProxy(config, target.proxy)
            ProxyRequestResult(
                listener = target.listener.id,
                ok = result.ok,
                status = result.status,
                error = result.error,
            )
        }
        val firstFailure = results.firstOrNull { !it.ok }
        val ok = firstFailure == null
        return RequestCheckResult(
            ok = ok,
            status = firstFailure?.status ?: results.firstOrNull()?.status ?: 0,
            error = if (ok) "" else requestSummary(results.filter { !it.ok }),
            results = results,
        )
    }

    private fun checkRequestViaProxy(config: ProxyConfig, proxy: Proxy): RequestResult {
        return withProxyAuthenticator(config) {
            val connection = URL(config.healthUrl).openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = config.timeoutSeconds * 1000
            connection.readTimeout = config.timeoutSeconds * 1000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (config.authEnabled && config.username.isNotBlank() && proxy.type() == Proxy.Type.HTTP) {
                connection.setRequestProperty("Proxy-Authorization", basicProxyAuth(config))
            }
            val status = connection.responseCode
            connection.disconnect()
            val expected = config.expectedStatus
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .ifEmpty { listOf(204) }
            RequestResult(
                ok = expected.contains(status),
                status = status,
                error = if (expected.contains(status)) "" else "unexpected status $status expected=${config.expectedStatus}",
            )
        }
    }

    private fun requestSummary(results: List<ProxyRequestResult>): String {
        return results.joinToString("; ") { result ->
            val detail = result.error.ifBlank { "status ${result.status}" }
            "${result.listener} request failed: $detail"
        }
    }

    private fun withProxyAuthenticator(config: ProxyConfig, block: () -> RequestResult): RequestResult {
        if (!config.authEnabled || config.username.isBlank()) {
            return runCatching(block).getOrElse {
                RequestResult(ok = false, status = 0, error = it.message ?: it.javaClass.simpleName)
            }
        }
        runCatching {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (getRequestorType() != RequestorType.PROXY) return null
                    return PasswordAuthentication(config.username, config.password.toCharArray())
                }
            })
        }
        return try {
            runCatching(block).getOrElse {
                RequestResult(ok = false, status = 0, error = it.message ?: it.javaClass.simpleName)
            }
        } finally {
            runCatching { Authenticator.setDefault(null) }
        }
    }

    private fun basicProxyAuth(config: ProxyConfig): String {
        val token = "${config.username}:${config.password}"
        return "Basic " + Base64.getEncoder().encodeToString(token.toByteArray(StandardCharsets.UTF_8))
    }
}

private enum class ProxyListener(val id: String) {
    HTTP("http"),
    SOCKS5("socks5"),
}

private data class ProxyRequestTarget(
    val listener: ProxyListener,
    val proxy: Proxy,
)

data class RequestResult(
    val ok: Boolean,
    val status: Int,
    val error: String,
)

data class RequestCheckResult(
    val ok: Boolean,
    val status: Int,
    val error: String,
    val results: List<ProxyRequestResult>,
)

data class ProxyRequestResult(
    val listener: String,
    val ok: Boolean,
    val status: Int,
    val error: String,
)

data class HealthCheckResult(
    val portOk: Boolean,
    val portMessage: String,
    val requestOk: Boolean,
    val requestStatus: Int,
    val requestError: String,
    val requestResults: List<ProxyRequestResult> = emptyList(),
) {
    val ok: Boolean get() = portOk && requestOk
    val requestSummary: String
        get() = if (requestResults.isEmpty()) {
            requestError.ifBlank { "none" }
        } else {
            requestResults.joinToString("; ") {
                val state = if (it.ok) "ok" else "fail"
                val error = it.error.ifBlank { "none" }
                "${it.listener}=$state status=${it.status} error=$error"
            }
        }
    val lastError: String
        get() = when {
            !portOk -> portMessage
            !requestOk -> requestError
            else -> ""
        }

    fun requestResultFor(listener: String): ProxyRequestResult? {
        return requestResults.firstOrNull { it.listener == listener }
    }
}
