package com.lite404.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class VpnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val start = Connection.autoConnect(context.applicationContext, boot = true)
                withTimeoutOrNull(8_000) { start?.join() }
            } finally {
                pending.finish()
            }
        }
    }
}
