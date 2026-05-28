package com.wsy.pixelproxygateway

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class StatusStore(context: Context) {
    private val file = File(context.filesDir, "status.json")
    @Volatile private var status = RuntimeStatus()

    @Synchronized
    fun update(transform: (RuntimeStatus) -> RuntimeStatus): RuntimeStatus {
        val nowMillis = System.currentTimeMillis()
        status = readStatusFromDisk() ?: status
        val next = transform(status).copy(
            statusUpdatedAt = TimeUtil.format(nowMillis),
            statusUpdatedAtEpochMillis = nowMillis,
        )
        status = next
        writeStatus(next)
        return next
    }

    fun current(): RuntimeStatus = status

    @Synchronized
    fun loadFromDisk(): RuntimeStatus {
        val next = readStatusFromDisk() ?: status
        status = next
        return next
    }

    private fun readStatusFromDisk(): RuntimeStatus? {
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            RuntimeStatus(
                statusUpdatedAt = json.optString("statusUpdatedAt", ""),
                statusUpdatedAtEpochMillis = json.optLong("statusUpdatedAtEpochMillis", 0),
                serviceRunning = json.optBoolean("serviceRunning", false),
                desiredRunning = json.optBoolean("desiredRunning", false),
                proxyRunning = json.optBoolean("proxyRunning", false),
                proxyPid = json.optLong("proxyPid", -1),
                bindAddress = json.optString("bindAddress", "0.0.0.0"),
                httpPort = json.optInt("httpPort", 8080),
                socksPort = json.optInt("socksPort", 1080),
                enableHttp = json.optBoolean("enableHttp", true),
                enableSocks = json.optBoolean("enableSocks", true),
                startOnBoot = json.optBoolean("startOnBoot", true),
                autoStart = json.optBoolean("autoStart", false),
                lastStartAt = json.optString("lastStartAt", ""),
                lastStopAt = json.optString("lastStopAt", ""),
                lastExitAt = json.optString("lastExitAt", ""),
                lastExitCode = if (json.isNull("lastExitCode")) null else json.optInt("lastExitCode"),
                lastRestartReason = json.optString("lastRestartReason", ""),
                restartCount = json.optInt("restartCount", 0),
                portOk = json.optBoolean("portOk", false),
                lastPortCheckAt = json.optString("lastPortCheckAt", ""),
                requestOk = json.optBoolean("requestOk", false),
                lastRequestCheckAt = json.optString("lastRequestCheckAt", ""),
                lastHttpStatus = json.optInt("lastHttpStatus", 0),
                consecutiveFailures = json.optInt("consecutiveFailures", 0),
                lastError = json.optString("lastError", ""),
                gostVersion = json.optString("gostVersion", ""),
                gostTag = json.optString("gostTag", ""),
                gostCommit = json.optString("gostCommit", ""),
                gostSha256 = json.optString("gostSha256", ""),
                gostPath = json.optString("gostPath", ""),
                nativeLibraryDir = json.optString("nativeLibraryDir", ""),
                wakeLockHeld = json.optBoolean("wakeLockHeld", false),
                batteryIgnoringOptimizations = json.optBoolean("batteryIgnoringOptimizations", false),
                lastNetworkEventAt = json.optString("lastNetworkEventAt", ""),
                lastNetworkEvent = json.optString("lastNetworkEvent", ""),
                lastNetworkSummary = json.optString("lastNetworkSummary", ""),
                lastNetworkProbeAt = json.optString("lastNetworkProbeAt", ""),
                lastNetworkProbeResult = json.optString("lastNetworkProbeResult", ""),
                networkRecoveryFailures = json.optInt("networkRecoveryFailures", 0),
                networkRecoveryRestartCount = json.optInt("networkRecoveryRestartCount", 0),
                appVersion = json.optString("appVersion", "0.1.0"),
            )
        }.getOrNull()
    }

    private fun writeStatus(next: RuntimeStatus) {
        val payload = next.toJson().toString(2)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(payload)
        val atomicMoved = runCatching {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrDefault(false)
        if (atomicMoved) return

        val moved = runCatching {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
        if (!moved) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
