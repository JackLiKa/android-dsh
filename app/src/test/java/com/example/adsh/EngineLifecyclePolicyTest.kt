package com.example.adsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EngineLifecyclePolicyTest {
    @Test
    fun shutdownDoesNotOverwriteStartupTimeout() {
        val failed = EngineStatus(EngineStatus.Phase.FAILED, "启动超时，请检查日志")
        assertSame(failed, engineExited(failed, 143))
    }

    @Test
    fun ordinaryProcessExitRemainsVisible() {
        val running = EngineStatus(EngineStatus.Phase.READY, "dsh Web 已就绪")
        val stopped = engineExited(running, 1)
        assertEquals(EngineStatus.Phase.STOPPED, stopped.phase)
        assertEquals("dsh 已退出（代码 1）", stopped.message)
    }
}
