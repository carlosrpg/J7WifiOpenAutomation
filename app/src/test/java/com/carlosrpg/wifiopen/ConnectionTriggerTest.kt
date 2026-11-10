package com.carlosrpg.wifiopen

import com.carlosrpg.wifiopen.ConnectionTrigger.Decision.RUN
import com.carlosrpg.wifiopen.ConnectionTrigger.Decision.SKIP_COOLDOWN
import com.carlosrpg.wifiopen.ConnectionTrigger.Decision.SKIP_WIFI_ON
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTriggerTest {
    @Test
    fun skipsWhenWifiAlreadyOn() {
        val t = ConnectionTrigger(cooldownMs = 1_000)
        assertEquals(SKIP_WIFI_ON, t.onProjectionWindow(0L, wifiOn = true))
        // A WiFi-on skip must not start the cooldown.
        assertEquals(RUN, t.onProjectionWindow(10L, wifiOn = false))
    }

    @Test
    fun cooldownOnlyStopsRetryLoops() {
        val t = ConnectionTrigger(cooldownMs = 1_000)
        val fired = listOf(
            0L to false,     // first projection window -> run
            300L to false,   // back from EngMode, run failed -> skip, no loop
            700L to true,    // WiFi on -> skip
            1_000L to false, // WiFi turned off again, cooldown over -> run
        ).map { (now, wifi) -> t.onProjectionWindow(now, wifi) }
        assertEquals(listOf(RUN, SKIP_COOLDOWN, SKIP_WIFI_ON, RUN), fired)
    }
}
