package com.wsy.pixelproxygateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class GostConfigWriterTest {
    @Test
    fun writesBothDefaultListeners() {
        val file = tempConfigFile()
        GostConfigWriter.write(ProxyConfig(), file)

        val yaml = file.readText()
        assertTrue(yaml.contains("name: service-http"))
        assertTrue(yaml.contains("addr: \"0.0.0.0:8080\""))
        assertTrue(yaml.contains("resolver: android-dns"))
        assertTrue(yaml.contains("type: http"))
        assertTrue(yaml.contains("name: service-socks5"))
        assertTrue(yaml.contains("addr: \"0.0.0.0:1080\""))
        assertTrue(yaml.contains("type: socks5"))
        assertTrue(yaml.contains("name: android-dns"))
        assertTrue(yaml.contains("addr: udp://8.8.8.8:53"))
        assertTrue(yaml.contains("prefer: ipv4"))
    }

    @Test
    fun omitsDisabledListener() {
        val file = tempConfigFile()
        GostConfigWriter.write(ProxyConfig(enableHttp = false, enableSocks = true), file)

        val yaml = file.readText()
        assertFalse(yaml.contains("service-http"))
        assertTrue(yaml.contains("service-socks5"))
    }

    @Test
    fun writesEscapedAuthentication() {
        val file = tempConfigFile()
        GostConfigWriter.write(
            ProxyConfig(
                authEnabled = true,
                username = "u\"ser",
                password = "p\\ass\"word",
            ),
            file,
        )

        val yaml = file.readText()
        assertTrue(yaml.contains("auth:"))
        assertTrue(yaml.contains("username: \"u\\\"ser\""))
        assertTrue(yaml.contains("password: \"p\\\\ass\\\"word\""))
    }

    private fun tempConfigFile(): File {
        val dir = createTempDirectory(prefix = "pixel-proxy-gost-test").toFile()
        return File(dir, "gost.yaml")
    }
}
