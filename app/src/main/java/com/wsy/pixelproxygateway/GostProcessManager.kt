package com.wsy.pixelproxygateway

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

class GostProcessManager(
    context: Context,
    private val logStore: LogStore,
    private val statusStore: StatusStore,
) {
    private val appContext = context.applicationContext
    private val installer = GostBinaryInstaller(appContext, logStore)
    private val supervisor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val gostDir = File(appContext.filesDir, "gost").apply { mkdirs() }
    private val configFile = File(gostDir, "gost.yaml")

    @Volatile private var process: Process? = null
    @Volatile private var desiredRunning = false
    @Volatile private var restartFuture: ScheduledFuture<*>? = null
    @Volatile private var restartDelaySeconds = 5L
    @Volatile private var config: ProxyConfig = ProxyConfig()

    @Synchronized
    fun start(newConfig: ProxyConfig, reason: String = "start") {
        val nextConfig = newConfig.sanitized()
        val configChanged = nextConfig != config
        config = nextConfig
        config.startValidationError()?.let { error ->
            rejectInvalidStart(error, reason)
            return
        }
        desiredRunning = true
        statusStore.update {
            it.copy(
                desiredRunning = true,
                bindAddress = config.bindAddress,
                httpPort = config.httpPort,
                socksPort = config.socksPort,
                enableHttp = config.enableHttp,
                enableSocks = config.enableSocks,
                startOnBoot = config.startOnBoot,
                autoStart = config.autoStart,
                lastRestartReason = reason,
            )
        }
        if (isRunning()) {
            if (configChanged) {
                logStore.append("app", "gost restart requested reason=start_config_changed:$reason")
                stop("restart:start_config_changed:$reason")
                desiredRunning = true
                statusStore.update { it.copy(desiredRunning = true, lastRestartReason = "start_config_changed:$reason") }
                scheduleRestart("start_config_changed:$reason", immediate = true)
            } else {
                logStore.append("app", "gost start skipped reason=already_running")
            }
            return
        }
        startNow(reason)
    }

    @Synchronized
    fun stop(reason: String = "stop") {
        desiredRunning = false
        restartFuture?.cancel(false)
        restartFuture = null
        val current = process
        process = null
        if (current != null) {
            logStore.append("app", "stopping gost reason=$reason")
            current.destroy()
            runCatching {
                if (!current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly()
            }
        }
        statusStore.update {
            it.copy(
                desiredRunning = false,
                proxyRunning = false,
                proxyPid = -1,
                lastStopAt = TimeUtil.now(),
                lastRestartReason = reason,
            )
        }
    }

    @Synchronized
    fun restart(newConfig: ProxyConfig, reason: String) {
        config = newConfig.sanitized()
        logStore.append("app", "restart requested reason=$reason")
        config.startValidationError()?.let { error ->
            rejectInvalidStart(error, reason)
            return
        }
        stop("restart:$reason")
        desiredRunning = true
        statusStore.update {
            it.copy(
                desiredRunning = true,
                startOnBoot = config.startOnBoot,
                autoStart = config.autoStart,
                lastRestartReason = reason,
            )
        }
        scheduleRestart(reason, immediate = true)
    }

    @Synchronized
    fun restart(reason: String) {
        restart(config, reason)
    }

    fun isRunning(): Boolean = process?.isAlive == true

    fun markProcessCheck() {
        val current = process
        val running = current?.isAlive == true
        statusStore.update { it.copy(proxyRunning = running, proxyPid = ProcessUtil.pidOf(current)) }
        if (desiredRunning && !running) {
            scheduleRestart("process_watchdog", immediate = false)
        }
    }

    @Synchronized
    private fun startNow(reason: String) {
        runCatching {
            if (!config.enableHttp && !config.enableSocks) error("Both HTTP and SOCKS listeners are disabled")
            val binary = installer.ensureInstalled()
            GostConfigWriter.write(config, configFile)
            val version = installer.version(binary)
            val assetInfo = installer.assetInfo()
            val builder = ProcessBuilder(binary.absolutePath, "-C", configFile.absolutePath)
                .directory(gostDir)
                .redirectErrorStream(true)
            val started = builder.start()
            process = started
            restartDelaySeconds = 5L
            val pid = ProcessUtil.pidOf(started)
            logStore.append("app", "gost started pid=$pid reason=$reason")
            statusStore.update {
                it.copy(
                    proxyRunning = true,
                    proxyPid = pid,
                    lastStartAt = TimeUtil.now(),
                    lastError = "",
                    gostVersion = version,
                    gostTag = assetInfo.tag,
                    gostCommit = assetInfo.commit,
                    gostSha256 = assetInfo.sha256,
                    gostPath = binary.absolutePath,
                    nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir,
                    restartCount = it.restartCount + 1,
                )
            }
            Thread({ pumpOutput(started) }, "gost-output").start()
            Thread({ waitForExit(started) }, "gost-wait").start()
        }.getOrElse { throwable ->
            val message = throwable.message ?: throwable.javaClass.simpleName
            logStore.append("app", "gost start failed error=$message")
            statusStore.update {
                it.copy(proxyRunning = false, proxyPid = -1, lastError = message)
            }
            if (desiredRunning) scheduleRestart("start_failed:$message", immediate = false)
        }
    }

    private fun pumpOutput(started: Process) {
        runCatching {
            started.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { logStore.append("gost", it) }
            }
        }.getOrElse {
            logStore.append("app", "gost output reader stopped error=${it.message}")
        }
    }

    private fun waitForExit(started: Process) {
        val exitCode = runCatching { started.waitFor() }.getOrDefault(-999)
        val isCurrentProcess = synchronized(this) {
            if (process === started) {
                process = null
                true
            } else {
                false
            }
        }
        val pid = ProcessUtil.pidOf(started)
        logStore.append("app", "gost exited pid=$pid code=$exitCode desired=$desiredRunning current=$isCurrentProcess")
        if (!isCurrentProcess) {
            logStore.append("app", "ignoring stale gost exit pid=$pid code=$exitCode")
            return
        }
        statusStore.update {
            it.copy(
                proxyRunning = false,
                proxyPid = -1,
                lastExitAt = TimeUtil.now(),
                lastExitCode = exitCode,
            )
        }
        if (desiredRunning) scheduleRestart("process_exit:$exitCode", immediate = false)
    }

    @Synchronized
    private fun rejectInvalidStart(error: String, reason: String) {
        logStore.append("app", "gost start rejected reason=$reason error=$error")
        stop("invalid_config:$reason")
        statusStore.update {
            it.copy(
                desiredRunning = false,
                proxyRunning = false,
                proxyPid = -1,
                bindAddress = config.bindAddress,
                httpPort = config.httpPort,
                socksPort = config.socksPort,
                enableHttp = config.enableHttp,
                enableSocks = config.enableSocks,
                startOnBoot = config.startOnBoot,
                autoStart = config.autoStart,
                lastRestartReason = "invalid_config:$reason",
                lastError = error,
            )
        }
    }

    @Synchronized
    private fun scheduleRestart(reason: String, immediate: Boolean) {
        if (!desiredRunning) return
        if (restartFuture?.isDone == false) return
        val delay = if (immediate) 0 else restartDelaySeconds
        restartDelaySeconds = min(restartDelaySeconds * 2, 300)
        logStore.append("app", "scheduling gost restart reason=$reason delay=${delay}s")
        statusStore.update { it.copy(lastRestartReason = reason, lastError = reason) }
        restartFuture = supervisor.schedule({ runScheduledRestart(reason) }, delay, TimeUnit.SECONDS)
    }

    @Synchronized
    private fun runScheduledRestart(reason: String) {
        restartFuture = null
        startNow(reason)
    }

    fun shutdown() {
        stop("service_destroy")
        supervisor.shutdownNow()
    }
}
