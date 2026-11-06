package com.example.adsh.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineIdlePolicyTest {
    @Test
    fun `stops only an idle background engine`() {
        assertTrue(EngineIdlePolicy.shouldStop(true, false, false, 300_000, 300_000))
        assertFalse(EngineIdlePolicy.shouldStop(false, false, false, 300_000, 300_000))
        assertFalse(EngineIdlePolicy.shouldStop(true, true, false, 300_000, 300_000))
        assertFalse(EngineIdlePolicy.shouldStop(true, false, true, 300_000, 300_000))
        assertFalse(EngineIdlePolicy.shouldStop(true, false, false, 299_999, 300_000))
    }
}
