package com.example.adsh.engine

/** Session row from `session/list` (subagent sessions are filtered out by callers). */
data class DshSessionSummary(
    val id: String,
    val title: String,
    val cwd: String?,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val origin: String?,
    val permissionPreset: String?,
    val provider: String?,
    val model: String?,
) {
    companion object {
        fun from(json: Any?): DshSessionSummary? {
            val map = json.asDshMapOrEmpty()
            val id = map["sessionId"].dshString()?.takeIf { it.isNotBlank() } ?: return null
            val projections = map["projections"].asDshMapOrEmpty()["values"].asDshMapOrEmpty()
            val model = projections["modelSelection"].asDshMapOrEmpty()
                .let { it["next"].asDshMap() ?: it["lastUsed"].asDshMap() }
            return DshSessionSummary(
                id = id,
                title = projections["title"].dshString().orEmpty(),
                cwd = map["cwd"].dshString(),
                updatedAt = map["updatedAt"].dshLong() ?: 0L,
                running = map["running"] == true,
                blank = map["blank"] == true ||
                    projections["sessionListMetadata"].asDshMapOrEmpty()["blank"] == true,
                origin = map["origin"].dshString(),
                permissionPreset = projections["permissions"].asDshMapOrEmpty()["currentValue"].dshString(),
                provider = model?.get("provider").dshString(),
                model = model?.get("model").dshString(),
            )
        }
    }
}

data class DshModelOption(
    val provider: String,
    val providerName: String,
    val model: String,
    val name: String,
    val efforts: List<DshReasoningEffort> = emptyList(),
    val defaultEffort: String? = null,
) {
    val label: String get() = if (name.isBlank() || name == model) model else name
}

data class DshReasoningEffort(val id: String, val name: String)

data class DshModelCatalog(
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val options: List<DshModelOption> = emptyList(),
    val failures: List<String> = emptyList(),
) {
    fun defaultOption(): DshModelOption? = options.firstOrNull {
        it.provider == defaultProvider && it.model == defaultModel
    } ?: options.firstOrNull()

    companion object {
        fun from(value: Any?): DshModelCatalog {
            val map = value.asDshMapOrEmpty()
            val options = ArrayList<DshModelOption>()
            for (group in map["groups"].asDshList().orEmpty()) {
                val groupMap = group.asDshMapOrEmpty()
                val provider = groupMap["id"].dshString().orEmpty()
                val providerName = groupMap["name"].dshString().orEmpty().ifBlank { provider }
                for (model in groupMap["models"].asDshList().orEmpty()) {
                    val modelMap = model.asDshMapOrEmpty()
                    val id = modelMap["id"].dshString().orEmpty()
                    if (id.isBlank()) continue
                    val reasoning = modelMap["reasoning"].asDshMapOrEmpty()
                    options += DshModelOption(
                        provider = provider,
                        providerName = providerName,
                        model = id,
                        name = modelMap["name"].dshString().orEmpty().ifBlank { id },
                        efforts = reasoning["efforts"].asDshList().orEmpty().mapNotNull { raw ->
                            val effort = raw.asDshMapOrEmpty()
                            val effortId = effort["id"].dshString() ?: return@mapNotNull null
                            DshReasoningEffort(
                                id = effortId,
                                name = effort["name"].dshString().orEmpty().ifBlank { effortId },
                            )
                        },
                        defaultEffort = reasoning["defaultEffort"].dshString(),
                    )
                }
            }
            val default = map["default"].asDshMapOrEmpty()
            return DshModelCatalog(
                defaultProvider = default["provider"].dshString(),
                defaultModel = default["model"].dshString(),
                options = options,
                failures = map["failures"].asDshList().orEmpty().mapNotNull { raw ->
                    val failure = raw.asDshMapOrEmpty()
                    val message = failure["message"].dshString().orEmpty()
                    if (message.isBlank()) null
                    else "${failure["name"].dshString() ?: failure["id"].dshString().orEmpty()}：$message"
                },
            )
        }
    }
}

data class DshToolCall(
    val callId: String,
    val name: String,
    val arguments: String,
    val output: String? = null,
    val isError: Boolean = false,
    val running: Boolean = false,
)

/** One slash-command run recorded by `command/run` + `command/done`. */
data class DshCommandInfo(
    val commandId: String,
    val line: String,
    val result: String? = null,
    val failed: Boolean = false,
)

data class DshChatMessage(
    val key: String,
    val isUser: Boolean,
    val text: String,
    val reasoning: String = "",
    val tools: List<DshToolCall> = emptyList(),
    val interrupted: Boolean = false,
    val isError: Boolean = false,
    val command: DshCommandInfo? = null,
)

/** Approval prompt from the `approval/request` waterfall. */
data class DshApproval(
    val eventId: String,
    val toolName: String,
    val reason: String?,
    val callId: String?,
)

data class DshQuestionOption(val label: String, val description: String?)

data class DshQuestion(
    val id: String,
    val question: String,
    val detail: String?,
    val header: String?,
    val options: List<DshQuestionOption>,
    val multiSelect: Boolean,
)

/** Question prompt from the `user-questions/request` waterfall. */
data class DshQuestionRequest(
    val eventId: String,
    val questions: List<DshQuestion>,
    val timed: Boolean,
)

data class DshSessionHeader(
    val id: String,
    val cwd: String?,
    val origin: String?,
    val agentPreset: String?,
) {
    companion object {
        fun from(value: Any?): DshSessionHeader? {
            val map = value.asDshMap() ?: return null
            val id = map["id"].dshString() ?: return null
            return DshSessionHeader(
                id = id,
                cwd = map["cwd"].dshString(),
                origin = map["origin"].dshString(),
                agentPreset = map["agentPreset"].dshString(),
            )
        }
    }
}

data class DshSnapshot(
    val header: DshSessionHeader?,
    val cursor: Int,
    val events: List<Map<String, Any?>>,
    val hasMore: Boolean,
    val title: String?,
    val provider: String?,
    val model: String?,
    val permissionPreset: String?,
)

data class DshStreaming(
    val attemptId: String,
    val text: String,
    val reasoning: String,
    val startedAfterSeq: Int,
) {
    val isEmpty: Boolean get() = text.isEmpty() && reasoning.isEmpty()
}

/** Folds durable session events into chat bubbles. Pure; unit tested. */
object DshTranscript {
    fun fold(events: List<Map<String, Any?>>): List<DshChatMessage> {
        val bubbles = ArrayList<MutableBubble>()
        val toolOwners = HashMap<String, Pair<Int, Int>>()

        fun appendUser(key: String, text: String) {
            bubbles += MutableBubble(key, isUser = true, text = text)
        }

        fun appendAssistant(key: String, text: String, reasoning: String, tools: List<DshToolCall>, interrupted: Boolean) {
            bubbles += MutableBubble(
                key = key,
                isUser = false,
                text = text,
                reasoning = reasoning,
                tools = tools.toMutableList(),
                interrupted = interrupted,
            )
            tools.forEachIndexed { toolIndex, tool ->
                if (tool.callId.isNotBlank()) toolOwners[tool.callId] = bubbles.lastIndex to toolIndex
            }
        }

        fun ensureToolOwner(callId: String): Pair<Int, Int>? {
            toolOwners[callId]?.let { return it }
            if (callId.isBlank()) return null
            val bubble = bubbles.lastOrNull { !it.isUser }
                ?: MutableBubble("tool-$callId", isUser = false, text = "").also { bubbles += it }
            bubble.tools += DshToolCall(callId, "", "", running = true)
            val owner = (bubbles.lastIndex) to (bubble.tools.lastIndex)
            toolOwners[callId] = owner
            return owner
        }

        for (event in events) {
            val type = event["type"].dshString().orEmpty()
            val data = event["data"].asDshMapOrEmpty()
            val seq = event["seq"].dshLong() ?: 0L
            when (type) {
                "user/message" -> {
                    val message = data["message"].asDshMap() ?: data
                    val source = message["source"].asDshMapOrEmpty()
                    if (source["kind"].dshString() != "user") continue
                    val text = joinText(message)
                    if (text.isBlank()) continue
                    appendUser("e$seq", text)
                }
                "assistant/message" -> {
                    val message = data["message"].asDshMapOrEmpty()
                    val parts = message["content"].asDshList().orEmpty()
                    val text = StringBuilder()
                    val reasoning = StringBuilder()
                    val tools = ArrayList<DshToolCall>()
                    for (part in parts) {
                        val block = part.asDshMapOrEmpty()
                        when (block["type"].dshString()) {
                            "text" -> text.append(block["text"].dshString().orEmpty())
                            "reasoning" -> reasoning.append(block["text"].dshString().orEmpty())
                            "tool-call" -> {
                                val id = block["id"].dshString().orEmpty()
                                tools += DshToolCall(
                                    callId = id,
                                    name = block["name"].dshString().orEmpty(),
                                    arguments = block["arguments"].dshString().orEmpty(),
                                    running = false,
                                )
                            }
                        }
                    }
                    if (text.isBlank() && reasoning.isBlank() && tools.isEmpty()) continue
                    appendAssistant(
                        key = "e$seq",
                        text = text.toString(),
                        reasoning = reasoning.toString(),
                        tools = tools,
                        interrupted = data["interrupted"] == true,
                    )
                }
                "tool/call" -> {
                    val callId = data["callId"].dshString().orEmpty()
                    if (callId.isBlank()) continue
                    val owner = toolOwners[callId]
                    if (owner != null) {
                        val tool = bubbles[owner.first].tools[owner.second]
                        bubbles[owner.first].tools[owner.second] = tool.copy(
                            name = data["name"].dshString().orEmpty().ifBlank { tool.name },
                            arguments = data["arguments"].dshString().orEmpty().ifBlank { tool.arguments },
                            running = tool.output == null,
                        )
                    } else {
                        val target = ensureToolOwner(callId) ?: continue
                        val tool = bubbles[target.first].tools[target.second]
                        bubbles[target.first].tools[target.second] = tool.copy(
                            name = data["name"].dshString().orEmpty(),
                            arguments = data["arguments"].dshString().orEmpty(),
                            running = true,
                        )
                    }
                }
                "tool/result" -> {
                    val message = data["message"].asDshMapOrEmpty()
                    val callId = message["toolCallId"].dshString()
                        ?: data["callId"].dshString().orEmpty()
                    val owner = toolOwners[callId] ?: ensureToolOwner(callId) ?: continue
                    val error = data["error"].asDshMapOrEmpty()
                    val failed = error.isNotEmpty() || message["isError"] == true
                    val output = error["reason"].dshString()?.takeIf { it.isNotBlank() }
                        ?: joinText(message).ifBlank { if (failed) "工具执行失败" else "" }
                    val tool = bubbles[owner.first].tools[owner.second]
                    bubbles[owner.first].tools[owner.second] = tool.copy(
                        output = output,
                        isError = failed,
                        running = false,
                    )
                }
                "command/run" -> {
                    val commandId = data["commandId"].dshString().orEmpty()
                    val name = data["name"].dshString().orEmpty()
                    if (commandId.isBlank() || name.isBlank()) continue
                    val args = data["args"].dshString().orEmpty().trim()
                    val line = if (args.isEmpty()) "/$name" else "/$name $args"
                    bubbles += MutableBubble(
                        key = "e$seq",
                        isUser = false,
                        text = "",
                        command = DshCommandInfo(commandId = commandId, line = line),
                    )
                }
                "command/done" -> {
                    val commandId = data["commandId"].dshString().orEmpty()
                    val bubble = bubbles.lastOrNull { it.command?.commandId == commandId } ?: continue
                    val info = bubble.command ?: continue
                    bubble.command = info.copy(
                        result = data["text"].dshString()?.takeIf { it.isNotBlank() },
                        failed = data["kind"].dshString() == "error",
                    )
                }
            }
        }
        return bubbles.map { it.freeze() }
    }

    private fun joinText(message: Map<String, Any?>): String = buildString {
        for (part in message["content"].asDshList().orEmpty()) {
            val block = part.asDshMapOrEmpty()
            if (block["type"].dshString() == "text") append(block["text"].dshString().orEmpty())
        }
    }

    private class MutableBubble(
        val key: String,
        val isUser: Boolean,
        val text: String,
        val reasoning: String = "",
        val tools: MutableList<DshToolCall> = ArrayList(),
        val interrupted: Boolean = false,
        var command: DshCommandInfo? = null,
    ) {
        fun freeze() = DshChatMessage(
            key = key,
            isUser = isUser,
            text = text,
            reasoning = reasoning,
            tools = tools.toList(),
            interrupted = interrupted,
            command = command,
        )
    }
}

/** Parses one `session/follow` item. Returns null for frames chat does not render. */
object DshFollowFrame {
    fun snapshot(item: Map<String, Any?>): DshSnapshot? {
        if (item["type"].dshString() != "snapshot") return null
        val events = item["records"].asDshList().orEmpty().mapNotNull { record ->
            record.asDshMapOrEmpty()["event"].asDshMap()
        }
        val values = item["projections"].asDshMapOrEmpty()["values"].asDshMapOrEmpty()
        val model = values["modelSelection"].asDshMapOrEmpty()
            .let { it["next"].asDshMap() ?: it["lastUsed"].asDshMap() }
        return DshSnapshot(
            header = DshSessionHeader.from(item["header"]),
            cursor = item["cursor"].dshInt() ?: -1,
            events = events,
            hasMore = item["hasMore"] == true,
            title = values["title"].dshString(),
            provider = model?.get("provider").dshString(),
            model = model?.get("model").dshString(),
            permissionPreset = values["permissions"].asDshMapOrEmpty()["currentValue"].dshString(),
        )
    }

    /** Live attempt carried by a snapshot, with every chunk already streamed. */
    fun activeAttempt(item: Map<String, Any?>): DshStreaming? {
        val attempt = item["assistantStream"].asDshMapOrEmpty()["activeAttempt"].asDshMap() ?: return null
        val attemptId = attempt["attemptId"].dshString() ?: return null
        val text = StringBuilder()
        val reasoning = StringBuilder()
        for (raw in attempt["stream"].asDshList().orEmpty()) {
            val item = raw.asDshMapOrEmpty()
            val chunk = item["chunk"].asDshMap()
            if (chunk != null) {
                appendChunk(chunk, text, reasoning)
                continue
            }
            when (item["type"].dshString()) {
                "text-chunks" -> item["texts"].asDshList().orEmpty().forEach {
                    text.append(it.dshString().orEmpty())
                }
                "reasoning-chunks" -> item["texts"].asDshList().orEmpty().forEach {
                    reasoning.append(it.dshString().orEmpty())
                }
            }
        }
        return DshStreaming(
            attemptId = attemptId,
            text = text.toString(),
            reasoning = reasoning.toString(),
            startedAfterSeq = attempt["startedAfterSeq"].dshInt() ?: -1,
        )
    }

    /** Applies one `assistant-stream` frame; returns the next live attempt state. */
    fun applyStream(current: DshStreaming?, frame: Map<String, Any?>): DshStreaming? {
        val type = frame["type"].dshString().orEmpty()
        val attemptId = frame["attemptId"].dshString().orEmpty()
        return when (type) {
            "start" -> DshStreaming(
                attemptId = attemptId,
                text = "",
                reasoning = "",
                startedAfterSeq = frame["startedAfterSeq"].dshInt() ?: -1,
            )
            "chunk" -> {
                val base = if (current?.attemptId == attemptId) current else DshStreaming(attemptId, "", "", -1)
                val text = StringBuilder(base.text)
                val reasoning = StringBuilder(base.reasoning)
                appendChunk(frame["chunk"].asDshMapOrEmpty(), text, reasoning)
                base.copy(text = text.toString(), reasoning = reasoning.toString())
            }
            "end" -> null
            else -> current
        }
    }

    private fun appendChunk(chunk: Map<String, Any?>, text: StringBuilder, reasoning: StringBuilder) {
        when (chunk["type"].dshString()) {
            "text-delta" -> text.append(chunk["text"].dshString().orEmpty())
            "reasoning-delta" -> reasoning.append(chunk["text"].dshString().orEmpty())
        }
    }
}

/** Parses `$events` items into the few Host events the chat needs. */
sealed class DshHostEvent {
    data class Ready(val clientId: String, val home: String) : DshHostEvent()
    data class ApprovalRequested(val approval: DshApproval) : DshHostEvent()
    data class QuestionRequested(val request: DshQuestionRequest) : DshHostEvent()
    data class RequestCancelled(val eventId: String) : DshHostEvent()
    data class SessionStatus(val sessionId: String, val running: Boolean) : DshHostEvent()
    data class SessionError(val sessionId: String, val message: String) : DshHostEvent()
    data class CredentialUpdated(val ref: String) : DshHostEvent()

    companion object {
        fun from(value: Any?): DshHostEvent? {
            val frame = value.asDshMap() ?: return null
            return when (frame["type"].dshString()) {
                "ready" -> Ready(
                    clientId = frame["clientId"].dshString().orEmpty(),
                    home = frame["host"].asDshMapOrEmpty()["home"].dshString().orEmpty(),
                )
                "waterfall" -> when (frame["event"].dshString()) {
                    "approval/request" -> ApprovalRequested(parseApproval(frame))
                    "user-questions/request" -> QuestionRequested(parseQuestions(frame))
                    else -> null
                }
                "cancel" -> RequestCancelled(frame["eventId"].dshString().orEmpty())
                "emit" -> {
                    val args = frame["args"].asDshList().orEmpty()
                    when (frame["event"].dshString()) {
                        "api-session/status" -> SessionStatus(
                            sessionId = args.getOrNull(0).dshString().orEmpty(),
                            running = args.getOrNull(1) == true,
                        )
                        "api-session/error" -> SessionError(
                            sessionId = args.getOrNull(0).dshString().orEmpty(),
                            message = args.getOrNull(1).dshString().orEmpty(),
                        )
                        "credentials/reference-updated" -> CredentialUpdated(
                            args.getOrNull(0).dshString().orEmpty(),
                        )
                        else -> null
                    }
                }
                else -> null
            }
        }

        private fun parseApproval(frame: Map<String, Any?>): DshApproval {
            val request = frame["request"].asDshMapOrEmpty()
            val display = request["displayReason"].asDshMapOrEmpty()
            return DshApproval(
                eventId = frame["eventId"].dshString().orEmpty(),
                toolName = request["toolName"].dshString()?.takeIf { it.isNotBlank() } ?: "tool",
                reason = request["reason"].dshString()
                    ?: display["zh"].dshString()
                    ?: display["en"].dshString(),
                callId = request["callId"].dshString(),
            )
        }

        fun parseQuestions(frame: Map<String, Any?>): DshQuestionRequest {
            val request = frame["request"].asDshMapOrEmpty()
            val questions = request["questions"].asDshList().orEmpty().mapNotNull { raw ->
                val map = raw.asDshMapOrEmpty()
                val question = map["question"].dshString() ?: return@mapNotNull null
                DshQuestion(
                    id = map["id"].dshString().orEmpty(),
                    question = question,
                    detail = map["detail"].dshString(),
                    header = map["header"].dshString(),
                    options = map["options"].asDshList().orEmpty().mapNotNull { option ->
                        val optionMap = option.asDshMapOrEmpty()
                        val label = optionMap["label"].dshString() ?: return@mapNotNull null
                        DshQuestionOption(label, optionMap["description"].dshString())
                    },
                    multiSelect = map["multiSelect"] == true,
                )
            }
            return DshQuestionRequest(
                eventId = frame["eventId"].dshString().orEmpty(),
                questions = questions,
                timed = request["wait"].asDshMapOrEmpty()["timed"] == true,
            )
        }
    }
}
