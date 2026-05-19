package com.wsy.pixelproxygateway

import android.content.Context
import android.content.Intent

object ServiceLauncher {
    fun startForeground(context: Context, intent: Intent, source: String): Boolean {
        val appContext = context.applicationContext
        return runCatching {
            appContext.startForegroundService(intent)
        }.onFailure { throwable ->
            val message = throwable.message ?: throwable.javaClass.simpleName
            val error = "service_launch_failed:$source:$message"
            runCatching { LogStore(appContext).append("app", error) }
            runCatching {
                val statusStore = StatusStore(appContext)
                statusStore.loadFromDisk()
                statusStore.update {
                    it.copy(
                        serviceRunning = false,
                        desiredRunning = false,
                        lastRestartReason = source,
                        lastError = error,
                    )
                }
            }
        }.isSuccess
    }
}
