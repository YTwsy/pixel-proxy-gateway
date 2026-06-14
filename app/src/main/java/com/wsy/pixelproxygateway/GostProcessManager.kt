package com.wsy.pixelproxygateway

import android.os.Process as AndroidProcess
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
    @Volatile private var restartDelaySeconds = RestartDelayPolicy.INITIAL_DELAY_SECONDS
    @Volatile private var restartDelayResetFuture: ScheduledFuture<*>? = null
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
                restart(config, "start_config_changed:$reason")
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
        cancelScheduledRestart()
        val stopped = stopCurrentProcess(reason)
        val current = process
        val currentPid = ProcessUtil.pidOf(current)
        statusStore.update {
            it.copy(
                desiredRunning = false,
                proxyRunning = !stopped && current?.isAlive == true,
                proxyPid = if (!stopped && current?.isAlive == true) currentPid else -1,
                lastStopAt = TimeUtil.now(),
                lastRestartReason = reason,
                lastError = if (stopped) it.lastError else "gost stop timed out:$reason",
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
        desiredRunning = true
        statusStore.update {
            it.copy(
                desiredRunning = true,
                startOnBoot = config.startOnBoot,
                autoStart = config.autoStart,
                lastRestartReason = reason,
            )
        }
        cancelScheduledRestart()
        if (!stopCurrentProcess("restart:$reason")) {
            scheduleRestart("restart_wait:$reason", immediate = false)
            return
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
            process?.let { current ->
                if (current.isAlive) {
                    val pid = ProcessUtil.pidOf(current)
                    logStore.append("app", "gost start deferred reason=$reason current_pid=$pid")
                    statusStore.update {
                        it.copy(proxyRunning = true, proxyPid = pid, lastError = "gost still stopping:$reason")
                    }
                    if (desiredRunning) scheduleRestart("start_deferred:$reason", immediate = false)
                    return
                }
                process = null
            }
            if (!stopUntrackedGostProcesses("pre_start:$reason")) {
                if (desiredRunning) scheduleRestart("stale_process:$reason", immediate = false)
                return
            }
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
            scheduleRestartDelayReset(started)
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
        val next = RestartDelayPolicy.next(restartDelaySeconds, immediate)
        val delay = next.delaySeconds
        restartDelaySeconds = next.nextDelaySeconds
        logStore.append("app", "scheduling gost restart reason=$reason delay=${delay}s")
        statusStore.update { it.copy(lastRestartReason = reason, lastError = reason) }
        restartFuture = supervisor.schedule({ runScheduledRestart(reason) }, delay, TimeUnit.SECONDS)
    }

    @Synchronized
    private fun runScheduledRestart(reason: String) {
        restartFuture = null
        process?.let { current ->
            if (current.isAlive && !stopCurrentProcess("scheduled_restart:$reason")) {
                if (desiredRunning) scheduleRestart("restart_wait:$reason", immediate = false)
                return
            }
        }
        startNow(reason)
    }

    fun shutdown() {
        stop("service_destroy")
        supervisor.shutdownNow()
    }

    private fun cancelScheduledRestart() {
        restartFuture?.cancel(false)
        restartFuture = null
        restartDelayResetFuture?.cancel(false)
        restartDelayResetFuture = null
    }

    private fun stopCurrentProcess(reason: String): Boolean {
        val current = process ?: return true
        val pid = ProcessUtil.pidOf(current)
        if (!current.isAlive) {
            process = null
            return true
        }

        logStore.append("app", "stopping gost pid=$pid reason=$reason")
        current.destroy()
        var stopped = current.waitForExit(GOST_STOP_GRACE_SECONDS)
        if (!stopped && current.isAlive) {
            logStore.append("app", "gost stop timed out pid=$pid reason=$reason action=force")
            current.destroyForcibly()
            stopped = current.waitForExit(GOST_FORCE_STOP_GRACE_SECONDS)
        }

        if (stopped || !current.isAlive) {
            if (process === current) process = null
            return true
        }

        logStore.append("app", "gost still alive pid=$pid reason=$reason")
        statusStore.update {
            it.copy(proxyRunning = true, proxyPid = pid, lastError = "gost stop timed out:$reason")
        }
        return false
    }

    private fun stopUntrackedGostProcesses(reason: String): Boolean {
        val trackedPid = ProcessUtil.pidOf(process).takeIf { it > 0 }
        val stalePids = runningGostPids().filter { it != trackedPid }
        if (stalePids.isEmpty()) return true

        stalePids.forEach { pid ->
            logStore.append("app", "stopping untracked gost pid=$pid reason=$reason")
            runCatching { AndroidProcess.killProcess(pid.toInt()) }
                .onFailure { logStore.append("app", "untracked gost kill failed pid=$pid error=${it.message}") }
        }

        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(GOST_FORCE_STOP_GRACE_SECONDS)
        while (System.currentTimeMillis() < deadline) {
            val remaining = runningGostPids().filter { it != trackedPid }
            if (remaining.isEmpty()) return true
            Thread.sleep(100)
        }

        val remaining = runningGostPids().filter { it != trackedPid }
        if (remaining.isEmpty()) return true
        val message = "untracked gost still running pids=${remaining.joinToString(",")}"
        logStore.append("app", "$message reason=$reason")
        statusStore.update { it.copy(lastError = message) }
        return false
    }

    private fun runningGostPids(): List<Long> {
        return GOST_PROCESS_NAMES
            .flatMap { processName -> pidOf(processName) }
            .distinct()
    }

    private fun pidOf(processName: String): List<Long> {
        return runCatching {
            val finder = ProcessBuilder("pidof", processName)
                .redirectErrorStream(true)
                .start()
            val output = finder.inputStream.bufferedReader().readText()
            if (!finder.waitFor(1, TimeUnit.SECONDS)) {
                finder.destroyForcibly()
                return@runCatching emptyList()
            }
            if (finder.exitValue() != 0) return@runCatching emptyList()
            output.trim()
                .split(Regex("\\s+"))
                .mapNotNull { it.toLongOrNull() }
                .filter { it > 0 }
        }.getOrDefault(emptyList())
    }

    private fun Process.waitForExit(timeoutSeconds: Long): Boolean {
        return runCatching { waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    private fun scheduleRestartDelayReset(started: Process) {
        restartDelayResetFuture?.cancel(false)
        restartDelayResetFuture = supervisor.schedule({
            synchronized(this) {
                if (process === started && started.isAlive) {
                    restartDelaySeconds = RestartDelayPolicy.INITIAL_DELAY_SECONDS
                    logStore.append("app", "gost restart backoff reset pid=${ProcessUtil.pidOf(started)}")
                }
            }
        }, RESTART_BACKOFF_RESET_SECONDS, TimeUnit.SECONDS)
    }

    companion object {
        private val GOST_PROCESS_NAMES = listOf("libgost.so", "gost")
        private const val GOST_STOP_GRACE_SECONDS = 3L
        private const val GOST_FORCE_STOP_GRACE_SECONDS = 2L
        private const val RESTART_BACKOFF_RESET_SECONDS = 30L
    }
}

internal object RestartDelayPolicy {
    const val INITIAL_DELAY_SECONDS = 5L
    private const val MAX_DELAY_SECONDS = 300L

    fun next(currentDelaySeconds: Long, immediate: Boolean): RestartDelay {
        if (immediate) {
            return RestartDelay(delaySeconds = 0, nextDelaySeconds = currentDelaySeconds)
        }
        return RestartDelay(
            delaySeconds = currentDelaySeconds,
            nextDelaySeconds = min(currentDelaySeconds * 2, MAX_DELAY_SECONDS),
        )
    }
}

internal data class RestartDelay(
    val delaySeconds: Long,
    val nextDelaySeconds: Long,
)
