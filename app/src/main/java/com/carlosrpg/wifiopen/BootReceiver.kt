package com.carlosrpg.wifiopen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * On boot, arms the ADB automation if "Enable ADB on boot" is set. The accessibility service does the
 * real work (it drives EngMode), so this only raises a one-shot flag that the service consumes when it
 * connects. The flag is cleared after one run, and also directly if the service is already connected.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED only: it fires after user 0 is unlocked, so the encrypted password and the
        // accessibility service (both unavailable in direct-boot) are ready.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(WifiOpenService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(WifiOpenService.KEY_ADB_ON_BOOT, false)) return
        Log.i("J7WifiOpen", "Boot completed: arming ADB-on-boot")
        prefs.edit().putBoolean(WifiOpenService.KEY_BOOT_PENDING, true).apply()
        // If the service is already connected, trigger now; otherwise onServiceConnected will consume the flag.
        WifiOpenService.instance?.consumeBootPending()
    }
}
