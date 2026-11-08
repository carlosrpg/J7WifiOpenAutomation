package com.carlosrpg.wifiopen

/**
 * Decides whether a projection app (Android Auto / CarPlay) coming to the front should start the automation.
 * The projection apps expose no "connected" signal to a normal app, so this rate-limits instead: at most one
 * run per cooldown. That covers returning from EngMode and switching between apps during a drive.
 */
class ConnectionTrigger(private val cooldownMs: Long) {
    private var lastRun: Long? = null

    /** @param now a monotonic clock (SystemClock.elapsedRealtime). @return true when the automation should run. */
    fun onProjectionWindow(now: Long): Boolean {
        val last = lastRun
        if (last != null && now - last < cooldownMs) return false
        lastRun = now
        return true
    }
}
