package com.example.adsh.engine

import com.example.adsh.EngineStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStartPolicyTest {
    @Test
    fun `starts an idle engine when enabled`() {
        assertTrue(EngineStartPolicy.shouldAutoStart(EngineStatus.Phase.IDLE, true, false))
        assertTrue(EngineStartPolicy.shouldAutoStart(EngineStatus.Phase.STOPPED, true, false))
    }

    @Test
    fun `never restarts a running engine`() {
        assertFalse(EngineStartPolicy.shouldAutoStart(EngineStatus.Phase.READY, true, false))
        assertFalse(EngineStartPolicy.shouldAutoStart(EngineStatus.Phase.STARTING, true, false))
        assertFalse(EngineStartPolicy.shouldAutoStart(EngineStatus.Phase.FAILED, true, false))
    }

    @Test
    fun `respects the toggle and manual stop`() {
        assertFalse(EngineStartPolicy.shouldAutoStart(EngineStatus.Phase.STOPPED, false, false))
        assertFalse(EngineStartPolicy.shouldAutoStart(EngineStatus.Phase.STOPPED, true, true))
    }
}
