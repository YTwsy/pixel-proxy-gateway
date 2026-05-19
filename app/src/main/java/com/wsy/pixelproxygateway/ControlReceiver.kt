package com.wsy.pixelproxygateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Actions.START &&
            action != Actions.STOP &&
            action != Actions.RESTART &&
            action != Actions.APPLY_CONFIG
        ) return
        val serviceIntent = Intent(context, ProxyForegroundService::class.java).setAction(action)
        intent?.extras?.let { serviceIntent.putExtras(it) }
        ServiceLauncher.startForeground(context, serviceIntent, "control:${action.substringAfterLast('.')}")
    }
}
