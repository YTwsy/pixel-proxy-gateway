package com.wsy.pixelproxygateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class NetworkChangeRestartMonitor(
    context: Context,
    private val logStore: LogStore,
    private val statusStore: StatusStore,
    private val scheduler: ScheduledExecutorService,
    private val desiredRunningProvider: () -> Boolean,
    private val restartRulesProvider: () -> RestartRules,
    private val restartProxy: (String) -> Unit,
    private val onStatusChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var registered = false
    private var defaultCallbackRegistered = false
    private var observedCallbackRegistered = false
    private var pendingRestart: ScheduledFuture<*>? = null
    private var eventSequence = 0L
    private var lastRestartAtMillis = 0L
    private var lastRestartNetworkKey = ""
    private val lastObservedNetworkKeys = mutableMapOf<String, String>()

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            recordNetworkEvent("default_available", network)
        }

        override fun onLost(network: Network) {
            recordNetworkEvent("default_lost", network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            recordNetworkEvent("default_capabilities_changed", network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            recordNetworkEvent("default_link_properties_changed", network)
        }
    }

    private val observedNetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private val observedNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            recordObservedNetworkEvent("observed_available", network)
        }

        override fun onLost(network: Network) {
            recordObservedNetworkEvent("observed_lost", network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            recordObservedNetworkEvent("observed_capabilities_changed", network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            recordObservedNetworkEvent("observed_link_properties_changed", network)
        }
    }

    fun start() {
        val shouldRegister = synchronized(lock) {
            if (registered) {
                false
            } else {
                registered = true
                lastObservedNetworkKeys.clear()
                true
            }
        }
        if (!shouldRegister) return

        val snapshot = networkSnapshot()
        statusStore.update {
            it.copy(
                lastNetworkEventAt = TimeUtil.now(),
                lastNetworkEvent = "monitor_start",
                lastNetworkSummary = snapshot.summary,
            )
        }
        logStore.append("app", "network restart monitor start ${snapshot.summary}")
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback)
            synchronized(lock) {
                defaultCallbackRegistered = true
            }
        }.getOrElse {
            synchronized(lock) {
                registered = false
            }
            val message = it.message ?: it.javaClass.simpleName
            logStore.append("app", "network restart monitor default registration failed error=$message")
            statusStore.update { status ->
                status.copy(
                    lastNetworkEventAt = TimeUtil.now(),
                    lastNetworkEvent = "monitor_registration_failed",
                    lastNetworkSummary = message,
                    lastError = "network monitor registration failed: $message",
                )
            }
            return
        }

        runCatching {
            connectivityManager.registerNetworkCallback(observedNetworkRequest, observedNetworkCallback)
            synchronized(lock) {
                observedCallbackRegistered = true
            }
        }.getOrElse {
            val message = it.message ?: it.javaClass.simpleName
            logStore.append("app", "network restart monitor observed registration failed error=$message")
            statusStore.update { status ->
                status.copy(
                    lastNetworkEventAt = TimeUtil.now(),
                    lastNetworkEvent = "monitor_observed_registration_failed",
                    lastNetworkSummary = message,
                    lastError = "observed network monitor registration failed: $message",
                )
            }
        }
    }

    fun stop() {
        val callbacksToUnregister = synchronized(lock) {
            pendingRestart?.cancel(false)
            pendingRestart = null
            val callbacks = RegisteredCallbacks(
                defaultNetwork = defaultCallbackRegistered,
                observedNetwork = observedCallbackRegistered,
            )
            defaultCallbackRegistered = false
            observedCallbackRegistered = false
            registered = false
            lastObservedNetworkKeys.clear()
            callbacks
        }
        if (callbacksToUnregister.defaultNetwork) {
            runCatching { connectivityManager.unregisterNetworkCallback(defaultNetworkCallback) }
                .onFailure { logStore.append("app", "network restart monitor default unregister failed error=${it.message}") }
        }
        if (callbacksToUnregister.observedNetwork) {
            runCatching { connectivityManager.unregisterNetworkCallback(observedNetworkCallback) }
                .onFailure { logStore.append("app", "network restart monitor observed unregister failed error=${it.message}") }
        }
        logStore.append("app", "network restart monitor stop")
    }

    private fun recordObservedNetworkEvent(event: String, network: Network) {
        if (network == connectivityManager.activeNetwork) return
        val eventDetail = networkDetail("event", network)
        val rules = restartRulesProvider().sanitized()
        val eligibility = synchronized(lock) {
            val networkId = network.toString()
            val previousKey = lastObservedNetworkKeys[networkId]
            val decision = NetworkChangeRestartPolicy.observedRestartEligibility(
                event = event,
                observedNetworkKey = eventDetail.key,
                previousObservedNetworkKey = previousKey,
                rules = rules,
            )
            if (event == "observed_lost") {
                lastObservedNetworkKeys.remove(networkId)
            } else {
                lastObservedNetworkKeys[networkId] = eventDetail.key
            }
            decision
        }
        recordNetworkEvent(
            event = event,
            network = network,
            eventDetail = eventDetail,
            restartEligible = eligibility.scheduleRestart,
            restartSkipReason = eligibility.summary,
        )
    }

    private fun recordNetworkEvent(
        event: String,
        network: Network,
        eventDetail: NetworkDetail? = null,
        restartEligible: Boolean = true,
        restartSkipReason: String = "",
    ) {
        val sequence = synchronized(lock) {
            if (!registered) {
                null
            } else {
                eventSequence += 1
                eventSequence
            }
        } ?: return
        val snapshot = networkSnapshot(eventNetwork = network, eventDetail = eventDetail)
        val desiredRunning = desiredRunningProvider()
        logStore.append(
            "app",
            "network event seq=$sequence event=$event desired=$desiredRunning ${snapshot.summary}",
        )
        statusStore.update {
            it.copy(
                lastNetworkEventAt = TimeUtil.now(),
                lastNetworkEvent = "$event seq=$sequence",
                lastNetworkSummary = snapshot.summary,
            )
        }

        if (event == "default_lost") {
            cancelPendingRestart("network_lost seq=$sequence")
            onStatusChanged()
            return
        }

        if (!desiredRunning) {
            logStore.append("app", "network restart skipped seq=$sequence event=$event reason=proxy_not_desired")
            onStatusChanged()
            return
        }

        if (!restartEligible) {
            logStore.append("app", "network restart skipped seq=$sequence event=$event reason=$restartSkipReason")
            onStatusChanged()
            return
        }

        val rules = restartRulesProvider().sanitized()
        if (!rules.networkRestartEnabled) {
            logStore.append("app", "network restart skipped seq=$sequence event=$event reason=disabled")
            onStatusChanged()
            return
        }

        scheduleRestart(event, sequence, rules)
        onStatusChanged()
    }

    private fun scheduleRestart(trigger: String, sequence: Long, rules: RestartRules) {
        val delaySeconds = rules.networkRestartDelaySeconds
        synchronized(lock) {
            pendingRestart?.cancel(false)
            pendingRestart = scheduler.schedule(
                { runScheduledRestart(trigger, sequence) },
                delaySeconds,
                TimeUnit.SECONDS,
            )
        }
        logStore.append(
            "app",
            "network restart scheduled trigger=$trigger seq=$sequence delay=${delaySeconds}s",
        )
    }

    private fun cancelPendingRestart(reason: String) {
        val cancelled = synchronized(lock) {
            val pending = pendingRestart
            pendingRestart = null
            pending?.cancel(false) == true
        }
        if (cancelled) {
            logStore.append("app", "network restart cancelled reason=$reason")
        }
    }

    private fun runScheduledRestart(trigger: String, sequence: Long) {
        synchronized(lock) {
            pendingRestart = null
        }
        val snapshot = networkSnapshot()
        val nowMillis = System.currentTimeMillis()
        val desiredRunning = desiredRunningProvider()
        if (!snapshot.hasActiveInternetNetwork) {
            val summary = "no active internet network"
            logStore.append(
                "app",
                "network restart skipped trigger=$trigger seq=$sequence action=SKIP_NO_NETWORK $summary ${snapshot.summary}",
            )
            statusStore.update {
                it.copy(
                    lastNetworkRestartReason = summary,
                    lastNetworkSummary = snapshot.summary,
                )
            }
            onStatusChanged()
            return
        }
        val decision = synchronized(lock) {
            NetworkChangeRestartPolicy.decide(
                desiredRunning = desiredRunning,
                nowMillis = nowMillis,
                activeNetworkKey = snapshot.key,
                lastRestartAtMillis = lastRestartAtMillis,
                lastRestartNetworkKey = lastRestartNetworkKey,
                rules = restartRulesProvider().sanitized(),
            )
        }
        val checkedAt = TimeUtil.format(nowMillis)
        if (decision.action != NetworkChangeRestartAction.RESTART) {
            logStore.append(
                "app",
                "network restart skipped trigger=$trigger seq=$sequence action=${decision.action} ${decision.summary} ${snapshot.summary}",
            )
            statusStore.update {
                it.copy(
                    lastNetworkRestartReason = decision.summary,
                    lastNetworkSummary = snapshot.summary,
                )
            }
            onStatusChanged()
            return
        }

        val reason = NetworkChangeRestartPolicy.restartReason(trigger, sequence, snapshot.summary)
        synchronized(lock) {
            lastRestartAtMillis = nowMillis
            lastRestartNetworkKey = snapshot.key
        }
        statusStore.update {
            it.copy(
                lastNetworkRestartAt = checkedAt,
                lastNetworkRestartReason = reason,
                lastNetworkSummary = snapshot.summary,
                networkRestartCount = it.networkRestartCount + 1,
            )
        }
        logStore.append("app", "network restart executing reason=$reason")
        restartProxy(reason)
        onStatusChanged()
    }

    private fun networkSnapshot(eventNetwork: Network? = null, eventDetail: NetworkDetail? = null): NetworkSnapshot {
        val active = connectivityManager.activeNetwork
        val capabilities = active?.let { connectivityManager.getNetworkCapabilities(it) }
        val linkProperties = active?.let { connectivityManager.getLinkProperties(it) }
        val transports = transportsSummary(capabilities)
        val link = linkSummary(linkProperties)
        val activeId = active?.toString() ?: "none"
        val hasActiveInternetNetwork = active != null &&
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val resolvedEventDetail = eventDetail ?: eventNetwork?.let { networkDetail("event", it) }
        val eventPart = resolvedEventDetail?.let { "${it.summary} " }.orEmpty()
        val summary = eventPart +
            "active=$activeId " +
            "transports=$transports " +
            "capabilities=${capabilitiesSummary(capabilities)} " +
            link.summary
        val key = listOf(
            activeId,
            transports,
            link.interfaceName,
            link.ipv4,
            link.dns,
            resolvedEventDetail?.key ?: "event=none",
        ).joinToString("|")
        return NetworkSnapshot(summary = summary, key = key, hasActiveInternetNetwork = hasActiveInternetNetwork)
    }

    private fun networkDetail(label: String, network: Network): NetworkDetail {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val link = linkSummary(connectivityManager.getLinkProperties(network))
        val transports = transportsSummary(capabilities)
        val capabilitiesText = capabilitiesSummary(capabilities)
        val summary = "$label=$network ${label}Transports=$transports " +
            "${label}Capabilities=$capabilitiesText ${label}If=${link.interfaceName} " +
            "${label}Ipv4=${link.ipv4} ${label}Dns=${link.dns}"
        val key = "$label=$network,$transports,${link.interfaceName},${link.ipv4},${link.dns}"
        return NetworkDetail(summary = summary, key = key)
    }

    private fun transportsSummary(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "none"
        return listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
        ).filter { capabilities.hasTransport(it.first) }
            .joinToString(",") { it.second }
            .ifBlank { "unknown" }
    }

    private fun capabilitiesSummary(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "none"
        return listOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET to "internet",
            NetworkCapabilities.NET_CAPABILITY_VALIDATED to "validated",
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED to "not_metered",
            NetworkCapabilities.NET_CAPABILITY_NOT_VPN to "not_vpn",
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL to "captive_portal",
        ).filter { capabilities.hasCapability(it.first) }
            .joinToString(",") { it.second }
            .ifBlank { "none" }
    }

    private fun linkSummary(linkProperties: LinkProperties?): LinkSnapshot {
        if (linkProperties == null) {
            return LinkSnapshot(
                summary = "link=none",
                interfaceName = "none",
                ipv4 = "none",
                dns = "none",
            )
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
        val interfaceName = linkProperties.interfaceName ?: "none"
        return LinkSnapshot(
            summary = "if=$interfaceName ipv4=$ipv4 ipv6=$ipv6Count dns=$dns routes=${linkProperties.routes.size} httpProxy=$httpProxy",
            interfaceName = interfaceName,
            ipv4 = ipv4,
            dns = dns,
        )
    }

    private data class NetworkSnapshot(
        val summary: String,
        val key: String,
        val hasActiveInternetNetwork: Boolean,
    )

    private data class NetworkDetail(
        val summary: String,
        val key: String,
    )

    private data class RegisteredCallbacks(
        val defaultNetwork: Boolean,
        val observedNetwork: Boolean,
    )

    private data class LinkSnapshot(
        val summary: String,
        val interfaceName: String,
        val ipv4: String,
        val dns: String,
    )
}
