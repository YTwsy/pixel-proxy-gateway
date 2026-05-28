package com.wsy.pixelproxygateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class NetworkRecoveryMonitor(
    context: Context,
    private val logStore: LogStore,
    private val statusStore: StatusStore,
    private val scheduler: ScheduledExecutorService,
    private val configProvider: () -> ProxyConfig,
    private val desiredRunningProvider: () -> Boolean,
    private val restartProxy: (String) -> Unit,
    private val onStatusChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var registered = false
    private var pendingProbe: ScheduledFuture<*>? = null
    private var eventSequence = 0L
    private var recoveryFailures = 0
    private var lastRecoveryRestartAtMillis = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            recordNetworkEvent("available", network)
        }

        override fun onLost(network: Network) {
            recordNetworkEvent("lost", network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            recordNetworkEvent("capabilities_changed", network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            recordNetworkEvent("link_properties_changed", network)
        }
    }

    fun start() {
        val shouldRegister = synchronized(lock) {
            if (registered) false else {
                registered = true
                true
            }
        }
        if (!shouldRegister) return

        val summary = activeNetworkSummary()
        statusStore.update {
            it.copy(
                lastNetworkEventAt = TimeUtil.now(),
                lastNetworkEvent = "monitor_start",
                lastNetworkSummary = summary,
            )
        }
        logStore.append("app", "network monitor start $summary")
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }.getOrElse {
            val message = it.message ?: it.javaClass.simpleName
            logStore.append("app", "network monitor registration failed error=$message")
            statusStore.update { status ->
                status.copy(
                    lastNetworkEventAt = TimeUtil.now(),
                    lastNetworkEvent = "monitor_registration_failed",
                    lastNetworkSummary = message,
                    lastError = "network monitor registration failed: $message",
                )
            }
        }
    }

    fun stop() {
        val shouldUnregister = synchronized(lock) {
            pendingProbe?.cancel(false)
            pendingProbe = null
            if (registered) {
                registered = false
                true
            } else {
                false
            }
        }
        if (shouldUnregister) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                .onFailure { logStore.append("app", "network monitor unregister failed error=${it.message}") }
        }
        logStore.append("app", "network monitor stop")
    }

    private fun recordNetworkEvent(event: String, network: Network) {
        val sequence = synchronized(lock) {
            eventSequence += 1
            eventSequence
        }
        val summary = activeNetworkSummary(eventNetwork = network)
        val desiredRunning = desiredRunningProvider()
        logStore.append(
            "app",
            "network event seq=$sequence event=$event desired=$desiredRunning $summary",
        )
        statusStore.update {
            it.copy(
                lastNetworkEventAt = TimeUtil.now(),
                lastNetworkEvent = "$event seq=$sequence",
                lastNetworkSummary = summary,
            )
        }
        if (desiredRunning && event != "lost") {
            scheduleProbe("network_$event")
        }
        onStatusChanged()
    }

    private fun scheduleProbe(trigger: String) {
        synchronized(lock) {
            pendingProbe?.cancel(false)
            pendingProbe = scheduler.schedule(
                { runProbe(trigger) },
                NetworkRecoveryPolicy.PROBE_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
        }
        logStore.append(
            "app",
            "network recovery probe scheduled trigger=$trigger delay=${NetworkRecoveryPolicy.PROBE_DELAY_SECONDS}s",
        )
    }

    private fun runProbe(trigger: String) {
        synchronized(lock) {
            pendingProbe = null
        }
        val desiredRunning = desiredRunningProvider()
        if (!desiredRunning) {
            val decision = NetworkRecoveryPolicy.decide(
                result = HealthCheckResult(false, "proxy stopped", false, 0, "proxy stopped"),
                previousFailures = recoveryFailures,
                desiredRunning = false,
                nowMillis = System.currentTimeMillis(),
                lastRestartAtMillis = lastRecoveryRestartAtMillis,
            )
            recoveryFailures = decision.nextFailures
            logStore.append("app", "network recovery probe skipped trigger=$trigger reason=${decision.summary}")
            return
        }

        val config = configProvider()
        val result = HealthWatchdogs.checkAll(config)
        val nowMillis = System.currentTimeMillis()
        val decision = synchronized(lock) {
            val next = NetworkRecoveryPolicy.decide(
                result = result,
                previousFailures = recoveryFailures,
                desiredRunning = true,
                nowMillis = nowMillis,
                lastRestartAtMillis = lastRecoveryRestartAtMillis,
            )
            recoveryFailures = next.nextFailures
            next
        }
        val checkedAt = TimeUtil.format(nowMillis)
        statusStore.update {
            it.copy(
                portOk = result.portOk,
                lastPortCheckAt = checkedAt,
                requestOk = result.requestOk,
                lastRequestCheckAt = checkedAt,
                lastHttpStatus = result.requestStatus,
                lastError = if (result.ok) "" else "network_recovery:${result.lastError}",
                lastNetworkProbeAt = checkedAt,
                lastNetworkProbeResult = decision.summary,
                networkRecoveryFailures = decision.nextFailures,
            )
        }
        logStore.append(
            "app",
            "network recovery probe trigger=$trigger action=${decision.action} failures=${decision.nextFailures} ${decision.summary}",
        )

        when (decision.action) {
            NetworkRecoveryAction.CLEAR_FAILURES,
            NetworkRecoveryAction.SKIP_STOPPED -> Unit
            NetworkRecoveryAction.RETRY_PROBE,
            NetworkRecoveryAction.SUPPRESS_RESTART -> scheduleProbe("network_retry_after_${decision.action.name.lowercase()}")
            NetworkRecoveryAction.RESTART_PROXY -> restartAfterProbeFailure(decision)
        }
        onStatusChanged()
    }

    private fun restartAfterProbeFailure(decision: NetworkRecoveryDecision) {
        val reason = "network_recovery:${decision.summary}".take(180)
        synchronized(lock) {
            recoveryFailures = 0
            lastRecoveryRestartAtMillis = System.currentTimeMillis()
        }
        statusStore.update {
            it.copy(
                networkRecoveryFailures = 0,
                networkRecoveryRestartCount = it.networkRecoveryRestartCount + 1,
                lastRestartReason = reason,
            )
        }
        logStore.append("app", "network recovery restart reason=$reason")
        restartProxy(reason)
    }

    private fun activeNetworkSummary(eventNetwork: Network? = null): String {
        val active = connectivityManager.activeNetwork
        val capabilities = active?.let { connectivityManager.getNetworkCapabilities(it) }
        val linkProperties = active?.let { connectivityManager.getLinkProperties(it) }
        val eventPart = eventNetwork?.let { "eventNetwork=$it " }.orEmpty()
        return eventPart +
            "active=${active ?: "none"} " +
            capabilitiesSummary(capabilities) + " " +
            linkSummary(linkProperties)
    }

    private fun capabilitiesSummary(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "transports=none capabilities=none"
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
        ).filter { capabilities.hasTransport(it.first) }
            .joinToString(",") { it.second }
            .ifBlank { "unknown" }
        val flags = listOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET to "internet",
            NetworkCapabilities.NET_CAPABILITY_VALIDATED to "validated",
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED to "not_metered",
            NetworkCapabilities.NET_CAPABILITY_NOT_VPN to "not_vpn",
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL to "captive_portal",
        ).filter { capabilities.hasCapability(it.first) }
            .joinToString(",") { it.second }
            .ifBlank { "none" }
        return "transports=$transports capabilities=$flags"
    }

    private fun linkSummary(linkProperties: LinkProperties?): String {
        if (linkProperties == null) {
            return "link=none"
        }
        val ipv4 = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .joinToString(",") { it.hostAddress ?: "" }
            .ifBlank { "none" }
        val ipv6Count = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet6Address>()
            .count()
        val dns = linkProperties.dnsServers
            .joinToString(",") { it.hostAddress ?: "" }
            .ifBlank { "none" }
        val httpProxy = linkProperties.httpProxy?.let { "${it.host}:${it.port}" } ?: "none"
        return "if=${linkProperties.interfaceName ?: "none"} ipv4=$ipv4 ipv6=$ipv6Count dns=$dns routes=${linkProperties.routes.size} httpProxy=$httpProxy"
    }
}
