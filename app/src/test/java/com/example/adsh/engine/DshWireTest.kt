package com.example.adsh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshWireTest {
    private fun event(seq: Int, type: String, data: Any?): Map<String, Any?> =
        mapOf("seq" to seq, "type" to type, "data" to data)

    private fun userMessage(text: String): Map<String, Any?> = mapOf(
        "role" to "user",
        "content" to listOf(mapOf("type" to "text", "text" to text)),
        "source" to mapOf("kind" to "user"),
    )

    @Test
    fun `user message data is the message itself`() {
        val message = userMessage("生成一个页面")
        val bubbles = DshTranscript.fold(listOf(event(1, "user/message", message)))
        assertEquals(1, bubbles.size)
        assertTrue(bubbles[0].isUser)
        assertEquals("生成一个页面", bubbles[0].text)
    }

    @Test
    fun `assistant reasoning and tool calls survive folding`() {
        val assistant = mapOf(
            "role" to "assistant",
            "content" to listOf(
                mapOf("type" to "reasoning", "text" to "先检查目录"),
                mapOf("type" to "text", "text" to "我来看看。"),
                mapOf(
                    "type" to "tool-call",
                    "id" to "call-1",
                    "name" to "read",
                    "arguments" to "{\"path\":\"a.txt\"}",
                ),
            ),
        )
        val events = listOf(
            event(1, "user/message", userMessage("读取文件")),
            event(2, "assistant/message", mapOf("message" to assistant, "interrupted" to false)),
            event(3, "tool/result", mapOf(
                "message" to mapOf(
                    "toolCallId" to "call-1",
                    "isError" to false,
                    "content" to listOf(mapOf("type" to "text", "text" to "文件内容")),
                ),
            )),
        )
        val bubbles = DshTranscript.fold(events)
        assertEquals(2, bubbles.size)
        val reply = bubbles[1]
        assertFalse(reply.isUser)
        assertEquals("我来看看。", reply.text)
        assertEquals("先检查目录", reply.reasoning)
        assertEquals(1, reply.tools.size)
        assertEquals("read", reply.tools[0].name)
        assertEquals("文件内容", reply.tools[0].output)
        assertFalse(reply.tools[0].running)
        assertFalse(reply.tools[0].isError)
    }

    @Test
    fun `tool call before assistant message attaches to a pending bubble`() {
        val events = listOf(
            event(1, "tool/call", mapOf(
                "callId" to "call-9",
                "name" to "bash",
                "arguments" to "{\"command\":\"ls\"}",
            )),
        )
        val bubbles = DshTranscript.fold(events)
        assertEquals(1, bubbles.size)
        assertEquals("bash", bubbles[0].tools[0].name)
        assertTrue(bubbles[0].tools[0].running)
    }

    @Test
    fun `failed tool result marks the call as error`() {
        val events = listOf(
            event(1, "tool/call", mapOf("callId" to "c", "name" to "bash", "arguments" to "{}")),
            event(2, "tool/result", mapOf(
                "message" to mapOf("toolCallId" to "c", "isError" to true),
                "error" to mapOf("name" to "ToolError", "reason" to "权限不足"),
            )),
        )
        val tool = DshTranscript.fold(events)[0].tools[0]
        assertTrue(tool.isError)
        assertEquals("权限不足", tool.output)
    }

    @Test
    fun `slash command lifecycle folds into one command bubble`() {
        val events = listOf(
            event(1, "command/run", mapOf(
                "commandId" to "cmd-1",
                "name" to "permission",
                "args" to "workspace-write",
                "source" to mapOf("kind" to "user"),
            )),
            event(2, "command/done", mapOf(
                "commandId" to "cmd-1",
                "kind" to "success",
                "text" to "preset workspace-write",
            )),
        )
        val bubbles = DshTranscript.fold(events)
        assertEquals(1, bubbles.size)
        val command = bubbles[0].command!!
        assertEquals("/permission workspace-write", command.line)
        assertEquals("preset workspace-write", command.result)
        assertFalse(command.failed)
    }

    @Test
    fun `failed command without args still reports the error`() {
        val events = listOf(
            event(1, "command/run", mapOf("commandId" to "cmd-2", "name" to "compact")),
            event(2, "command/done", mapOf(
                "commandId" to "cmd-2",
                "kind" to "error",
                "text" to "unknown preset",
            )),
        )
        val command = DshTranscript.fold(events)[0].command!!
        assertEquals("/compact", command.line)
        assertTrue(command.failed)
        assertEquals("unknown preset", command.result)
    }

    @Test
    fun `snapshot parses cursor title model and events`() {
        val item = mapOf(
            "type" to "snapshot",
            "header" to mapOf("id" to "s1", "cwd" to "/tmp"),
            "cursor" to 7,
            "hasMore" to false,
            "records" to listOf(
                mapOf("event" to event(7, "user/message", userMessage("hi"))),
            ),
            "projections" to mapOf(
                "values" to mapOf(
                    "title" to "标题",
                    "modelSelection" to mapOf(
                        "lastUsed" to mapOf("provider" to "deepseek", "model" to "chat"),
                    ),
                ),
            ),
        )
        val snapshot = DshFollowFrame.snapshot(item)!!
        assertEquals(7, snapshot.cursor)
        assertEquals("标题", snapshot.title)
        assertEquals("deepseek", snapshot.provider)
        assertEquals("chat", snapshot.model)
        assertEquals(1, snapshot.events.size)
        assertEquals("s1", snapshot.header?.id)
    }

    @Test
    fun `active attempt accepts aggregated chunks`() {
        val item = mapOf(
            "type" to "snapshot",
            "assistantStream" to mapOf(
                "activeAttempt" to mapOf(
                    "attemptId" to "a1",
                    "startedAfterSeq" to 3,
                    "stream" to listOf(
                        mapOf("type" to "reasoning-chunks", "texts" to listOf("思考")),
                        mapOf("type" to "text-chunks", "texts" to listOf("你", "好")),
                    ),
                ),
            ),
        )
        val streaming = DshFollowFrame.activeAttempt(item)!!
        assertEquals("思考", streaming.reasoning)
        assertEquals("你好", streaming.text)
        assertEquals(3, streaming.startedAfterSeq)
    }

    @Test
    fun `stream frames start append and end`() {
        var state = DshFollowFrame.applyStream(
            null,
            mapOf("type" to "start", "attemptId" to "a1", "startedAfterSeq" to 2),
        )
        assertEquals("", state!!.text)
        state = DshFollowFrame.applyStream(state, mapOf(
            "type" to "chunk",
            "attemptId" to "a1",
            "chunk" to mapOf("type" to "text-delta", "text" to "你"),
        ))
        assertEquals("你", state!!.text)
        state = DshFollowFrame.applyStream(state, mapOf(
            "type" to "chunk",
            "attemptId" to "a1",
            "chunk" to mapOf("type" to "reasoning-delta", "text" to "想"),
        ))
        assertEquals("你", state!!.text)
        assertEquals("想", state!!.reasoning)
        assertNull(DshFollowFrame.applyStream(state, mapOf("type" to "end", "attemptId" to "a1")))
    }

    @Test
    fun `model catalog flattens groups and failures`() {
        val catalog = DshModelCatalog.from(
            DshJson.parse(
                """
                {
                  "default": {"provider": "deepseek", "model": "chat"},
                  "groups": [
                    {
                      "id": "deepseek",
                      "name": "DeepSeek",
                      "models": [
                        {
                          "id": "chat",
                          "name": "Chat",
                          "reasoning": {
                            "efforts": [{"id": "high", "name": "高"}],
                            "defaultEffort": "high"
                          }
                        }
                      ]
                    }
                  ],
                  "failures": [{"id": "x", "name": "X", "message": "离线"}]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(1, catalog.options.size)
        assertEquals("deepseek", catalog.defaultProvider)
        assertEquals("chat", catalog.defaultOption()?.model)
        assertEquals("high", catalog.options[0].defaultEffort)
        assertEquals(1, catalog.failures.size)
    }

    @Test
    fun `host events parse approvals questions and status emits`() {
        val approval = DshHostEvent.from(
            mapOf(
                "type" to "waterfall",
                "event" to "approval/request",
                "eventId" to "e1",
                "request" to mapOf(
                    "toolName" to "bash",
                    "reason" to "需要执行命令",
                    "callId" to "c1",
                ),
            ),
        )
        assertTrue(approval is DshHostEvent.ApprovalRequested)
        assertEquals("bash", (approval as DshHostEvent.ApprovalRequested).approval.toolName)

        val questions = DshHostEvent.from(
            mapOf(
                "type" to "waterfall",
                "event" to "user-questions/request",
                "eventId" to "e2",
                "request" to mapOf(
                    "questions" to listOf(
                        mapOf(
                            "id" to "q1",
                            "question" to "继续吗？",
                            "options" to listOf(mapOf("label" to "继续")),
                            "multiSelect" to false,
                        ),
                    ),
                    "wait" to mapOf("callId" to "c2", "timed" to false),
                ),
            ),
        )
        assertTrue(questions is DshHostEvent.QuestionRequested)
        assertEquals(1, (questions as DshHostEvent.QuestionRequested).request.questions.size)

        val status = DshHostEvent.from(
            mapOf("type" to "emit", "event" to "api-session/status", "args" to listOf("s1", true)),
        )
        assertTrue(status is DshHostEvent.SessionStatus)
        assertTrue((status as DshHostEvent.SessionStatus).running)

        assertEquals(
            "e3",
            (DshHostEvent.from(mapOf("type" to "cancel", "eventId" to "e3")) as DshHostEvent.RequestCancelled)
                .eventId,
        )
    }
}
