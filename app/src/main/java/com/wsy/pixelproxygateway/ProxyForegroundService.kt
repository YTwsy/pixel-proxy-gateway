package com.wsy.pixelproxygateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ProxyForegroundService : Service() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var logStore: LogStore
    private lateinit var statusStore: StatusStore
    private lateinit var manager: GostProcessManager
    private lateinit var networkChangeRestartMonitor: NetworkChangeRestartMonitor
    private lateinit var notificationManager: NotificationManager

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private var processFuture: ScheduledFuture<*>? = null
    private var requestFuture: ScheduledFuture<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationSnapshot: NotificationSnapshot? = null
    @Volatile private var config = ProxyConfig()

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        logStore = LogStore(this)
        statusStore = StatusStore(this)
        statusStore.loadFromDisk()
        manager = GostProcessManager(this, logStore, statusStore)
        networkChangeRestartMonitor = NetworkChangeRestartMonitor(
            context = this,
            logStore = logStore,
            statusStore = statusStore,
            scheduler = scheduler,
            desiredRunningProvider = { statusStore.current().desiredRunning },
            restartProxy = { reason -> manager.restart(config, reason) },
            onStatusChanged = { updateNotification() },
        )
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        config = settingsStore.load()
        statusStore.update {
            it.copy(
                serviceRunning = true,
                bindAddress = config.bindAddress,
                httpPort = config.httpPort,
                socksPort = config.socksPort,
                enableHttp = config.enableHttp,
                enableSocks = config.enableSocks,
                startOnBoot = config.startOnBoot,
                autoStart = config.autoStart,
                nativeLibraryDir = applicationInfo.nativeLibraryDir,
                wakeLockHeld = wakeLock?.isHeld == true,
                batteryIgnoringOptimizations = isIgnoringBatteryOptimizations(),
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
        startAsForeground()
        logStore.append("app", "service created")
        if (config.autoStart) {
            if (!startProxy(config, "service_restore")) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        }
        networkChangeRestartMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val action = intent?.action ?: Actions.START
        val updated = settingsStore.load().withIntentOverrides(intent)
        when (action) {
            Actions.STOP -> {
                stopProxy("adb_or_ui_stop")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            Actions.RESTART -> {
                val nextConfig = updated.copy(autoStart = true).sanitized()
                nextConfig.startValidationError()?.let { error ->
                    rejectStartConfig(nextConfig, "adb_or_ui_restart", error)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                config = nextConfig
                settingsStore.save(config)
                startWatchdogs()
                acquireWakeLock()
                manager.restart(config, "adb_or_ui_restart")
            }
            Actions.CHECK_HEALTH -> {
                config = config.withHealthSettingsFrom(updated)
                settingsStore.save(config)
                statusStore.update {
                    it.copy(
                        serviceRunning = true,
                        bindAddress = config.bindAddress,
                        httpPort = config.httpPort,
                        socksPort = config.socksPort,
                        enableHttp = config.enableHttp,
                        enableSocks = config.enableSocks,
                        startOnBoot = config.startOnBoot,
                        autoStart = config.autoStart,
                        wakeLockHeld = wakeLock?.isHeld == true,
                        batteryIgnoringOptimizations = isIgnoringBatteryOptimizations(),
                    )
                }
                runManualHealthCheck(config)
            }
            Actions.REPOST_NOTIFICATION -> {
                updateNotification(force = true)
            }
            Actions.APPLY_CONFIG -> {
                config = updated.copy(autoStart = config.autoStart).sanitized()
                settingsStore.save(config)
                statusStore.update {
                    it.copy(
                        bindAddress = config.bindAddress,
                        httpPort = config.httpPort,
                        socksPort = config.socksPort,
                        enableHttp = config.enableHttp,
                        enableSocks = config.enableSocks,
                        startOnBoot = config.startOnBoot,
                        autoStart = config.autoStart,
                    )
                }
                if (statusStore.current().desiredRunning) {
                    config.startValidationError()?.let { error ->
                        rejectStartConfig(config, "config_changed", error)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(startId)
                        return START_NOT_STICKY
                    }
                    manager.restart(config, "config_changed")
                    startWatchdogs()
                    acquireWakeLock()
                } else {
                    logStore.append("app", "config applied while proxy stopped")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
            }
            else -> {
                val nextConfig = updated.copy(autoStart = true).sanitized()
                if (!startProxy(nextConfig, "adb_or_ui_start")) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProxy(nextConfig: ProxyConfig, reason: String): Boolean {
        val requested = nextConfig.copy(autoStart = true).sanitized()
        requested.startValidationError()?.let { error ->
            rejectStartConfig(requested, reason, error)
            return false
        }
        config = requested
        settingsStore.save(config)
        acquireWakeLock()
        startWatchdogs()
        manager.start(config, reason)
        logStore.append("app", "proxy start requested reason=$reason")
        return true
    }

    private fun rejectStartConfig(requested: ProxyConfig, reason: String, error: String) {
        config = requested.copy(autoStart = false).sanitized()
        settingsStore.save(config)
        stopWatchdogs()
        manager.stop("invalid_config:$reason")
        releaseWakeLock()
        statusStore.update {
            it.copy(
                bindAddress = config.bindAddress,
                httpPort = config.httpPort,
                socksPort = config.socksPort,
                enableHttp = config.enableHttp,
                enableSocks = config.enableSocks,
                startOnBoot = config.startOnBoot,
                autoStart = config.autoStart,
                portOk = false,
                requestOk = false,
                consecutiveFailures = 0,
                lastHttpStatus = 0,
                lastRestartReason = "invalid_config:$reason",
                lastError = error,
            )
        }
        logStore.append("app", "proxy start rejected reason=$reason error=$error")
        updateNotification()
    }

    private fun stopProxy(reason: String) {
        config = settingsStore.load().copy(autoStart = false).sanitized()
        settingsStore.save(config)
        stopWatchdogs()
        manager.stop(reason)
        releaseWakeLock()
        statusStore.update {
            it.copy(
                portOk = false,
                requestOk = false,
                consecutiveFailures = 0,
                lastHttpStatus = 0,
                startOnBoot = config.startOnBoot,
                autoStart = config.autoStart,
            )
        }
        logStore.append("app", "proxy stop requested reason=$reason")
        updateNotification()
    }

    private fun runManualHealthCheck(healthConfig: ProxyConfig) {
        scheduler.execute {
            runCatching {
                val result = HealthWatchdogs.checkAll(healthConfig)
                val checkedAt = TimeUtil.now()
                statusStore.update {
                    HealthStatus.applyManualCheck(it, healthConfig, result, checkedAt).copy(
                        wakeLockHeld = wakeLock?.isHeld == true,
                        batteryIgnoringOptimizations = isIgnoringBatteryOptimizations(),
                    )
                }
                logStore.append("app", HealthStatus.logLine(result))
                updateNotification()
            }.getOrElse {
                logStore.append("app", "manual health check error=${it.message}")
            }
        }
    }

    private fun startWatchdogs() {
        processFuture?.cancel(false)
        requestFuture?.cancel(false)
        processFuture = scheduler.scheduleWithFixedDelay({
            runCatching {
                acquireWakeLock()
                manager.markProcessCheck()
                val (ok, message) = HealthWatchdogs.checkPorts(config, config.timeoutSeconds * 1000)
                statusStore.update {
                    it.copy(
                        portOk = ok,
                        lastPortCheckAt = TimeUtil.now(),
                        wakeLockHeld = wakeLock?.isHeld == true,
                        batteryIgnoringOptimizations = isIgnoringBatteryOptimizations(),
                        lastError = if (ok) it.lastError else message,
                    )
                }
                if (!ok && statusStore.current().desiredRunning) manager.restart("port_watchdog:$message")
                updateNotification()
            }.getOrElse {
                logStore.append("app", "port watchdog error=${it.message}")
            }
        }, 5, 30, TimeUnit.SECONDS)

        requestFuture = scheduler.scheduleWithFixedDelay({
            runCatching {
                val result = HealthWatchdogs.checkRequest(config)
                val next = statusStore.update {
                    it.copy(
                        requestOk = result.ok,
                        lastRequestCheckAt = TimeUtil.now(),
                        lastHttpStatus = result.status,
                        wakeLockHeld = wakeLock?.isHeld == true,
                        batteryIgnoringOptimizations = isIgnoringBatteryOptimizations(),
                        consecutiveFailures = if (result.ok) 0 else it.consecutiveFailures + 1,
                        lastError = if (result.ok) "" else result.error,
                    )
                }
                if (!result.ok) {
                    logStore.append("app", "request watchdog fail count=${next.consecutiveFailures} error=${result.error}")
                }
                if (!result.ok && next.consecutiveFailures >= config.failureThreshold) {
                    manager.restart("request_watchdog:${result.error}")
                    statusStore.update { it.copy(consecutiveFailures = 0) }
                }
                updateNotification()
            }.getOrElse {
                logStore.append("app", "request watchdog error=${it.message}")
            }
        }, 10, config.intervalSeconds, TimeUnit.SECONDS)
    }

    private fun stopWatchdogs() {
        processFuture?.cancel(false)
        requestFuture?.cancel(false)
        processFuture = null
        requestFuture = null
    }

    private fun acquireWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.acquire(WAKE_LOCK_TIMEOUT_MS)
                return
            }
        }
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixelProxyGateway:ProxyService").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        statusStore.update { it.copy(wakeLockHeld = true, batteryIgnoringOptimizations = isIgnoringBatteryOptimizations()) }
        logStore.append("app", "wake_lock=acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        statusStore.update { it.copy(wakeLockHeld = false, batteryIgnoringOptimizations = isIgnoringBatteryOptimizations()) }
        logStore.append("app", "wake_lock=released")
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return runCatching {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = "Proxy gateway foreground service"
        notificationManager.createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val status = statusStore.current()
        lastNotificationSnapshot = notificationSnapshot(status)
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(force: Boolean = false) {
        val status = statusStore.current()
        val snapshot = notificationSnapshot(status)
        if (!force && snapshot == lastNotificationSnapshot) return
        lastNotificationSnapshot = snapshot
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: RuntimeStatus): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deletePending = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyForegroundService::class.java).setAction(Actions.REPOST_NOTIFICATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val listeners = notificationListenerSummary()
        val text = if (status.proxyRunning && status.portOk && status.requestOk) {
            "$listeners; health ok"
        } else if (status.proxyRunning && !status.portOk) {
            "Proxy running; port check failed"
        } else if (status.proxyRunning && !status.requestOk) {
            "Proxy running; request check failed"
        } else {
            "Proxy core stopped; watchdog ${if (status.desiredRunning) "armed" else "idle"}"
        }
        val keepNotification = shouldKeepNotification(status)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle("Pixel Proxy Gateway")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(keepNotification)
            .setAutoCancel(false)
            .setContentIntent(pending)
            .setDeleteIntent(deletePending)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .build()
        if (keepNotification) {
            notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        }
        return notification
    }

    private fun notificationListenerSummary(): String {
        val listeners = mutableListOf<String>()
        if (config.enableHttp) listeners += "HTTP ${config.httpPort}"
        if (config.enableSocks) listeners += "SOCKS ${config.socksPort}"
        return listeners.ifEmpty { listOf("No listeners") }.joinToString(", ")
    }

    private fun notificationSnapshot(status: RuntimeStatus): NotificationSnapshot {
        return NotificationSnapshot(
            proxyRunning = status.proxyRunning,
            serviceRunning = status.serviceRunning,
            desiredRunning = status.desiredRunning,
            portOk = status.portOk,
            requestOk = status.requestOk,
            enableHttp = config.enableHttp,
            enableSocks = config.enableSocks,
            httpPort = config.httpPort,
            socksPort = config.socksPort,
        )
    }

    private fun shouldKeepNotification(status: RuntimeStatus): Boolean {
        return status.serviceRunning || status.desiredRunning || status.proxyRunning
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println(statusStore.current().toText())
        writer.println("config=${config.toJson(includePassword = false)}")
        writer.println("logs:")
        writer.println(logStore.tailAll(80))
    }

    override fun onDestroy() {
        logStore.append("app", "service destroyed")
        statusStore.update { it.copy(serviceRunning = false) }
        networkChangeRestartMonitor.stop()
        stopWatchdogs()
        releaseWakeLock()
        manager.shutdown()
        scheduler.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "proxy_gateway_status"
        private const val NOTIFICATION_ID = 1001
        private val WAKE_LOCK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10)

        fun startIntent(context: Context, action: String = Actions.START): Intent {
            return Intent(context, ProxyForegroundService::class.java).setAction(action)
        }
    }

    private data class NotificationSnapshot(
        val proxyRunning: Boolean,
        val serviceRunning: Boolean,
        val desiredRunning: Boolean,
        val portOk: Boolean,
        val requestOk: Boolean,
        val enableHttp: Boolean,
        val enableSocks: Boolean,
        val httpPort: Int,
        val socksPort: Int,
    )
}

private fun ProxyConfig.withHealthSettingsFrom(requested: ProxyConfig): ProxyConfig {
    return copy(
        healthUrl = requested.healthUrl,
        expectedStatus = requested.expectedStatus,
        intervalSeconds = requested.intervalSeconds,
        timeoutSeconds = requested.timeoutSeconds,
        failureThreshold = requested.failureThreshold,
        startOnBoot = requested.startOnBoot,
    ).sanitized()
}
