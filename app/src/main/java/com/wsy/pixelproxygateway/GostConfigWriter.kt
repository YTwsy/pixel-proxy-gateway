package com.wsy.pixelproxygateway

import java.io.File

object GostConfigWriter {
    fun write(config: ProxyConfig, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(build(config))
    }

    private fun build(config: ProxyConfig): String {
        val services = mutableListOf<String>()
        if (config.enableHttp) {
            services += service(
                name = "service-http",
                type = "http",
                addr = "${config.bindAddress}:${config.httpPort}",
                config = config,
            )
        }
        if (config.enableSocks) {
            services += service(
                name = "service-socks5",
                type = "socks5",
                addr = "${config.bindAddress}:${config.socksPort}",
                config = config,
            )
        }
        return buildString {
            appendLine("services:")
            services.forEach { append(it) }
            appendLine("resolvers:")
            appendLine("  - name: android-dns")
            appendLine("    nameservers:")
            appendLine("      - addr: udp://8.8.8.8:53")
            appendLine("        prefer: ipv4")
            appendLine("        timeout: 3s")
        }
    }

    private fun service(name: String, type: String, addr: String, config: ProxyConfig): String {
        return buildString {
            appendLine("  - name: $name")
            appendLine("    addr: \"$addr\"")
            appendLine("    resolver: android-dns")
            appendLine("    handler:")
            appendLine("      type: $type")
            if (config.authEnabled && config.username.isNotEmpty()) {
                appendLine("      auth:")
                appendLine("        username: \"${escape(config.username)}\"")
                appendLine("        password: \"${escape(config.password)}\"")
            }
            appendLine("      metadata:")
            appendLine("        udp: true")
            appendLine("        udpBufferSize: 4096")
            appendLine("    listener:")
            appendLine("      type: tcp")
        }
    }

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
