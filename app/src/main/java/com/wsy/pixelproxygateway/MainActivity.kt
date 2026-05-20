package com.wsy.pixelproxygateway

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var statusStore: StatusStore
    private lateinit var logStore: LogStore

    private lateinit var bindAddress: EditText
    private lateinit var httpPort: EditText
    private lateinit var socksPort: EditText
    private lateinit var enableHttp: CheckBox
    private lateinit var enableSocks: CheckBox
    private lateinit var authEnabled: CheckBox
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var healthUrl: EditText
    private lateinit var expectedStatus: EditText
    private lateinit var intervalSeconds: EditText
    private lateinit var timeoutSeconds: EditText
    private lateinit var failureThreshold: EditText
    private lateinit var startOnBoot: CheckBox
    private lateinit var statusPill: TextView
    private lateinit var statusSummary: TextView
    private lateinit var endpointText: TextView
    private lateinit var sentTrafficText: TextView
    private lateinit var receivedTrafficText: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var scrollView: ScrollView
    private var firstTrafficSample: TrafficSample? = null
    private var previousTrafficSample: TrafficSample? = null
    private var maxTxBps: Long = 0
    private var maxRxBps: Long = 0

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus(updateDetails = false)
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        statusStore = StatusStore(this)
        logStore = LogStore(this)
        requestNotificationPermission()
        buildUi()
        loadConfig(settingsStore.load())
        refreshStatus(updateDetails = true)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_SCREEN)
        }
        applySystemBarPadding(root)

        statusPill = pill("Stopped", COLOR_MUTED_BG, COLOR_MUTED_TEXT)
        statusSummary = bodyText(monospace = false).apply {
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(10), 0, 0)
            lockLines(STATUS_SUMMARY_LINES)
        }
        root.addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    title("Pixel Proxy Gateway"),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(statusPill)
            })
            addView(statusSummary)
        })

        endpointText = bodyText(monospace = false).apply {
            textSize = 14f
            lockLines(ENDPOINT_LINES)
        }
        root.addView(card().apply {
            addView(section("Proxy endpoints", topPaddingDp = 0))
            addView(endpointText)
        })

        sentTrafficText = metricText()
        receivedTrafficText = metricText()
        root.addView(card().apply {
            addView(section("Traffic", topPaddingDp = 0))
            addView(row(sentTrafficText, receivedTrafficText))
        })

        root.addView(card().apply {
            addView(section("Controls", topPaddingDp = 0))
            addView(row(
                button("Save") { saveOnly() },
                button("Start") { send(Actions.START) },
                button("Stop") { send(Actions.STOP) },
            ))
            addView(row(
                button("Restart") { send(Actions.RESTART) },
                button("Health") { checkHealthNow() },
                button("Copy") { copyStatus() },
            ))
            addView(row(
                button("Battery") { openBatterySettings() },
            ))
        })

        root.addView(card().apply {
            addView(section("Listeners", topPaddingDp = 0))
            addView(row(
                listenerCheck(isHttp = true),
                listenerCheck(isHttp = false),
            ))
            addView(row(
                portField(isHttp = true),
                portField(isHttp = false),
            ))
            bindAddress = field("Bind address", InputType.TYPE_CLASS_TEXT)
            addView(bindAddress)

            addView(section("Authentication"))
            authEnabled = check("Require username and password")
            addView(authEnabled)
            addView(row(
                usernameField(),
                passwordField(),
            ))
        })

        root.addView(card().apply {
            addView(section("Watchdog", topPaddingDp = 0))
            healthUrl = field("Health URL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            addView(healthUrl)
            expectedStatus = field("Expected status", InputType.TYPE_CLASS_TEXT)
            addView(expectedStatus)
            addView(row(
                intervalField(),
                timeoutField(),
                failureField(),
            ))
            startOnBoot = check("Restore after boot")
            addView(startOnBoot)
        })

        root.addView(card().apply {
            addView(section("Status", topPaddingDp = 0))
            statusText = bodyText()
            addView(statusText)
        })

        root.addView(card(bottomMarginDp = 24).apply {
            addView(section("Logs", topPaddingDp = 0))
            logText = bodyText()
            addView(logText)
        })

        authEnabled.setOnCheckedChangeListener { _, _ -> updateAuthFields() }

        scrollView = ScrollView(this).apply {
            setBackgroundColor(COLOR_SCREEN)
            addView(root)
        }
        setContentView(scrollView)
    }

    private fun listenerCheck(isHttp: Boolean): View {
        val view = check(if (isHttp) "HTTP listener" else "SOCKS5 listener")
        if (isHttp) {
            enableHttp = view
        } else {
            enableSocks = view
        }
        return view
    }

    private fun portField(isHttp: Boolean): View {
        val view = field(if (isHttp) "HTTP port" else "SOCKS5 port", InputType.TYPE_CLASS_NUMBER)
        if (isHttp) {
            httpPort = view
        } else {
            socksPort = view
        }
        return view
    }

    private fun usernameField(): View {
        username = field("Username", InputType.TYPE_CLASS_TEXT)
        return username
    }

    private fun passwordField(): View {
        password = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        return password
    }

    private fun intervalField(): View {
        intervalSeconds = field("Interval", InputType.TYPE_CLASS_NUMBER)
        return intervalSeconds
    }

    private fun timeoutField(): View {
        timeoutSeconds = field("Timeout", InputType.TYPE_CLASS_NUMBER)
        return timeoutSeconds
    }

    private fun failureField(): View {
        failureThreshold = field("Failures", InputType.TYPE_CLASS_NUMBER)
        return failureThreshold
    }

    @SuppressLint("SetTextI18n")
    private fun loadConfig(config: ProxyConfig) {
        bindAddress.setText(config.bindAddress)
        httpPort.setText(config.httpPort.toString())
        socksPort.setText(config.socksPort.toString())
        enableHttp.isChecked = config.enableHttp
        enableSocks.isChecked = config.enableSocks
        authEnabled.isChecked = config.authEnabled
        username.setText(config.username)
        password.setText(config.password)
        healthUrl.setText(config.healthUrl)
        expectedStatus.setText(config.expectedStatus)
        intervalSeconds.setText(config.intervalSeconds.toString())
        timeoutSeconds.setText(config.timeoutSeconds.toString())
        failureThreshold.setText(config.failureThreshold.toString())
        startOnBoot.isChecked = config.startOnBoot
        updateAuthFields()
    }

    private fun readConfig(): ProxyConfig {
        val current = settingsStore.load()
        return current.copy(
            bindAddress = bindAddress.text.toString(),
            httpPort = httpPort.intValue(8080),
            socksPort = socksPort.intValue(1080),
            enableHttp = enableHttp.isChecked,
            enableSocks = enableSocks.isChecked,
            authEnabled = authEnabled.isChecked,
            username = username.text.toString(),
            password = password.text.toString(),
            healthUrl = healthUrl.text.toString(),
            expectedStatus = expectedStatus.text.toString(),
            intervalSeconds = intervalSeconds.longValue(300),
            timeoutSeconds = timeoutSeconds.intValue(15),
            failureThreshold = failureThreshold.intValue(2),
            startOnBoot = startOnBoot.isChecked,
        ).sanitized()
    }

    private fun saveOnly() {
        val config = readConfig()
        val error = config.startValidationError()
        if (error != null) {
            toast(error)
            return
        }
        settingsStore.save(config)
        launchService(Actions.APPLY_CONFIG, config, "Saved")
    }

    private fun send(action: String) {
        val config = readConfig()
        if (action != Actions.STOP) {
            val error = config.startValidationError()
            if (error != null) {
                toast(error)
                return
            }
        }
        settingsStore.save(config)
        launchService(action, config, action.substringAfterLast('.').lowercase())
    }

    private fun launchService(action: String, config: ProxyConfig, successMessage: String) {
        val intent = Intent(this, ProxyForegroundService::class.java)
            .setAction(action)
            .putExtra(Actions.EXTRA_BIND_ADDRESS, config.bindAddress)
            .putExtra(Actions.EXTRA_HTTP_PORT, config.httpPort)
            .putExtra(Actions.EXTRA_SOCKS_PORT, config.socksPort)
            .putExtra(Actions.EXTRA_ENABLE_HTTP, config.enableHttp)
            .putExtra(Actions.EXTRA_ENABLE_SOCKS, config.enableSocks)
            .putExtra(Actions.EXTRA_AUTH_ENABLED, config.authEnabled)
            .putExtra(Actions.EXTRA_USERNAME, config.username)
            .putExtra(Actions.EXTRA_PASSWORD, config.password)
            .putExtra(Actions.EXTRA_HEALTH_URL, config.healthUrl)
            .putExtra(Actions.EXTRA_EXPECTED_STATUS, config.expectedStatus)
            .putExtra(Actions.EXTRA_INTERVAL_SECONDS, config.intervalSeconds)
            .putExtra(Actions.EXTRA_TIMEOUT_SECONDS, config.timeoutSeconds)
            .putExtra(Actions.EXTRA_FAILURE_THRESHOLD, config.failureThreshold)
            .putExtra(Actions.EXTRA_START_ON_BOOT, config.startOnBoot)
        val started = ServiceLauncher.startForeground(this, intent, "ui:${action.substringAfterLast('.')}")
        toast(if (started) successMessage else "service launch failed")
        refreshStatus(updateDetails = true)
    }

    private fun refreshStatus(updateDetails: Boolean) {
        val status = statusStore.loadFromDisk()
        updateStatusSummary(status)
        updateEndpoints(status)
        updateTraffic()
        if (updateDetails) {
            val scrollY = if (::scrollView.isInitialized) scrollView.scrollY else 0
            statusText.text = status.toText()
            logText.text = logStore.tailAll(90).ifBlank { "No logs yet." }
            if (::scrollView.isInitialized) {
                scrollView.post { scrollView.scrollTo(0, scrollY) }
            }
        }
    }

    private fun checkHealthNow() {
        val config = readConfig()
        val error = config.startValidationError()
        if (error != null) {
            toast(error)
            return
        }
        settingsStore.save(config)
        toast("Checking health")
        Thread({
            val portResult = HealthWatchdogs.checkPorts(config, config.timeoutSeconds * 1000)
            val requestResult = HealthWatchdogs.checkRequest(config)
            val now = TimeUtil.now()
            val lastError = when {
                !portResult.first -> portResult.second
                !requestResult.ok -> requestResult.error
                else -> ""
            }
            statusStore.update {
                it.copy(
                    bindAddress = config.bindAddress,
                    httpPort = config.httpPort,
                    socksPort = config.socksPort,
                    enableHttp = config.enableHttp,
                    enableSocks = config.enableSocks,
                    startOnBoot = config.startOnBoot,
                    portOk = portResult.first,
                    lastPortCheckAt = now,
                    requestOk = requestResult.ok,
                    lastRequestCheckAt = now,
                    lastHttpStatus = requestResult.status,
                    consecutiveFailures = if (requestResult.ok) 0 else it.consecutiveFailures,
                    lastError = lastError,
                )
            }
            logStore.append(
                "app",
                "manual health check port=${portResult.first} request=${requestResult.ok} status=${requestResult.status} error=$lastError",
            )
            runOnUiThread {
                refreshStatus(updateDetails = true)
                toast(if (portResult.first && requestResult.ok) "Health ok" else "Health failed")
            }
        }, "manual-health-check").start()
    }

    private fun updateStatusSummary(status: RuntimeStatus) {
        val healthOk = status.serviceRunning && status.proxyRunning && status.portOk && status.requestOk
        val active = status.serviceRunning || status.desiredRunning
        statusPill.text = when {
            healthOk -> "Running"
            active -> "Recovering"
            else -> "Stopped"
        }
        statusPill.setTextColor(when {
            healthOk -> COLOR_GOOD
            active -> COLOR_WARN
            else -> COLOR_MUTED_TEXT
        })
        statusPill.background = rounded(when {
            healthOk -> COLOR_GOOD_BG
            active -> COLOR_WARN_BG
            else -> COLOR_MUTED_BG
        }, dp(18))

        val listeners = mutableListOf<String>()
        if (status.enableHttp) listeners.add("HTTP :${status.httpPort}")
        if (status.enableSocks) listeners.add("SOCKS5 :${status.socksPort}")
        val listenerText = if (listeners.isEmpty()) "No listener enabled" else listeners.joinToString("  ")
        val health = "Port ${okFail(status.portOk)}  Health ${if (status.requestOk) status.lastHttpStatus else "fail"}"
        val lastChange = status.lastStartAt.ifBlank { status.lastStopAt.ifBlank { "No start recorded" } }
        statusSummary.text = "$listenerText\n$health  Restarts ${status.restartCount}\nLast ${compactTimestamp(lastChange)}"
    }

    private fun updateEndpoints(status: RuntimeStatus) {
        val addresses = endpointAddresses(status.bindAddress)
        val lines = mutableListOf<String>()
        addresses.forEach { address ->
            if (status.enableHttp) lines.add("HTTP   $address:${status.httpPort}")
            if (status.enableSocks) lines.add("SOCKS5 $address:${status.socksPort}")
        }
        val displayLines = when {
            lines.isEmpty() -> listOf("No IPv4 endpoint available")
            lines.size <= ENDPOINT_LINES -> lines
            else -> lines.take(ENDPOINT_LINES - 1) + "+${lines.size - ENDPOINT_LINES + 1} more"
        }
        endpointText.text = displayLines.joinToString("\n")
    }

    private fun endpointAddresses(bindAddress: String): List<String> {
        val bind = bindAddress.trim()
        if (bind.isNotBlank() && bind != "0.0.0.0" && bind != "::") {
            return listOf(bind)
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface ->
                    networkInterface.inetAddresses.asSequence()
                        .filterIsInstance<Inet4Address>()
                        .filterNot { it.isLoopbackAddress }
                        .map { it.hostAddress ?: "" }
                }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedWith(compareBy<String> { endpointPriority(it) }.thenBy { it })
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun endpointPriority(address: String): Int {
        return when {
            address.startsWith("192.168.") -> 0
            address.startsWith("10.") -> 1
            address.substringBefore('.').toIntOrNull() == 172 -> 2
            address.startsWith("100.64.") -> 3
            else -> 4
        }
    }

    private fun updateTraffic() {
        val rxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
        val txBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid())
        if (rxBytes == TrafficStats.UNSUPPORTED.toLong() || txBytes == TrafficStats.UNSUPPORTED.toLong()) {
            sentTrafficText.text = "Sent\nCurrent: --\nMax: --\nTotal: --"
            receivedTrafficText.text = "Received\nCurrent: --\nMax: --\nTotal: --"
            return
        }

        val sample = TrafficSample(System.currentTimeMillis(), rxBytes, txBytes)
        val first = firstTrafficSample ?: sample.also { firstTrafficSample = it }
        val previous = previousTrafficSample
        val elapsedMs = previous?.let { sample.timestampMs - it.timestampMs } ?: 0L
        val txBps = if (previous != null && elapsedMs > 0) {
            (maxOf(0L, sample.txBytes - previous.txBytes) * 1000L) / elapsedMs
        } else {
            0L
        }
        val rxBps = if (previous != null && elapsedMs > 0) {
            (maxOf(0L, sample.rxBytes - previous.rxBytes) * 1000L) / elapsedMs
        } else {
            0L
        }
        maxTxBps = maxOf(maxTxBps, txBps)
        maxRxBps = maxOf(maxRxBps, rxBps)
        previousTrafficSample = sample

        sentTrafficText.text = buildTrafficText("Sent", txBps, maxTxBps, maxOf(0L, sample.txBytes - first.txBytes))
        receivedTrafficText.text = buildTrafficText("Received", rxBps, maxRxBps, maxOf(0L, sample.rxBytes - first.rxBytes))
    }

    private fun buildTrafficText(label: String, currentBps: Long, maxBps: Long, totalBytes: Long): String {
        return "$label\nCurrent: ${formatBitRate(currentBps)}\nMax: ${formatBitRate(maxBps)}\nTotal: ${formatBytes(totalBytes)}"
    }

    private fun compactTimestamp(value: String): String {
        if (value == "No start recorded") return value
        return value.replace('T', ' ').substringBefore("+").take(16).ifBlank { value }
    }

    private fun formatBitRate(bytesPerSecond: Long): String {
        val bitsPerSecond = bytesPerSecond * 8.0
        val units = arrayOf("bps", "Kbps", "Mbps", "Gbps")
        var value = bitsPerSecond
        var index = 0
        while (value >= 1000.0 && index < units.lastIndex) {
            value /= 1000.0
            index += 1
        }
        return "${formatNumber(value)} ${units[index]}"
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index += 1
        }
        return "${formatNumber(value)} ${units[index]}"
    }

    private fun formatNumber(value: Double): String {
        return if (value >= 100 || value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format("%.1f", value)
        }
    }

    private fun updateAuthFields() {
        val enabled = authEnabled.isChecked
        username.isEnabled = enabled
        password.isEnabled = enabled
        username.alpha = if (enabled) 1f else 0.55f
        password.alpha = if (enabled) 1f else 0.55f
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }.onFailure {
            toast("Unable to open battery settings: ${it.message}")
        }
    }

    private fun copyStatus() {
        val text = statusStore.loadFromDisk().toText() + "\n" + logStore.tailAll(120)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Pixel Proxy Gateway status", text))
        toast("Status copied")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTextColor(COLOR_TEXT)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(text: String, topPaddingDp: Int = 18): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(COLOR_TEXT)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(topPaddingDp), 0, dp(8))
    }

    private fun bodyText(monospace: Boolean = true, selectable: Boolean = false): TextView = TextView(this).apply {
        textSize = 12f
        setTextColor(COLOR_TEXT_SECONDARY)
        typeface = if (monospace) Typeface.MONOSPACE else Typeface.DEFAULT
        setTextIsSelectable(selectable)
        setLineSpacing(0f, 1.08f)
    }

    private fun metricText(): TextView = bodyText(monospace = false).apply {
        textSize = 13f
        setTextColor(COLOR_TEXT)
        setTextIsSelectable(false)
        lockLines(TRAFFIC_METRIC_LINES)
    }

    private fun field(hint: String, input: Int): EditText = EditText(this).apply {
        this.hint = hint
        inputType = input
        setSingleLine(true)
        minHeight = dp(56)
        gravity = Gravity.CENTER_VERTICAL
        textSize = 14f
        setTextColor(COLOR_TEXT)
        setHintTextColor(COLOR_HINT)
        includeFontPadding = true
        setPadding(dp(10), dp(4), dp(10), dp(8))
    }

    private fun check(text: String): CheckBox = CheckBox(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(COLOR_TEXT)
    }

    private fun button(text: String, action: View.OnClickListener): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        setAllCaps(false)
        minimumHeight = dp(42)
        background = rounded(COLOR_PRIMARY, dp(8))
        setOnClickListener(action)
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(8)
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
    }

    private fun card(bottomMarginDp: Int = 12): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Color.WHITE, dp(8))
        elevation = dp(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(bottomMarginDp)
        }
    }

    private fun TextView.lockLines(count: Int) {
        minLines = count
        maxLines = count
        setLines(count)
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = true
    }

    private fun applySystemBarPadding(view: View) {
        val horizontal = dp(16)
        val vertical = dp(16)
        view.setPadding(horizontal, vertical, horizontal, vertical)
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                target.setPadding(horizontal, vertical + bars.top, horizontal, vertical + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                target.setPadding(
                    horizontal,
                    vertical + insets.systemWindowInsetTop,
                    horizontal,
                    vertical + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        view.requestApplyInsets()
    }

    private fun pill(text: String, backgroundColor: Int, textColor: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(backgroundColor, dp(18))
        gravity = Gravity.CENTER
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun okFail(value: Boolean): String = if (value) "ok" else "fail"

    companion object {
        private const val STATUS_SUMMARY_LINES = 3
        private const val ENDPOINT_LINES = 4
        private const val TRAFFIC_METRIC_LINES = 4
        private const val COLOR_SCREEN = 0xFFF6F7F9.toInt()
        private const val COLOR_TEXT = 0xFF172033.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF526070.toInt()
        private const val COLOR_HINT = 0xFF7A8491.toInt()
        private const val COLOR_PRIMARY = 0xFF1769AA.toInt()
        private const val COLOR_GOOD = 0xFF126E43.toInt()
        private const val COLOR_GOOD_BG = 0xFFDDF7E9.toInt()
        private const val COLOR_WARN = 0xFF8A4B00.toInt()
        private const val COLOR_WARN_BG = 0xFFFFE8C2.toInt()
        private const val COLOR_MUTED_TEXT = 0xFF5D6673.toInt()
        private const val COLOR_MUTED_BG = 0xFFE7EBF0.toInt()
    }

    private data class TrafficSample(
        val timestampMs: Long,
        val rxBytes: Long,
        val txBytes: Long,
    )
}

private fun EditText.intValue(default: Int): Int = text.toString().toIntOrNull() ?: default
private fun EditText.longValue(default: Long): Long = text.toString().toLongOrNull() ?: default
