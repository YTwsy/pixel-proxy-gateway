package com.wsy.pixelproxygateway

import android.content.Context
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): ProxyConfig {
        val raw = prefs.getString("config_json", null) ?: return ProxyConfig()
        return runCatching { ProxyConfig.fromJson(JSONObject(raw)) }.getOrElse { ProxyConfig() }
    }

    @Synchronized
    fun save(config: ProxyConfig) {
        val saved = prefs.edit()
            .putString("config_json", config.sanitized().toJson(includePassword = true).toString())
            .commit()
        if (!saved) error("Failed to persist proxy config")
    }
}
