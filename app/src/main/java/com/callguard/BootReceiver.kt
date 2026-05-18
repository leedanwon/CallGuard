package com.callguard

import android.content.*
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = ctx.getSharedPreferences(CallMonitorService.PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(CallMonitorService.KEY_PW, null) != null)
            ContextCompat.startForegroundService(ctx, Intent(ctx, CallMonitorService::class.java))
    }
}
