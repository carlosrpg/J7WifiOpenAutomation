package com.carlosrpg.wifiopen

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTriggerTest {
    @Test
    fun runsAtMostOncePerCooldown() {
        val t = ConnectionTrigger(cooldownMs = 1_000)
        val fired = listOf(
            0L,     // first projection window -> run
            300L,   // back from EngMode -> skip
            999L,   // still inside cooldown -> skip
            1_000L, // cooldown over -> run
            1_500L  // skip
        ).map(t::onProjectionWindow)
        assertEquals(listOf(true, false, false, true, false), fired)
    }
}
