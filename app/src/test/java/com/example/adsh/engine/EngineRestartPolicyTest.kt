package com.example.adsh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRestartPolicyTest {
    @Test
    fun `backoff grows one two four seconds`() {
        val policy = EngineRestartPolicy()
        assertEquals(1_000L, policy.onExit(0, intentional = false).delayMs)
        assertEquals(2_000L, policy.onExit(1_000, intentional = false).delayMs)
        val third = policy.onExit(3_000, intentional = false)
        assertEquals(4_000L, third.delayMs)
        assertFalse(third.restart)
        assertTrue(third.circuitOpen)
    }

    @Test
    fun `intentional stop resets the failure window`() {
        val policy = EngineRestartPolicy()
        policy.onExit(0, intentional = false)
        policy.onExit(1_000, intentional = false)
        policy.onExit(2_000, intentional = true)
        val again = policy.onExit(3_000, intentional = false)
        assertTrue(again.restart)
        assertEquals(1_000L, again.delayMs)
    }

    @Test
    fun `failures older than the window are pruned`() {
        val policy = EngineRestartPolicy()
        policy.onExit(0, intentional = false)
        policy.onExit(1_000, intentional = false)
        val later = policy.onExit(11 * 60_000L, intentional = false)
        assertTrue(later.restart)
        assertEquals(1, later.failures)
        assertEquals(1_000L, later.delayMs)
    }

    @Test
    fun `crash hints name common native exits`() {
        assertTrue(EngineCrash.hintFor(139).contains("SIGSEGV"))
        assertTrue(EngineCrash.hintFor(137).contains("SIGKILL"))
        assertTrue(EngineCrash.hintFor(134).contains("SIGABRT"))
        assertTrue(EngineCrash.format(139, 0, 2_000, listOf("boom")).contains("boom"))
    }
}
