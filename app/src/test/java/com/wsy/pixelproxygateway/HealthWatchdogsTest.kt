package com.wsy.pixelproxygateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64

class HealthWatchdogsTest {
    @Test
    fun portCheckHostUsesLoopbackForWildcardAndPreservesSpecificBindAddress() {
        assertEquals("127.0.0.1", HealthWatchdogs.portCheckHost(""))
        assertEquals("127.0.0.1", HealthWatchdogs.portCheckHost("0.0.0.0"))
        assertEquals("127.0.0.1", HealthWatchdogs.portCheckHost("localhost"))
        assertEquals("::1", HealthWatchdogs.portCheckHost("::"))
        assertEquals("::1", HealthWatchdogs.portCheckHost("[::]"))
        assertEquals("192.168.1.50", HealthWatchdogs.portCheckHost("192.168.1.50"))
        assertEquals("127.0.0.2", HealthWatchdogs.portCheckHost("127.0.0.2"))
    }

    @Test
    fun checkPortsAcceptsBothEnabledListeners() {
        OneShotListener("127.0.0.1").use { http ->
            OneShotListener("127.0.0.1").use { socks ->
                val (ok, message) = HealthWatchdogs.checkPorts(
                    ProxyConfig(httpPort = http.port, socksPort = socks.port),
                    timeoutMs = 1_000,
                )

                assertTrue(message, ok)
                assertEquals("ok", message)
            }
        }
    }

    @Test
    fun checkPortsReportsClosedListener() {
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        val (ok, message) = HealthWatchdogs.checkPorts(
            ProxyConfig(httpPort = closedPort, enableHttp = true, enableSocks = false),
            timeoutMs = 100,
        )

        assertFalse(ok)
        assertTrue(message, message.contains("127.0.0.1:$closedPort"))
    }

    @Test
    fun checkRequestAcceptsExpectedHttpProxyStatus() {
        OneShotHttpProxy(responseStatus = 204, responseReason = "No Content").use { proxy ->
            val result = HealthWatchdogs.checkRequest(
                ProxyConfig(
                    bindAddress = "localhost",
                    httpPort = proxy.port,
                    enableHttp = true,
                    enableSocks = false,
                    healthUrl = "http://example.test/generate_204",
                    expectedStatus = "204",
                    timeoutSeconds = 3,
                ),
            )
            proxy.await()

            assertTrue(result.error, result.ok)
            assertEquals(204, result.status)
            assertEquals("", result.error)
            assertTrue(proxy.requestLine, proxy.requestLine.startsWith("GET http://example.test/generate_204 "))
            assertNull(proxy.headers["proxy-authorization"])
        }
    }

    @Test
    fun checkRequestSendsHttpProxyAuthorizationWhenEnabled() {
        OneShotHttpProxy(responseStatus = 204, responseReason = "No Content").use { proxy ->
            val result = HealthWatchdogs.checkRequest(
                ProxyConfig(
                    httpPort = proxy.port,
                    enableHttp = true,
                    enableSocks = false,
                    authEnabled = true,
                    username = "user",
                    password = "p@ss",
                    healthUrl = "http://example.test/generate_204",
                    expectedStatus = "204",
                    timeoutSeconds = 3,
                ),
            )
            proxy.await()

            val expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("user:p@ss".toByteArray(StandardCharsets.UTF_8))
            assertTrue(result.error, result.ok)
            assertEquals(expectedAuth, proxy.headers["proxy-authorization"])
        }
    }

    @Test
    fun checkRequestRejectsNoListeners() {
        val result = HealthWatchdogs.checkRequest(
            ProxyConfig(enableHttp = false, enableSocks = false),
        )

        assertFalse(result.ok)
        assertEquals(0, result.status)
        assertEquals("no listeners enabled", result.error)
    }

    @Test
    fun checkRequestReportsUnexpectedStatus() {
        OneShotHttpProxy(responseStatus = 500, responseReason = "Server Error").use { proxy ->
            val result = HealthWatchdogs.checkRequest(
                ProxyConfig(
                    httpPort = proxy.port,
                    enableHttp = true,
                    enableSocks = false,
                    healthUrl = "http://example.test/generate_204",
                    expectedStatus = "204",
                    timeoutSeconds = 3,
                ),
            )
            proxy.await()

            assertFalse(result.ok)
            assertEquals(500, result.status)
            assertTrue(result.error, result.error.contains("unexpected status 500"))
        }
    }

    @Test
    fun checkRequestsReportsSocksFailureEvenWhenHttpWorks() {
        val closedSocksPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        OneShotHttpProxy(responseStatus = 204, responseReason = "No Content").use { http ->
            val result = HealthWatchdogs.checkRequests(
                ProxyConfig(
                    bindAddress = "localhost",
                    httpPort = http.port,
                    socksPort = closedSocksPort,
                    enableHttp = true,
                    enableSocks = true,
                    healthUrl = "http://example.test/generate_204",
                    expectedStatus = "204",
                    timeoutSeconds = 3,
                ),
            )
            http.await()

            assertFalse(result.ok)
            assertEquals(204, result.results.first { it.listener == "http" }.status)
            assertFalse(result.results.first { it.listener == "socks5" }.ok)
            assertTrue(result.error, result.error.contains("socks5 request failed"))
        }
    }

    private class OneShotListener(host: String) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName(host))
        val port: Int = server.localPort
        private val thread = Thread({
            runCatching {
                server.soTimeout = 5_000
                server.accept().use { }
            }
            runCatching { server.close() }
        }, "one-shot-listener").apply {
            isDaemon = true
            start()
        }

        override fun close() {
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    private class OneShotHttpProxy(
        private val responseStatus: Int,
        private val responseReason: String,
    ) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        @Volatile var requestLine: String = ""
            private set
        @Volatile var headers: Map<String, String> = emptyMap()
            private set
        @Volatile private var error: Throwable? = null

        private val thread = Thread({ serveOnce() }, "one-shot-http-proxy").apply {
            isDaemon = true
            start()
        }

        fun await() {
            thread.join(2_000)
            error?.let { throw AssertionError("HTTP proxy test server failed", it) }
        }

        override fun close() {
            runCatching { server.close() }
            thread.join(2_000)
        }

        private fun serveOnce() {
            runCatching {
                server.soTimeout = 5_000
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                    requestLine = reader.readLine().orEmpty()
                    val capturedHeaders = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            capturedHeaders[line.substring(0, separator).lowercase()] =
                                line.substring(separator + 1).trim()
                        }
                    }
                    headers = capturedHeaders
                    val response = "HTTP/1.1 $responseStatus $responseReason\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                    socket.getOutputStream().write(response.toByteArray(StandardCharsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                }
            }.getOrElse {
                if (it !is SocketTimeoutException) error = it
            }
            runCatching { server.close() }
        }
    }
}
