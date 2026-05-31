package com.wsy.pixelproxygateway

import org.json.JSONObject

data class RuntimeStatus(
    val statusUpdatedAt: String = "",
    val statusUpdatedAtEpochMillis: Long = 0,
    val serviceRunning: Boolean = false,
    val desiredRunning: Boolean = false,
    val proxyRunning: Boolean = false,
    val proxyPid: Long = -1,
    val bindAddress: String = "0.0.0.0",
    val httpPort: Int = 8080,
    val socksPort: Int = 1080,
    val enableHttp: Boolean = true,
    val enableSocks: Boolean = true,
    val startOnBoot: Boolean = true,
    val autoStart: Boolean = false,
    val lastStartAt: String = "",
    val lastStopAt: String = "",
    val lastExitAt: String = "",
    val lastExitCode: Int? = null,
    val lastRestartReason: String = "",
    val restartCount: Int = 0,
    val portOk: Boolean = false,
    val lastPortCheckAt: String = "",
    val requestOk: Boolean = false,
    val lastRequestCheckAt: String = "",
    val lastHttpStatus: Int = 0,
    val lastHttpProxyRequestOk: Boolean = false,
    val lastHttpProxyStatus: Int = 0,
    val lastHttpProxyError: String = "",
    val lastSocksProxyRequestOk: Boolean = false,
    val lastSocksProxyStatus: Int = 0,
    val lastSocksProxyError: String = "",
    val lastProxyRequestSummary: String = "",
    val consecutiveFailures: Int = 0,
    val lastError: String = "",
    val gostVersion: String = "",
    val gostTag: String = "",
    val gostCommit: String = "",
    val gostSha256: String = "",
    val gostPath: String = "",
    val nativeLibraryDir: String = "",
    val wakeLockHeld: Boolean = false,
    val batteryIgnoringOptimizations: Boolean = false,
    val lastNetworkEventAt: String = "",
    val lastNetworkEvent: String = "",
    val lastNetworkSummary: String = "",
    val lastNetworkProbeAt: String = "",
    val lastNetworkProbeResult: String = "",
    val networkRecoveryFailures: Int = 0,
    val networkRecoveryRestartCount: Int = 0,
    val appVersion: String = "0.1.0",
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("statusUpdatedAt", statusUpdatedAt)
            .put("statusUpdatedAtEpochMillis", statusUpdatedAtEpochMillis)
            .put("serviceRunning", serviceRunning)
            .put("desiredRunning", desiredRunning)
            .put("proxyRunning", proxyRunning)
            .put("proxyPid", proxyPid)
            .put("bindAddress", bindAddress)
            .put("httpPort", httpPort)
            .put("socksPort", socksPort)
            .put("enableHttp", enableHttp)
            .put("enableSocks", enableSocks)
            .put("startOnBoot", startOnBoot)
            .put("autoStart", autoStart)
            .put("lastStartAt", lastStartAt)
            .put("lastStopAt", lastStopAt)
            .put("lastExitAt", lastExitAt)
            .put("lastExitCode", lastExitCode)
            .put("lastRestartReason", lastRestartReason)
            .put("restartCount", restartCount)
            .put("portOk", portOk)
            .put("lastPortCheckAt", lastPortCheckAt)
            .put("requestOk", requestOk)
            .put("lastRequestCheckAt", lastRequestCheckAt)
            .put("lastHttpStatus", lastHttpStatus)
            .put("lastHttpProxyRequestOk", lastHttpProxyRequestOk)
            .put("lastHttpProxyStatus", lastHttpProxyStatus)
            .put("lastHttpProxyError", lastHttpProxyError)
            .put("lastSocksProxyRequestOk", lastSocksProxyRequestOk)
            .put("lastSocksProxyStatus", lastSocksProxyStatus)
            .put("lastSocksProxyError", lastSocksProxyError)
            .put("lastProxyRequestSummary", lastProxyRequestSummary)
            .put("consecutiveFailures", consecutiveFailures)
            .put("lastError", lastError)
            .put("gostVersion", gostVersion)
            .put("gostTag", gostTag)
            .put("gostCommit", gostCommit)
            .put("gostSha256", gostSha256)
            .put("gostPath", gostPath)
            .put("nativeLibraryDir", nativeLibraryDir)
            .put("wakeLockHeld", wakeLockHeld)
            .put("batteryIgnoringOptimizations", batteryIgnoringOptimizations)
            .put("lastNetworkEventAt", lastNetworkEventAt)
            .put("lastNetworkEvent", lastNetworkEvent)
            .put("lastNetworkSummary", lastNetworkSummary)
            .put("lastNetworkProbeAt", lastNetworkProbeAt)
            .put("lastNetworkProbeResult", lastNetworkProbeResult)
            .put("networkRecoveryFailures", networkRecoveryFailures)
            .put("networkRecoveryRestartCount", networkRecoveryRestartCount)
            .put("appVersion", appVersion)
    }

    fun toText(): String {
        return buildString {
            appendLine("statusUpdatedAt=$statusUpdatedAt")
            appendLine("statusUpdatedAtEpochMillis=$statusUpdatedAtEpochMillis")
            appendLine("serviceRunning=$serviceRunning")
            appendLine("desiredRunning=$desiredRunning")
            appendLine("proxyRunning=$proxyRunning")
            appendLine("proxyPid=$proxyPid")
            appendLine("bindAddress=$bindAddress")
            appendLine("httpPort=$httpPort")
            appendLine("socksPort=$socksPort")
            appendLine("enableHttp=$enableHttp")
            appendLine("enableSocks=$enableSocks")
            appendLine("startOnBoot=$startOnBoot")
            appendLine("autoStart=$autoStart")
            appendLine("lastStartAt=$lastStartAt")
            appendLine("lastStopAt=$lastStopAt")
            appendLine("lastExitAt=$lastExitAt")
            appendLine("lastExitCode=${lastExitCode ?: ""}")
            appendLine("lastRestartReason=$lastRestartReason")
            appendLine("restartCount=$restartCount")
            appendLine("portOk=$portOk")
            appendLine("lastPortCheckAt=$lastPortCheckAt")
            appendLine("requestOk=$requestOk")
            appendLine("lastRequestCheckAt=$lastRequestCheckAt")
            appendLine("lastHttpStatus=$lastHttpStatus")
            appendLine("lastHttpProxyRequestOk=$lastHttpProxyRequestOk")
            appendLine("lastHttpProxyStatus=$lastHttpProxyStatus")
            appendLine("lastHttpProxyError=$lastHttpProxyError")
            appendLine("lastSocksProxyRequestOk=$lastSocksProxyRequestOk")
            appendLine("lastSocksProxyStatus=$lastSocksProxyStatus")
            appendLine("lastSocksProxyError=$lastSocksProxyError")
            appendLine("lastProxyRequestSummary=$lastProxyRequestSummary")
            appendLine("consecutiveFailures=$consecutiveFailures")
            appendLine("lastError=$lastError")
            appendLine("gostVersion=$gostVersion")
            appendLine("gostTag=$gostTag")
            appendLine("gostCommit=$gostCommit")
            appendLine("gostSha256=$gostSha256")
            appendLine("gostPath=$gostPath")
            appendLine("nativeLibraryDir=$nativeLibraryDir")
            appendLine("wakeLockHeld=$wakeLockHeld")
            appendLine("batteryIgnoringOptimizations=$batteryIgnoringOptimizations")
            appendLine("lastNetworkEventAt=$lastNetworkEventAt")
            appendLine("lastNetworkEvent=$lastNetworkEvent")
            appendLine("lastNetworkSummary=$lastNetworkSummary")
            appendLine("lastNetworkProbeAt=$lastNetworkProbeAt")
            appendLine("lastNetworkProbeResult=$lastNetworkProbeResult")
            appendLine("networkRecoveryFailures=$networkRecoveryFailures")
            appendLine("networkRecoveryRestartCount=$networkRecoveryRestartCount")
            appendLine("appVersion=$appVersion")
        }
    }
}
