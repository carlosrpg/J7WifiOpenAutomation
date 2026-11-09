package com.carlosrpg.wifiopen

/**
 * Decides whether a projection app (Android Auto / CarPlay) coming to the front should start the automation.
 * WiFi already on means there is nothing to do. The short cooldown is only a backstop: if a run fails to turn
 * WiFi on, the projection window returning from EngMode must not start an endless EngMode loop.
 */
class ConnectionTrigger(private val cooldownMs: Long) {
    private var lastRun: Long? = null

    enum class Decision { RUN, SKIP_WIFI_ON, SKIP_COOLDOWN }

    /** @param now a monotonic clock (SystemClock.elapsedRealtime). */
    fun onProjectionWindow(now: Long, wifiOn: Boolean): Decision {
        if (wifiOn) return Decision.SKIP_WIFI_ON
        val last = lastRun
        if (last != null && now - last < cooldownMs) return Decision.SKIP_COOLDOWN
        lastRun = now
        return Decision.RUN
    }
}
