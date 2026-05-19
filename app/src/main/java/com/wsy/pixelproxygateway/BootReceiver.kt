package com.wsy.pixelproxygateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val config = SettingsStore(context).load()
        if (!config.startOnBoot || !config.autoStart) return
        val serviceIntent = ProxyForegroundService.startIntent(context, Actions.START)
        ServiceLauncher.startForeground(context, serviceIntent, "boot:${intent.action}")
    }
}
