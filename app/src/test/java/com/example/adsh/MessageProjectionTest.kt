package com.example.adsh

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageProjectionTest {
    @Test
    fun modelHistoryUsesOnlyCommittedVisibleMessages() {
        val events = listOf(
            event(0, "turn/start", ""),
            event(1, "user/message", "任务"),
            event(2, "assistant/attempt", "失败的片段"),
            event(3, "assistant/message", "答复"),
            event(4, "assistant/message", ""),
            event(5, "tool/call", "{}"),
        )
        assertEquals(
            listOf(ModelMessage("user", "任务"), ModelMessage("assistant", "答复")),
            deriveMessages(events),
        )
    }

    private fun event(seq: Long, type: String, data: String) =
        SessionEventRow("session-id", seq, type, 1L, data)
}
