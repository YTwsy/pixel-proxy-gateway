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
        val requestResult = checkRequest(config)
        return HealthCheckResult(
            portOk = portResult.first,
            portMessage = portResult.second,
            requestOk = requestResult.ok,
            requestStatus = requestResult.status,
            requestError = requestResult.error,
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
        val host = portCheckHost(config.bindAddress)
        val proxy = if (config.enableHttp) {
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, config.httpPort))
        } else if (config.enableSocks) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, config.socksPort))
        } else {
            return RequestResult(ok = false, status = 0, error = "no listeners enabled")
        }
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

data class RequestResult(
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
) {
    val ok: Boolean get() = portOk && requestOk
    val lastError: String
        get() = when {
            !portOk -> portMessage
            !requestOk -> requestError
            else -> ""
        }
}
