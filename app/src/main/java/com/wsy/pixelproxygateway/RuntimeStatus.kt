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
            appendLine("appVersion=$appVersion")
        }
    }
}
